package logic

import dto.PageResponse
import model.Account
import table.AccountTable
import neton.database.dsl.*
import neton.database.api.DbContext
import neton.security.password.PasswordHasher

import neton.logging.Logger

@neton.core.annotations.Logic(logger = "logic.account")
class AccountLogic(
    private val log: Logger,
    private val db: DbContext,
) {

    suspend fun page(
        page: Int,
        size: Int,
        nickname: String? = null,
        mobile: String? = null,
        /** 用户编号。数字主键做精确匹配，不做 `%x%`：中间匹配用不上主键索引，
         *  语义也怪——搜 "123" 会把 100001230 和 100012345 一起捞出来，
         *  而运营手里的 uid 从来都是完整的。
         *  非数字输入由 controller 拦掉并直接返回空页，到不了这里。 */
        uid: Long? = null,
        username: String? = null,
        status: Int? = null,
        // 默认隐藏系统生成的陪玩机器人(is_robot=1)；admin 需查看时传 includeRobot=true。
        includeRobot: Boolean = false,
        // 默认隐藏无凭证游客(is_guest=1，如客服 widget 访客)；admin 需查看时传 includeGuest=true。
        includeGuest: Boolean = false
    ): PageResponse<Account> {
        val result = AccountTable.query {
            where {
                and(
                    whenNotBlank(nickname) { Account::nickname like "%$it%" },
                    whenNotBlank(mobile) { Account::mobile like "%$it%" },
                    whenNotBlank(username) { Account::username like "%$it%" },
                    whenPresent(uid) { Account::id eq it },
                    whenPresent(status) { Account::status eq it },
                    // includeRobot=false → 过滤 is_robot=0；true → 传 null 跳过该条件。
                    whenPresent(if (includeRobot) null else 0) { Account::isRobot eq it },
                    whenPresent(if (includeGuest) null else 0) { Account::isGuest eq it }
                )
            }
            orderBy(Account::id.desc())
        }.page(page, size)
        return PageResponse(result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0)
    }

    suspend fun get(id: Long): Account? {
        return AccountTable.get(id)
    }

    suspend fun update(member: Account) {
        AccountTable.update(member)
        log.info("Updated account: id=${member.id}")
    }

    /**
     * 管理员重设会员登录密码。明文只在此方法内经 [PasswordHasher] 加盐哈希后落库，
     * 不写日志、不回传（日志只记 userId）。与注册/登录同一套哈希，改完即可用新密码登录。
     */
    suspend fun updatePassword(userId: Long, rawPassword: String) {
        val member = AccountTable.get(userId)
            ?: throw IllegalArgumentException("Account not found: $userId")
        AccountTable.update(member.copy(password = PasswordHasher.hash(rawPassword)))
        log.info("Updated account password: userId=$userId")
    }

    /** 扫所有 `is_robot=1` 且 nickname 为 null/空 的陪玩账号,用 [NicknameGenerator] 批量补昵称.
     *
     *  - generator 返 null (词库未初始化或单边空) → 该条计入 skippedPoolEmpty
     *  - 单条 update 失败 → 计入 errors,继续下一条 (不抛, 保证大部分补成功)
     *  - 已有非空 nickname 的 bot 不动 (admin 想重抽请先清空 nickname)
     */
    /**
     * @param onlyEmpty true=只补 nickname='' 的(增量); false=重抽所有 is_robot=1(强制刷新).
     */
    suspend fun fillBotNicknames(generator: NicknameGenerator, onlyEmpty: Boolean = false): FillBotNicknamesResult {
        // account_users.nickname 列定义 NOT NULL DEFAULT '' (V001).
        // onlyEmpty=false: 重抽所有陪玩账号 (force regenerate, 旧丑名换新浪漫名).
        // onlyEmpty=true : 只补 nickname='' (增量补漏).
        val candidates = AccountTable.query {
            where {
                if (onlyEmpty) {
                    and(
                        Account::isRobot eq 1,
                        Account::nickname eq "",
                    )
                } else {
                    Account::isRobot eq 1
                }
            }
        }.list()
        var scanned = candidates.size
        var filled = 0
        var skippedPoolEmpty = 0
        var errors = 0
        for (m in candidates) {
            val newNick = generator.next()
            if (newNick == null) {
                skippedPoolEmpty++
                continue
            }
            try {
                AccountTable.update(m.copy(nickname = newNick))
                filled++
            } catch (t: Throwable) {
                errors++
                log.info("fill_bot_nickname.failed id=${m.id} reason=${t.message ?: t::class.simpleName}")
            }
        }
        log.info("fill_bot_nicknames.done scanned=$scanned filled=$filled skipped_pool_empty=$skippedPoolEmpty errors=$errors")
        return FillBotNicknamesResult(scanned, filled, skippedPoolEmpty, errors)
    }
}

data class FillBotNicknamesResult(
    val scanned: Int,
    val filled: Int,
    val skippedPoolEmpty: Int,
    val errors: Int,
)
