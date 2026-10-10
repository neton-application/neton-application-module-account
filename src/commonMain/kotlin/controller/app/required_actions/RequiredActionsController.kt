package controller.app.required_actions

import controller.app.auth.dto.RequiredAction
import kotlinx.serialization.Serializable
import logic.AccountProfileLogic
import logic.RequiredActionsLogic
import neton.core.annotations.*
import neton.core.interfaces.Identity

/**
 * `GET /app/account/required-actions` 响应 envelope。
 *
 * 字段名严格对齐 spec PLATFORM_REQUIRED_ACTIONS_CONTRACT §3.2 wire 名 `requiredActions`
 * （camelCase；与 `AccountLoginResponse.requiredActions` 一致，client 同一 reducer
 * 同时处理 fresh-login 路径与 auto-login restore 路径）。
 */
@Serializable
data class RequiredActionsResponse(
    val requiredActions: List<RequiredAction>,
)

/** `POST /app/account/bind-mobile` 请求体。 */
@Serializable
data class BindMobileRequest(
    val mobile: String,
)

/**
 * Account-level 端点：当前登录用户的 post-login required actions 查询。
 *
 * 包路径设计：本类放在 `controller.app.required_actions`（独特包名），
 * 不放在 `controller.app.account` —— 那个 FQN 已被 `module-platform`
 * 占用（spec MODULE_PRIVCHAT_SPEC 的开发者平台 account 接口），同名会导致
 * Kotlin Native link 阶段类符号冲突。逻辑上两者无关：
 * - `module-platform` `controller.app.account.AccountController` 挂在
 *   `/app/member/account/...`（开发者凭据 / 调用统计）
 * - 本类挂在 `/app/account/...`（用户登录后必做动作）
 *
 * 路径设计要点（spec PLATFORM_REQUIRED_ACTIONS_CONTRACT §3.2）：
 * - 注解用相对路径 `/account`，由 framework 加 `/app` 前缀，最终 mount 为 `/app/account/...`
 * - 与 `AuthController` 的 `@Controller("/auth")` 风格一致
 *
 * 鉴权：登录态必需。未携带有效 token → framework 返 401；
 * required actions 本身**不阻断 token 颁发**（设计层 client gate，不是 server auth refusal）。
 */
@Controller("/account")
class RequiredActionsController(
    private val requiredActionsLogic: RequiredActionsLogic,
    private val accountProfileLogic: AccountProfileLogic,
) {

    /**
     * 当前用户的 required actions 列表。
     *
     * 用途：
     * - auto-login / restore session 后二次确认（无 LoginResponse 路径）
     * - 完成一个 action 后重新拉权威列表
     *
     * 返回空数组 = 用户可进入主功能；非空 = client 必须按顺序处理。
     */
    @Get("/required-actions")
    suspend fun listRequiredActions(identity: Identity): RequiredActionsResponse {
        val uid = identity.id.toLong()
        val actions = requiredActionsLogic.computeForUid(uid)
        return RequiredActionsResponse(requiredActions = actions)
    }

    /**
     * 注册流程里的手机号绑定（`bind_mobile` action 的提交端点）。
     *
     * **不发也不校验短信验证码**：这是注册流程中的首次绑定，插一次短信会把流程拦腰截断。
     * 换号走 `PUT /app/member/user/update-mobile`，那条仍然要验证码——免验证入口只开给
     * 「还没绑过」的情况，否则它就成了绕过验证换号的后门（校验在
     * [AccountProfileLogic.bindMobileWithoutVerification] 里）。
     *
     * 完成后客户端必须重新拉 [listRequiredActions] 取权威列表，不要自己把这一项划掉。
     */
    @Post("/bind-mobile")
    suspend fun bindMobile(identity: Identity, @Body request: BindMobileRequest): RequiredActionsResponse {
        val uid = identity.id.toLong()
        accountProfileLogic.bindMobileWithoutVerification(uid, request.mobile)
        return RequiredActionsResponse(requiredActions = requiredActionsLogic.computeForUid(uid))
    }
}
