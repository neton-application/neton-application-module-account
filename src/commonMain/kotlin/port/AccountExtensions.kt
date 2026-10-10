package port

import controller.app.auth.dto.RequiredAction
import kotlin.time.Instant
import model.Account

/**
 * 账号模块对外的扩展点。
 *
 * account 是很多业务的地基，它**不认识**任何具体业务（邀请、成长、签到……）。业务模块要在
 * 账号流程里插一脚，只能通过这里登记的接口，并且只能单向依赖 account：account 永远不会
 * 反过来 import 它们。
 *
 * 由 [init.AccountRuntimeBootstrap] 创建并 bind 一个实例；依赖 account 的模块在自己的
 * bootstrap 里（拓扑序在 account 之后）取出来登记。读取发生在请求期，所以登记顺序晚于
 * account 初始化没有关系。
 */
class AccountExtensions {
    private val registrationHooks = mutableListOf<AccountRegistrationHook>()
    private val requiredActionContributors = mutableListOf<RequiredActionContributor>()

    fun addRegistrationHook(hook: AccountRegistrationHook) {
        registrationHooks += hook
    }

    fun addRequiredActionContributor(contributor: RequiredActionContributor) {
        requiredActionContributors += contributor
    }

    internal fun registrationHooks(): List<AccountRegistrationHook> = registrationHooks.toList()

    internal fun requiredActionContributors(): List<RequiredActionContributor> =
        requiredActionContributors.sortedBy { it.order }
}

/** 一次注册的上下文。字段都是注册请求里本来就有的东西，account 只负责透传、不解释。 */
data class AccountRegistrationContext(
    /** 注册方式，见 [AccountAuthPolicy.MODE_PHONE_SMS] 等。 */
    val mode: String,
    /** 脱敏后的注册标识（手机号 / 用户名），用于审计记录。 */
    val identifierMasked: String?,
    /** 注册时带的邀请码，原样透传；没填为 null。account 不校验它，由接管它的扩展处理。 */
    val inviteCode: String?,
)

/**
 * 注册扩展。
 *
 * 时序与改造前完全一致：
 *   [validate]（所有钩子）→ 建账号 → [afterRegistered]（所有钩子）
 *
 * - [validate] 抛异常 = **整次注册失败**，账号不会建出来（例：邀请码无效）。
 * - [afterRegistered] 在账号建好之后调用；它抛出的异常会让注册失败（与改造前「绑定邀请记录」
 *   失败即注册失败的语义一致）。只想尽力而为的副作用（欢迎语之类）应自己 catch。
 */
interface AccountRegistrationHook {
    /**
     * 是否接管注册请求里的邀请码。
     *
     * 带码注册时，只要没有任何钩子接管邀请码，注册就会被拒绝（`INVITE_CODE_UNSUPPORTED`），
     * 而不是悄悄吞掉这个码。
     */
    val consumesInviteCode: Boolean get() = false

    suspend fun validate(context: AccountRegistrationContext) {}

    suspend fun afterRegistered(context: AccountRegistrationContext, account: Account) {}
}

/**
 * 登录后强制补全项的贡献者（spec PLATFORM_REQUIRED_ACTIONS_CONTRACT §5）。
 *
 * **列表顺序就是用户看到的补全顺序**，按 [order] 升序。账号自带两项：
 * `bind_mobile` = [ORDER_BIND_MOBILE]，`complete_profile` = [ORDER_COMPLETE_PROFILE]。
 * 扩展项选一个落在两者之间或之后的值即可。
 */
interface RequiredActionContributor {
    val order: Int

    suspend fun contribute(account: Account): List<RequiredAction>

    companion object {
        const val ORDER_BIND_MOBILE = 100
        const val ORDER_COMPLETE_PROFILE = 900
    }
}

/**
 * 时间锚判定，账号自带的补全项与扩展共用同一套规则：
 *
 * - 未配置 → 约束所有账号；
 * - 配置后只约束锚点之后注册的账号（存量豁免）；
 * - `createdAt` 缺失的异常数据按存量对待（不约束，避免把老账号锁在补全页）。
 *
 * 支持 epoch millis 或 ISO-8601。
 */
object RequiredActionAnchors {
    fun isAnchorHit(account: Account, since: String?): Boolean {
        val raw = since?.trim().takeUnless { it.isNullOrEmpty() } ?: return true
        val created = account.createdAt ?: return false
        val sinceEpoch = raw.toLongOrNull()
            ?: runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
            ?: return false
        return created >= sinceEpoch
    }
}
