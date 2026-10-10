package logic

import controller.app.auth.dto.RequiredAction
import model.Account

/**
 * Post-login Required Actions 计算（spec PLATFORM_REQUIRED_ACTIONS_CONTRACT §5）。
 *
 * 两个调用点（保持唯一权威源 - 同一个 `computeForAccount` 函数）：
 * 1. [AccountAuthLogic.buildResponse] 拼 `AccountLoginResponse` 时
 * 2. [controller.app.account.AccountController.listRequiredActions]
 *
 * v1 只判断一个 action（`complete_profile.nickname`），通过昵称默认 pattern
 * 兜底；未来加 schema 字段 `account_users.profile_completed_at` 或独立
 * `user_required_actions` 表时，只改 [isProfileIncomplete] / [computeForAccount]
 * 实现，**契约不变**（依旧返回 `List<RequiredAction>`）。
 *
 * 不在此类做：
 * - HTTP 鉴权 / 路由（controller 负责）
 * - DB 写入（v1 不写；v2 给独立 table）
 * - UI 决定（client 自己 dispatch）
 */
class RequiredActionsLogic(
    private val accountLogic: AccountLogic,
    private val authPolicy: port.AccountAuthPolicy = port.AccountAuthPolicy(),
    /** 其他模块登记的补全项（如 member 的「填写邀请码」）。account 本身不认识它们。 */
    private val extensions: port.AccountExtensions = port.AccountExtensions(),
) {

    /**
     * uid 入口：先取 member，未找到的兜底空数组（不抛错，不让登录失败）。
     *
     * **列表顺序就是用户看到的补全顺序**：绑定手机号 → 填写邀请码 → 设置昵称。客户端按数组
     * 次序逐个处理，所以这里的先后不是随意排的。
     */
    suspend fun computeForUid(uid: Long): List<RequiredAction> {
        val account = accountLogic.get(uid) ?: return emptyList()
        // 账号自带项与扩展项统一按 order 排序 —— 列表顺序就是用户看到的补全顺序。
        val ordered = mutableListOf<Pair<Int, List<RequiredAction>>>()
        ordered += port.RequiredActionContributor.ORDER_BIND_MOBILE to bindMobileAction(account)
        for (contributor in extensions.requiredActionContributors()) {
            ordered += contributor.order to contributor.contribute(account)
        }
        // 昵称排最后：前面都是「这个账号属于谁」，昵称是展示层，先确权再取名。
        ordered += port.RequiredActionContributor.ORDER_COMPLETE_PROFILE to computeForAccount(account)
        return ordered.sortedBy { it.first }.flatMap { it.second }
    }

    /**
     * 手机号**推荐**补全：登录后下发，但不阻断（required=false）。
     * 绑定不做短信验证（产品决定），所以这里只判断"有没有填"，不判断"验没验证过"。
     */
    private fun bindMobileAction(account: Account): List<RequiredAction> {
        if (!authPolicy.mobileRequired) return emptyList()
        if (!port.RequiredActionAnchors.isAnchorHit(account, authPolicy.mobileRequiredSince)) return emptyList()
        if (!account.mobile.isNullOrBlank()) return emptyList()
        return listOf(
            RequiredAction(
                action = "bind_mobile",
                // **推荐而非强制**：required=false 的语义是「认识它的客户端展示并允许跳过，
                // 不认识的静默略过」（契约 §4.3）。
                //
                // 设成 true 的后果实测过：旧版 App 遇到不认识的 required action 会 fail-closed
                // 停在「需要更新客户端」页，进不了主界面。开关一开，**所有没绑手机号的存量
                // 用户下次登录全被拦住**——生产 2102 个账号里只有 11 个填了手机号。
                required = false,
                title = "绑定手机号",
                titleKey = "requiredAction.bindMobile",
                fields = listOf("mobile"),
            ),
        )
    }

    /** member 入口：纯函数。R8.4 single source of truth。 */
    fun computeForAccount(account: Account): List<RequiredAction> {
        val actions = mutableListOf<RequiredAction>()
        if (isProfileIncomplete(account)) {
            actions += RequiredAction(
                action = "complete_profile",
                required = true,
                title = "设置昵称",
                titleKey = "requiredAction.completeProfile.nickname",
                fields = listOf("nickname"),
            )
        }
        // 未来扩展位 — 按需追加新 action 类型：
        // if (needAcceptTerms(member)) actions += RequiredAction(action="accept_terms", title="同意新版协议", titleKey="requiredAction.acceptTerms", version=...)
        // if (needBindMobile(member)) actions += RequiredAction(action="bind_mobile", title="绑定手机号", titleKey="requiredAction.bindMobile", reason=...)
        // if (needCompleteKyc(member)) actions += RequiredAction(action="complete_kyc", title="完成身份认证", titleKey="requiredAction.completeKyc")
        return actions
    }

    /**
     * v1 实现：通过昵称匹配自动注册的默认 pattern 兜底判断。
     *
     * 注册不再自动编造昵称（SMS/社交缺省一律空串），空昵称即未完成 → gate complete_profile。
     * 下方 Member_ pattern 检测仅为**存量数据兼容**（历史自动生成的 `Member_xxxx`/`Member_abc123`
     * 仍应被引导改名），新数据不会再产生该 pattern。
     *
     * **Edge case（存量兼容期接受）**：用户主动把 nickname 改成 `Member_1234`（恰好匹配 pattern）
     * → 下次登录又被 gate。v2 加 `profile_completed_at` 后此 edge case 消失。
     */
    fun isProfileIncomplete(member: Account): Boolean {
        val nickname = member.nickname.trim()
        if (nickname.isEmpty()) return true
        // SMS auto-register pattern: "Member_" + 4 digits
        if (nickname.matches(SMS_DEFAULT_NICKNAME_REGEX)) return true
        // Social auto-register pattern: "Member_" + 6 alphanumeric chars
        if (nickname.matches(SOCIAL_DEFAULT_NICKNAME_REGEX)) return true
        return false
    }

    companion object {
        private val SMS_DEFAULT_NICKNAME_REGEX = Regex("^Member_\\d{4}$")
        private val SOCIAL_DEFAULT_NICKNAME_REGEX = Regex("^Member_[A-Za-z0-9]{6}$")
    }
}
