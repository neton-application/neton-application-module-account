package controller.admin.account.dto

import logic.AccountErrorCodes
import kotlinx.serialization.Serializable
import neton.validation.annotations.Max
import neton.validation.annotations.Min
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Pattern
import neton.validation.annotations.Size

@Serializable
data class AccountVO(
    val id: Long = 0,
    val identityProvider: String = "builtin",
    val username: String? = null,
    val usernameUpdatedAt: Long? = null,
    val mobile: String? = null,
    val nickname: String? = null,
    val avatar: String? = null,
    val gender: Int = 0,
    val bio: String? = null,
    val birthday: String? = null,
    val isRobot: Int = 0,
    val isGuest: Int = 0,
    val status: Int? = null,
    val registerIp: String? = null,
    /** 注册 IP 反查出的归属地。与 [loginRegion] 同一套 GeoLite 解析，只是入参不同。 */
    val registerRegion: String? = null,
    val loginIp: String? = null,
    val loginRegion: String? = null,
    val loginDate: Long? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class UpdateAccountRequest(
    @property:Min(1)
    val id: Long,

    @property:Pattern("^1[3-9]\\d{9}$", message = AccountErrorCodes.INVALID_MOBILE)
    val mobile: String? = null,

    @property:NotBlank
    @property:Size(min = 1, max = 64)
    val nickname: String,

    @property:Size(min = 0, max = 255)
    val avatar: String? = null,

    @property:Min(0)
    @property:Max(1)
    val status: Int = 1,
)
