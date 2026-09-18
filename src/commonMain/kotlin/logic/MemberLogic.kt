package logic

import dto.PageResponse
import model.Member
import model.MemberLevelRecord
import model.MemberPointRecord
import table.MemberTable
import table.MemberLevelRecordTable
import table.MemberPointRecordTable
import neton.database.dsl.*
import neton.database.api.DbContext
import neton.security.password.PasswordHasher

import neton.logging.Logger

@neton.core.annotations.Logic(logger = "logic.member")
class MemberLogic(
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
        levelId: Long? = null,
        groupId: Long? = null,
        // 默认隐藏系统生成的陪玩机器人(is_robot=1)；admin 需查看时传 includeRobot=true。
        includeRobot: Boolean = false,
        // 默认隐藏无凭证游客(is_guest=1，如客服 widget 访客)；admin 需查看时传 includeGuest=true。
        includeGuest: Boolean = false
    ): PageResponse<Member> {
        val result = MemberTable.query {
            where {
                and(
                    whenNotBlank(nickname) { Member::nickname like "%$it%" },
                    whenNotBlank(mobile) { Member::mobile like "%$it%" },
                    whenNotBlank(username) { Member::username like "%$it%" },
                    whenPresent(uid) { Member::id eq it },
                    whenPresent(status) { Member::status eq it },
                    whenPresent(levelId) { Member::levelId eq it },
                    whenPresent(groupId) { Member::groupId eq it },
                    // includeRobot=false → 过滤 is_robot=0；true → 传 null 跳过该条件。
                    whenPresent(if (includeRobot) null else 0) { Member::isRobot eq it },
                    whenPresent(if (includeGuest) null else 0) { Member::isGuest eq it }
                )
            }
            orderBy(Member::id.desc())
        }.page(page, size)
        return PageResponse(result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0)
    }

    suspend fun get(id: Long): Member? {
        return MemberTable.get(id)
    }

    suspend fun update(member: Member) {
        MemberTable.update(member)
        log.info("Updated member: id=${member.id}")
    }

    /**
     * 管理员重设会员登录密码。明文只在此方法内经 [PasswordHasher] 加盐哈希后落库，
     * 不写日志、不回传（日志只记 userId）。与注册/登录同一套哈希，改完即可用新密码登录。
     */
    suspend fun updatePassword(userId: Long, rawPassword: String) {
        val member = MemberTable.get(userId)
            ?: throw IllegalArgumentException("Member not found: $userId")
        MemberTable.update(member.copy(password = PasswordHasher.hash(rawPassword)))
        log.info("Updated member password: userId=$userId")
    }

    suspend fun updateLevel(userId: Long, levelId: Long, level: Int, reason: String?) {
        val member = MemberTable.get(userId)
            ?: throw IllegalArgumentException("Member not found: $userId")

        // Update member + create level record in a single transaction
        db.transaction {
            MemberTable.update(member.copy(levelId = levelId))

            MemberLevelRecordTable.insert(MemberLevelRecord(
                userId = userId,
                levelId = levelId,
                level = level,
                reason = reason,
                description = "Admin updated member level"
            ))
        }

        log.info("Updated member level: userId=$userId, levelId=$levelId, level=$level")
    }

    /**
     * Take [point] from a member's balance, atomically, and record it.
     *
     * [updatePoint] reads the balance and writes it back, which is fine for crediting but not for
     * a debit: two concurrent redemptions each read the same balance, each write it back, and the
     * member pays once for two items — or the balance goes negative. This uses a conditional
     * update instead, so the database decides who wins:
     *
     *     UPDATE member_users SET point = point - :n WHERE id = :id AND point >= :n
     *
     * Zero affected rows means the balance was insufficient at the moment of the update, and
     * nothing is written. On success the ledger row is written in the same transaction with the
     * balance read back after the update, so `totalPoint` is the real post-debit figure.
     *
     * Runs inside the caller's transaction when there is one, so a caller can roll the debit back
     * together with its own writes.
     *
     * @return false if the balance was insufficient; true if the debit and its ledger row were written.
     */
    suspend fun debitPoint(userId: Long, point: Int, bizType: Int, title: String, description: String?): Boolean {
        require(point > 0) { "debit must be positive, got $point" }
        var debited = false
        db.transaction {
            val affected = db.execute(
                "UPDATE member_users SET point = point - :n, updated_at = :now WHERE id = :id AND point >= :n",
                mapOf("n" to point, "id" to userId, "now" to kotlin.time.Clock.System.now().toEpochMilliseconds()),
            )
            if (affected == 1L) {
                val after = MemberTable.get(userId) ?: throw IllegalArgumentException("Member not found: $userId")
                MemberPointRecordTable.insert(MemberPointRecord(
                    userId = userId,
                    bizType = bizType,
                    title = title,
                    point = -point,
                    totalPoint = after.point,
                    description = description,
                ))
                debited = true
            }
        }
        if (debited) log.info("Debited member point: userId=$userId, point=$point, bizType=$bizType")
        return debited
    }

    suspend fun updatePoint(userId: Long, point: Int, bizType: Int, title: String, description: String?) {
        val member = MemberTable.get(userId)
            ?: throw IllegalArgumentException("Member not found: $userId")

        val newPoint = member.point + point

        // Update member point + create point record in a single transaction
        db.transaction {
            MemberTable.update(member.copy(point = newPoint))

            MemberPointRecordTable.insert(MemberPointRecord(
                userId = userId,
                bizType = bizType,
                title = title,
                point = point,
                totalPoint = newPoint,
                description = description
            ))
        }

        log.info("Updated member point: userId=$userId, point=$point, totalPoint=$newPoint")
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
        // member_users.nickname 列定义 NOT NULL DEFAULT '' (V001).
        // onlyEmpty=false: 重抽所有陪玩账号 (force regenerate, 旧丑名换新浪漫名).
        // onlyEmpty=true : 只补 nickname='' (增量补漏).
        val candidates = MemberTable.query {
            where {
                if (onlyEmpty) {
                    and(
                        Member::isRobot eq 1,
                        Member::nickname eq "",
                    )
                } else {
                    Member::isRobot eq 1
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
                MemberTable.update(m.copy(nickname = newNick))
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
