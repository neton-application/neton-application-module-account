package logic

import model.Account
import neton.database.api.DbContext
import neton.logging.Logger
import port.CreateMemberAccountCommand
import port.AccountIdentityAdapter
import table.AccountTable

/**
 * 游客账号开通。
 *
 * 「无凭证、设备绑定的账号」是账号体系自己的概念 —— 客服 widget 访客、游戏游客登录、
 * 试用账号要的是同一件事，所以它属于 member 而不是调用方。调用方拿到的是一个正常的
 * [Account]，只是 `is_guest=1`，默认不进会员列表与排行（比照 `is_robot`）。
 *
 * 建行这一步必须在这里：`AccountIdentityAdapter.createOrBindAccount` **只分配 id**，
 * 真正的 `account_users` 插入是模块内部的 `insertAccountWithProvidedId`，外部模块够不着。
 *
 * 升级为正式会员 = 给同一行绑上凭证并把 `is_guest` 清零；id 不变，所以 IM 身份与
 * 全部历史原样保留。
 */
class AccountGuestLogic(
    private val log: Logger,
    private val db: DbContext,
    private val identityAdapter: AccountIdentityAdapter,
) {

    suspend fun createGuest(displayName: String? = null, deviceId: String? = null): Account {
        val ref = identityAdapter.createOrBindAccount(
            CreateMemberAccountCommand(displayName = displayName, deviceId = deviceId)
        )
        // privchat 支撑时 createOrBindAccount 可能命中既有账号（单 ID 模型），
        // 那种情况下不该再插一行，也不该把一个正式会员降级成游客
        AccountTable.get(ref.memberId)?.let { return it }

        val member = insertAccountWithProvidedId(
            db,
            Account(
                id = ref.memberId,
                nickname = displayName ?: "",
                status = 1,
                isGuest = 1,
            )
        )
        log.info("account.guest.created id=${member.id} device=$deviceId")
        return member
    }

    /** 绑定凭证后调用：同一行清零 is_guest，id 与历史不变。 */
    suspend fun promote(memberId: Long): Account? {
        val member = AccountTable.get(memberId) ?: return null
        val promoted = member.copy(isGuest = 0)
        AccountTable.update(promoted)
        return promoted
    }
}
