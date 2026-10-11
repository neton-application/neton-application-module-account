package controller.app.account

import controller.app.account.dto.AccountSelfVO
import controller.app.account.dto.toSelfVO

import port.AccountIdentityAdapter
import port.AccountPasswordChangedEvent
import controller.app.account.dto.ResetPasswordBySmsRequest
import controller.app.account.dto.UpdateAvatarRequest
import controller.app.account.dto.UpdateBioRequest
import controller.app.account.dto.UpdateBirthdayRequest
import controller.app.account.dto.UpdateGenderRequest
import controller.app.account.dto.UpdateMobileRequest
import controller.app.account.dto.SendEmailCodeRequest
import controller.app.account.dto.BindEmailRequest
import controller.app.account.dto.UpdateNicknameRequest
import controller.app.account.dto.UpdatePasswordRequest
import controller.app.account.dto.UpdateUsernameRequest
import controller.app.account.dto.UpdateUsernameResponse
import enums.SmsScene
import kotlinx.serialization.Serializable
import logic.AccountAuthLogic
import logic.AccountLogic
import logic.AccountProfileLogic
import neton.core.annotations.*
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import neton.core.interfaces.Identity
import neton.security.password.PasswordHasher
import neton.validation.annotations.Min

@Serializable
data class AuthedSendSmsCodeRequest(
    /** 修改手机时传新手机号；修改密码时可省略（从登录态读取） */
    val mobile: String? = null,

    @property:Min(1)
    val scene: Int
)

/**
 * Account 用户面 profile 控制器（spec MODULE_MEMBER_PROFILE_SPEC §3 / §4）。
 *
 * 历史 `PUT /app/member/user/update`（混改 nickname + avatar）已下线，所有 profile
 * 字段按操作单一职责拆开，每个端点通过 [AccountProfileLogic] 调用
 * [AccountIdentityAdapter]，由部署模式决定是否同步外部身份投影。
 */
