package controller.app.auth.dto

import controller.admin.auth.dto.LoginDeviceInfo
import kotlinx.serialization.Serializable
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Pattern
import neton.validation.annotations.Size

/**
 * 成员登录请求（密码 / SMS 共用）。
 *
 * 登录成功后 application 会立刻为该 device 签发 IM token（spec UPSTREAM §5），
 * 因此 [device] 必填；member token 的 JWT claim 也会绑定 `device.deviceId`，
 * refresh 时必须一致才允许续签。
 */
@Serializable
data class AccountLoginRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = E164_MESSAGE)
    val mobile: String,

    @property:Size(min = 8, max = 128)
    val password: String? = null,

    @property:Size(min = 4, max = 8)
    val smsCode: String? = null,

    val device: LoginDeviceInfo? = null,

    /** 邀请码(MEMBER_INVITE_CODE):仅新注册时生效;老用户登录携带则忽略。 */
    val inviteCode: String? = null,

    /** 注册时直填昵称(填了跳过 complete_profile 引导)。 */
    val nickname: String? = null,
)
