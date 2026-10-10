package port

/**
 * MEMBER-IDENTITY-ADAPTER v1 —— member 账号身份的中立模型(不依赖任何后端)。
 * 见 docs/ACCOUNT_IDENTITY_MODE_SPEC.md。
 */

/** 账号来源 provider 标识。 */
object AccountIdentityProvider {
    const val BUILTIN = "builtin"
    const val PRIVCHAT = "privchat"
}

/** 注册/登录时产生或绑定底层账号的入参。 */
data class CreateMemberAccountCommand(
    val mobile: String? = null,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val deviceId: String? = null,
    val platform: String? = null,
)

/**
 * 账号引用 —— **单 ID 模型**:`memberId` 始终是 account_users 主 ID。
 * - builtin:`memberId` = member 内置 ID 生成器产生;
 * - privchat:`memberId` = privchat-server 返回的 user_id(privchat user_id 直接就是 member id)。
 */
data class AccountRef(
    val provider: String,
    val memberId: Long,
    /** 本次是否新建(区分注册 vs 命中已有)。 */
    val created: Boolean = false,
)

/** 签发 token 入参(device 信息供后端构造设备记录/令牌)。 */
data class IssueMemberTokenCommand(
    val memberId: Long,
    val deviceId: String? = null,
    val platform: String? = null,
    val deviceName: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val ipAddress: String? = null,
)

/** 刷新 token 入参。 */
data class RefreshMemberTokenCommand(
    val refreshToken: String,
    val deviceId: String? = null,
)

/**
 * token 结果(后端无关)。字段足以构造 member 登录响应;requiredActions 由 member 业务层附加。
 */
data class AccountTokenBundle(
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
    val issuer: String,
)

/** 资料变更事件(作为 adapter 回调入参,定义在 member,不引入独立总线)。 */
data class AccountProfileChangedEvent(
    val memberId: Long,
    val changedFields: Set<String>,
    val username: String? = null,
    val nickname: String? = null,
    val avatar: String? = null,
)
data class AccountMobileChangedEvent(val memberId: Long, val newMobile: String, val oldMobile: String? = null)
data class AccountPasswordChangedEvent(val memberId: Long)