@Controller("/member/user")
class AccountSelfController(
    private val accountLogic: AccountLogic,
    private val accountAuthLogic: AccountAuthLogic,
    private val accountProfileLogic: AccountProfileLogic,
    // MEMBER-IDENTITY-ADAPTER: mobile/password 变更回调走 adapter(builtin=空;privchat adapter 同步)。
    private val identityAdapter: AccountIdentityAdapter? = null,
) {

    @Get("/get")
    suspend fun get(identity: Identity): AccountSelfVO? {
        val userId = identity.id.toLong()
        return accountLogic.get(userId)?.toSelfVO()
    }

    // ──────────── profile 拆分端点（spec §4） ────────────

    @Put("/update-nickname")
    suspend fun updateNickname(
        identity: Identity,
        @Body request: UpdateNicknameRequest,
    ) {
        accountProfileLogic.updateNickname(identity.id.toLong(), request.nickname)
    }

    @Put("/update-avatar")
    suspend fun updateAvatar(
        identity: Identity,
        @Body request: UpdateAvatarRequest,
    ): AccountSelfVO = accountProfileLogic.updateAvatar(identity.id.toLong(), request.fileId).toSelfVO()

    @Put("/update-username")
    @FreshAuth
    suspend fun updateUsername(
        identity: Identity,
        @Body request: UpdateUsernameRequest,
    ): UpdateUsernameResponse {
        val result = accountProfileLogic.updateUsername(identity.id.toLong(), request.username)
        return UpdateUsernameResponse(
            username = result.member.username
                ?: error("updateUsername returned member without username; logic invariant broken"),
            nextChangeAvailableAt = result.nextChangeAvailableAt,
        )
    }

    @Put("/update-bio")
    suspend fun updateBio(
        identity: Identity,
        @Body request: UpdateBioRequest,
    ) {
        accountProfileLogic.updateBio(identity.id.toLong(), request.bio)
    }

    @Put("/update-gender")
    suspend fun updateGender(
        identity: Identity,
        @Body request: UpdateGenderRequest,
    ) {
        accountProfileLogic.updateGender(identity.id.toLong(), request.gender)
    }

    @Put("/update-birthday")
    suspend fun updateBirthday(
        identity: Identity,
        @Body request: UpdateBirthdayRequest,
    ) {
        accountProfileLogic.updateBirthday(identity.id.toLong(), request.birthday)
    }

    // ──────────── mobile / password / SMS（spec §4.8 已有，行为不变） ────────────

    /** 绑定 / 换绑手机号：先用 scene=2 往新号码发码，再带码提交。 */
    @Put("/update-mobile")
    @FreshAuth
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.USER, message = "Too many attempts, please try again later")
    suspend fun updateMobile(identity: Identity, @Body request: UpdateMobileRequest) {
        accountAuthLogic.changeMobile(identity.id.toLong(), request.mobile, request.smsCode)
    }

    /** 绑定邮箱：先发码（登录态），再带码绑定。 */
    @Post("/send-email-code")
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = "Email code sending limit exceeded, please try again later")
    suspend fun sendEmailCode(identity: Identity, @Body request: SendEmailCodeRequest) {
        accountAuthLogic.sendEmailCode(request.email, "bind_email", identity.id.toLong())
    }

    /** 绑定 / 换绑邮箱：先发码到新邮箱，再带码提交。 */
    @Put("/bind-email")
    @FreshAuth
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.USER, message = "Too many attempts, please try again later")
    suspend fun bindEmail(identity: Identity, @Body request: BindEmailRequest) {
        accountAuthLogic.bindEmail(identity.id.toLong(), request.email, request.code)
    }

    @Put("/update-password")
    @FreshAuth
    suspend fun updatePassword(identity: Identity, @Body request: UpdatePasswordRequest) {
        val userId = identity.id.toLong()
        val member = accountLogic.get(userId)
            ?: throw NotFoundException("Account not found: $userId")

        // 已设过密码：必须带旧密码且正确；没设过（短信 / 社交注册）直接设置
        if (!member.password.isNullOrEmpty()) {
            if (request.oldPassword.isEmpty()) throw BadRequestException("请输入当前密码")
            val verification = PasswordHasher.verify(request.oldPassword, member.password)
            if (!verification.verified) {
                throw BadRequestException("当前密码不正确")
            }
        }

        // Hash new password and update
        accountLogic.update(member.copy(password = PasswordHasher.hash(request.newPassword)))
        identityAdapter?.onPasswordChanged(AccountPasswordChangedEvent(memberId = userId))
    }

    @Put("/reset-password")
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = "Too many attempts, please try again later")
    suspend fun resetPassword(@Body request: ResetPasswordBySmsRequest) {
        accountAuthLogic.resetPasswordBySms(request.mobile, request.smsCode, request.newPassword)
    }

    /**
     * 认证场景发送验证码：修改手机（scene=2）、修改密码（scene=3）。
     *
     * - 修改手机：[mobile] 为新手机号，校验未被其他用户占用。
     * - 修改密码：[mobile] 可省略，从当前登录用户的手机号自动获取。
     */
    @Post("/send-sms-code")
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = "SMS code sending limit exceeded, please try again later")
    suspend fun sendSmsCode(identity: Identity, @Body request: AuthedSendSmsCodeRequest) {
        val scene = try {
            SmsScene.fromScene(request.scene)
        } catch (_: IllegalArgumentException) {
            throw BadRequestException("Invalid scene: ${request.scene}")
        }
        if (scene !in setOf(SmsScene.UPDATE_MOBILE, SmsScene.UPDATE_PASSWORD)) {
            throw BadRequestException("Scene ${request.scene} is not allowed here, use /auth/send-sms-code for login/reset-password")
        }

        val userId = identity.id.toLong()

        val mobile = when (scene) {
            SmsScene.UPDATE_PASSWORD -> {
                // 修改密码：手机号来自登录用户自己的档案，忽略请求中的 mobile
                accountLogic.get(userId)?.mobile
                    ?: throw BadRequestException("No mobile bound to current account")
            }
            SmsScene.UPDATE_MOBILE -> {
                // 修改手机：手机号为新号，由请求提供（必须 E.164）
                request.mobile?.takeIf { it.matches(Regex(controller.app.auth.dto.E164_REGEX)) }
                    ?: throw BadRequestException(controller.app.auth.dto.E164_MESSAGE)
            }
            else -> throw BadRequestException("Unexpected scene: $scene")
        }

        accountAuthLogic.sendSmsCode(mobile, scene)
    }
}
