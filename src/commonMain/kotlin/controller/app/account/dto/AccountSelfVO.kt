package controller.app.account.dto

import kotlinx.serialization.Serializable
import model.Account

/**
 * 会员看自己的资料。字段与 [Account] 一一对应，**只去掉不该出库的**：
 * 密码哈希（拿到就能离线爆破）、会话版本与当前设备（会话机制的内部状态）、软删标记。
 * 以前接口直接回 [Account]，这几列原样带到了浏览器里。
 *
 * 其余字段保持原名原义 —— member 是多个产品共用的模块，各端客户端都在读这个形状。
 */
@Serializable
data class AccountSelfVO(
    val id: Long,
    val identityProvider: String,
    val mobile: String? = null,
    val email: String? = null,
    /** 是否设过密码：短信 / 社交登录注册的账号可能没有，前端据此显示「设置」还是「修改」 */
    val hasPassword: Boolean,
    val nickname: String,
    val avatar: String? = null,
    val status: Int,
    val registerIp: String? = null,
    val loginIp: String? = null,
    val loginDate: Long? = null,
    val username: String? = null,
    val usernameUpdatedAt: Long? = null,
    val gender: Int,
    val bio: String? = null,
    val birthday: String? = null,
    val isRobot: Int,
    val isGuest: Int,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

fun Account.toSelfVO() = AccountSelfVO(
    id = id,
    identityProvider = identityProvider,
    mobile = mobile,
    email = email,
    hasPassword = !password.isNullOrEmpty(),
    nickname = nickname,
    avatar = avatar,
    status = status,
    registerIp = registerIp,
    loginIp = loginIp,
    loginDate = loginDate,
    username = username,
    usernameUpdatedAt = usernameUpdatedAt,
    gender = gender,
    bio = bio,
    birthday = birthday,
    isRobot = isRobot,
    isGuest = isGuest,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
