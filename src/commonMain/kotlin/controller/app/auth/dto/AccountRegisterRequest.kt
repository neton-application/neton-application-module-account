package controller.app.auth.dto

import controller.admin.auth.dto.LoginDeviceInfo
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Size
import kotlinx.serialization.Serializable

/** 通用注册入口(MEMBER_INVITE_CODE §5.1)。v1 仅 USERNAME_PASSWORD(PHONE_SMS 走 sms-login)。 */
@Serializable
data class AccountRegisterRequest(
    /** USERNAME_PASSWORD(v1 唯一合法值;PHONE_SMS 复用 sms-login) */
    @property:NotBlank
    val mode: String,

    @property:NotBlank
    @property:Size(min = 3, max = 32)
    val username: String,

    @property:NotBlank
    @property:Size(min = 8, max = 128)
    val password: String,

    val nickname: String? = null,
    val inviteCode: String? = null,
    val device: LoginDeviceInfo? = null,
)
