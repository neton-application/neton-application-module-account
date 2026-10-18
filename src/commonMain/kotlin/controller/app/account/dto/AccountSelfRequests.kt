package controller.app.account.dto

import logic.AccountErrorCodes
import controller.app.auth.dto.E164_REGEX
import kotlinx.serialization.Serializable
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Pattern
import neton.validation.annotations.Size

// ──────────── 拆分端点（spec MODULE_MEMBER_PROFILE_SPEC §4） ────────────
//
// 旧的 UpdateProfileRequest 混改 nickname + avatar 已下线；按操作单一职责拆开。

@Serializable
data class UpdateNicknameRequest(
    @property:NotBlank
    @property:Size(min = 2, max = 32)
    val nickname: String,
)

@Serializable
data class UpdateAvatarRequest(
    /** application 唯一用户态上传入口（POST /app/infra/file/upload）返回的 fileId。 */
    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val fileId: String,
)

@Serializable
data class UpdateUsernameRequest(
    @property:NotBlank
    @property:Size(min = 3, max = 32)
    val username: String,
)

@Serializable
data class UpdateUsernameResponse(
    val username: String,
    /** 下次允许改 username 的时间戳（millis）。客户端可用来灰显按钮。 */
    val nextChangeAvailableAt: Long,
)

@Serializable
data class UpdateBioRequest(
    /** null 或空串视作清空；非 null 时长度 ≤ 200。 */
    @property:Size(max = 200)
    val bio: String? = null,
)

@Serializable
data class UpdateGenderRequest(
    /** 仅 0 / 1 / 2 / 9 合法（详 §4.6） */
    val gender: Int,
)

@Serializable
data class UpdateBirthdayRequest(
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
data class UpdateMobileRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = AccountErrorCodes.INVALID_MOBILE)
    val mobile: String,

    @property:NotBlank
    @property:Size(min = 4, max = 8)
    val smsCode: String,
)

@Serializable
data class UpdatePasswordRequest(
    /**
     * 当前密码。从没设过密码的账号（短信 / 社交登录注册）可留空 —— 以前这里是 @NotBlank + 最少 8 位，
     * 而逻辑层对无密码账号本就跳过校验，两者打架的结果是这类用户永远设不了密码。
     * 已有密码时是否必填、是否正确由 controller 判。不限最短长度：旧密码可能是按旧规则设的。
     */
    @property:Size(max = 128)
    val oldPassword: String = "",

    @property:NotBlank
    @property:Size(min = 8, max = 128)
    val newPassword: String,
)

@Serializable
data class ResetPasswordBySmsRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = AccountErrorCodes.INVALID_MOBILE)
    val mobile: String,

    @property:NotBlank
    @property:Size(min = 4, max = 8)
    val smsCode: String,

    @property:NotBlank
    @property:Size(min = 8, max = 128)
    val newPassword: String,
)
