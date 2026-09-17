package controller.app.member.dto

import controller.app.auth.dto.E164_MESSAGE
import controller.app.auth.dto.E164_REGEX
import kotlinx.serialization.Serializable
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Pattern
import neton.validation.annotations.Size

// ──────────── 拆分端点（spec MODULE_MEMBER_PROFILE_SPEC §4） ────────────
//
// 旧的 UpdateMemberProfileRequest 混改 nickname + avatar 已下线；按操作单一职责拆开。

@Serializable
data class UpdateMemberNicknameRequest(
    @property:NotBlank
    @property:Size(min = 2, max = 32)
    val nickname: String,
)

@Serializable
data class UpdateMemberAvatarRequest(
    /** application 唯一用户态上传入口（POST /app/infra/file/upload）返回的 fileId。 */
    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val fileId: String,
)

@Serializable
data class UpdateMemberUsernameRequest(
    @property:NotBlank
    @property:Size(min = 3, max = 32)
    val username: String,
)

@Serializable
data class UpdateMemberUsernameResponse(
    val username: String,
    /** 下次允许改 username 的时间戳（millis）。客户端可用来灰显按钮。 */
    val nextChangeAvailableAt: Long,
)

@Serializable
data class UpdateMemberBioRequest(
    /** null 或空串视作清空；非 null 时长度 ≤ 200。 */
    @property:Size(max = 200)
    val bio: String? = null,
)

@Serializable
data class UpdateMemberGenderRequest(
    /** 仅 0 / 1 / 2 / 9 合法（详 §4.6） */
    val gender: Int,
)

@Serializable
data class UpdateMemberBirthdayRequest(
    /** ISO `YYYY-MM-DD`；null 或空串视作清空。 */
    val birthday: String? = null,
)

@Serializable
data class SendEmailCodeRequest(
    @property:NotBlank
    val email: String,
    /** reset_password（匿名）| bind_email（需登录） */
    val scene: String = "reset_password",
)

@Serializable
data class BindEmailRequest(
    @property:NotBlank
    val email: String,
    @property:NotBlank
    val code: String,
)

@Serializable
data class ResetPasswordByEmailRequest(
    @property:NotBlank
    val email: String,
    @property:NotBlank
    val code: String,
    @property:NotBlank
    @property:Size(min = 8, max = 128)
    val newPassword: String,
)

@Serializable
data class UpdateMemberMobileRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = E164_MESSAGE)
    val mobile: String,

    @property:NotBlank
    @property:Size(min = 4, max = 8)
    val smsCode: String,
)

@Serializable
data class UpdateMemberPasswordRequest(
    @property:NotBlank
    @property:Size(min = 8, max = 128)
    val oldPassword: String,

    @property:NotBlank
    @property:Size(min = 8, max = 128)
    val newPassword: String,
)

@Serializable
data class ResetMemberPasswordRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = E164_MESSAGE)
    val mobile: String,

    @property:NotBlank
    @property:Size(min = 4, max = 8)
    val smsCode: String,

    @property:NotBlank
    @property:Size(min = 8, max = 128)
    val newPassword: String,
)
