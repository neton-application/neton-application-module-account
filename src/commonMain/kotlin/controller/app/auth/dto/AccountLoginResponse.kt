package controller.app.auth.dto

import kotlinx.serialization.Serializable

/**
 * 成员登录响应（spec TOKEN_UNIFICATION_SPEC §8 LoginResponse）。
 *
 * 单一 unified token：
 * - [accessToken] / [refreshToken]：server 签发的 RS256 unified token；HTTP + IM 通用
 * - 客户端拿这一对 token 即可：访问 application 的 app-api 路由组，以及 IM RPC Authenticate 都用 [accessToken]
 *
 * 严禁字段：im_token / im_refresh_token / im_device_id（spec §8.2 终态）
 */
@Serializable
data class AccountLoginResponse(
    val userId: Long,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val refreshExpiresIn: Long,
    val deviceId: String,
    val sessionVersion: Long,
    val deviceCreated: Boolean,
    val scope: List<String>,
    val issuer: String = "privchat-server",
    /**
     * R8.4a — Post-login Required Actions（spec PLATFORM_REQUIRED_ACTIONS_CONTRACT §3.1）。
     *
     * 空数组 = client 可直接进 workspace；非空 = client 必须进 RequiredActionFlow，
     * 按顺序完成每个 action 后才能进入主功能。每完成一个 action 必须重新拉
     * `GET /app/account/required-actions` 取权威新列表。
     *
     * 默认空数组保后向兼容：旧 client 不读字段视同空。
     */
    val requiredActions: List<RequiredAction> = emptyList(),
)
