package controller.app.auth

import logic.AccountErrorCodes
import logic.AccountAuthLogic
import controller.app.auth.dto.AccountRegisterRequest
import controller.app.auth.dto.UsernameLoginRequest
import controller.app.auth.dto.E164_REGEX
import controller.app.auth.dto.AccountLoginRequest
import controller.app.auth.dto.AccountLoginResponse
import controller.admin.auth.dto.SocialLoginRequest
import controller.admin.auth.dto.SocialRedirectVO
import kotlinx.serialization.Serializable
import enums.SmsScene
import logic.CaptchaAction
import neton.core.annotations.*
import neton.core.http.BadRequestException
import neton.core.http.HttpRequest
import neton.core.http.ValidationError
import neton.validation.annotations.Min
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Pattern
import neton.validation.annotations.Size

@Serializable
data class AccountRefreshTokenRequest(
    @property:NotBlank
    @property:Size(min = 32, max = 4096)
    val refreshToken: String,
    /** 必须与登录时签发 token 的 device 一致；不一致 → BadRequest（防跨设备 refresh）。 */
    val deviceId: String? = null,
)

/** 匿名场景：登录、忘记密码 */
@Serializable
data class AccountSendSmsCodeRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = AccountErrorCodes.INVALID_MOBILE)
    val mobile: String,

    @property:Min(1)
    val scene: Int
)

/** 认证场景的合法 scene 值集合（修改手机、修改密码） */
private val AUTHED_SCENES = setOf(SmsScene.UPDATE_MOBILE, SmsScene.UPDATE_PASSWORD)
/** 匿名场景的合法 scene 值集合（登录、忘记密码） */
private val ANON_SCENES = setOf(SmsScene.LOGIN, SmsScene.RESET_PASSWORD)

@Controller("/auth")
class AuthController(
    private val accountAuthLogic: AccountAuthLogic,
    private val captcha: logic.CaptchaService,
) {

    @Post("/login")
    @AllowAnonymous
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = AccountErrorCodes.LOGIN_RATE_LIMITED)
    suspend fun login(@Body request: AccountLoginRequest, httpRequest: HttpRequest): AccountLoginResponse {
        httpRequest.requireCaptcha(CaptchaAction.LOGIN)
        return accountAuthLogic.login(request, httpRequest.clientIp())
    }

    /** 通用注册入口(MEMBER_INVITE_CODE §5.1):v1 = USERNAME_PASSWORD;PHONE_SMS 走 sms-login。 */
    @Post("/register")
    @AllowAnonymous
    @RateLimit(windowSeconds = 3600, maxRequests = 10, scope = RateLimitScope.IP, message = AccountErrorCodes.REGISTER_RATE_LIMITED)
    suspend fun register(@Body request: AccountRegisterRequest, httpRequest: HttpRequest): AccountLoginResponse {
        httpRequest.requireCaptcha(CaptchaAction.REGISTER)
        return accountAuthLogic.register(request, httpRequest.clientIp())
    }

    /** 账号密码登录(USERNAME_PASSWORD 注册的用户)。 */
    @Post("/login-username")
    @AllowAnonymous
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = AccountErrorCodes.LOGIN_RATE_LIMITED)
    suspend fun loginUsername(@Body request: UsernameLoginRequest, httpRequest: HttpRequest): AccountLoginResponse {
        httpRequest.requireCaptcha(CaptchaAction.LOGIN)
        return accountAuthLogic.usernameLogin(
            request.username,
            request.password,
            request.device,
            httpRequest.clientIp(),
        )
    }

    @Post("/sms-login")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = AccountErrorCodes.LOGIN_RATE_LIMITED)
    suspend fun smsLogin(@Body request: AccountLoginRequest, httpRequest: HttpRequest): AccountLoginResponse {
        return accountAuthLogic.smsLogin(request, httpRequest.clientIp())
    }

    @Post("/logout")
    suspend fun logout(identity: neton.core.interfaces.Identity) {
        accountAuthLogic.logout(
            identity.id.toLong(),
            sessionId = (identity as? security.RealmIdentity)?.sessionId,
            deviceId = (identity as? neton.security.jwt.UnifiedIdentity)?.deviceId,
        )
    }

    /**
     * 注销自己的账号（软删除）。App Store 审核指南 5.1.1(v) 要求 App 内可发起。
     *
     * 只能注销**自己**：用户 id 取自会话身份，不接受请求体传入——否则就成了
     * 「随便报一个 id 就能删别人账号」。
     */
    /** 全端退出：作废该账号在所有设备上的会话（含当前会话）。 */
    @Post("/logout-all")
    suspend fun logoutAll(identity: neton.core.interfaces.Identity) {
        accountAuthLogic.logoutAll(identity.id.toLong())
    }

    @Post("/delete-account")
    suspend fun deleteAccount(identity: neton.core.interfaces.Identity) {
        accountAuthLogic.deleteOwnAccount(identity.id.toLong())
    }

    @Post("/refresh-token")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 30, scope = RateLimitScope.IP, message = AccountErrorCodes.REFRESH_RATE_LIMITED)
    suspend fun refreshToken(@Body request: AccountRefreshTokenRequest): AccountLoginResponse {
        return accountAuthLogic.refreshToken(request.refreshToken, request.deviceId)
    }

    /**
     * 匿名发送验证码：仅允许登录（scene=1）和忘记密码（scene=4）场景。
     * 修改手机/修改密码需要认证，走 /member/user/send-sms-code 接口。
     */
    @Post("/send-sms-code")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = AccountErrorCodes.SMS_SEND_RATE_LIMITED)
    suspend fun sendSmsCode(@Body request: AccountSendSmsCodeRequest, httpRequest: HttpRequest) {
        val scene = try {
            SmsScene.fromScene(request.scene)
        } catch (_: IllegalArgumentException) {
            throw BadRequestException(AccountErrorCodes.INVALID_SCENE, listOf(ValidationError("scene", AccountErrorCodes.INVALID_SCENE, AccountErrorCodes.INVALID_SCENE)))
        }
        if (scene !in ANON_SCENES) {
            throw BadRequestException(AccountErrorCodes.SCENE_REQUIRES_AUTH, listOf(ValidationError("scene", AccountErrorCodes.SCENE_REQUIRES_AUTH, AccountErrorCodes.SCENE_REQUIRES_AUTH)))
        }
        httpRequest.requireCaptcha(CaptchaAction.SEND_CODE)
        accountAuthLogic.sendSmsCode(request.mobile, scene)
    }

    /** 邮箱验证码（匿名只开放找回密码场景）。 */
    @Post("/send-email-code")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = AccountErrorCodes.EMAIL_SEND_RATE_LIMITED)
    suspend fun sendEmailCode(@Body request: controller.app.account.dto.SendEmailCodeRequest, httpRequest: HttpRequest) {
        if (request.scene != "reset_password") throw BadRequestException(AccountErrorCodes.SCENE_REQUIRES_AUTH, listOf(ValidationError("scene", AccountErrorCodes.SCENE_REQUIRES_AUTH, AccountErrorCodes.SCENE_REQUIRES_AUTH)))
        httpRequest.requireCaptcha(CaptchaAction.SEND_CODE)
        accountAuthLogic.sendEmailCode(request.email, "reset_password")
    }

    @Put("/reset-password-by-email")
    @AllowAnonymous
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = AccountErrorCodes.ATTEMPTS_RATE_LIMITED)
    suspend fun resetPasswordByEmail(@Body request: controller.app.account.dto.ResetPasswordByEmailRequest) {
        accountAuthLogic.resetPasswordByEmail(request.email, request.code, request.newPassword)
    }

    @Get("/social-auth-redirect")
    @AllowAnonymous
    suspend fun socialAuthRedirect(@Query type: String, @Query redirectUri: String? = null): SocialRedirectVO {
        val url = accountAuthLogic.socialAuthRedirect(type, redirectUri ?: "")
        return SocialRedirectVO(url = url)
    }

    @Post("/social-login")
    @AllowAnonymous
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = AccountErrorCodes.SOCIAL_LOGIN_RATE_LIMITED)
    suspend fun socialLogin(@Body request: SocialLoginRequest, httpRequest: HttpRequest): AccountLoginResponse {
        return accountAuthLogic.socialLogin(
            socialType = request.socialType,
            code = request.code,
            redirectUri = request.redirectUri ?: "",
            device = request.device,
            remoteIp = httpRequest.clientIp(),
        )
    }

    /** 人机验证在凭据校验、发码之前：没过就不做密码哈希、不发短信邮件（spec auth-abuse-protection §6.1）。 */
    private suspend fun HttpRequest.requireCaptcha(action: CaptchaAction) =
        captcha.require(action, header(logic.CaptchaService.HEADER), clientIp())
}


/**
 * 认证审计与设备记录用的客户端地址：取框架解析后的 [HttpRequest.clientAddress]，与网络准入、
 * 限流看到的是同一个值（评审 R10）。是否信任代理转发头由 routing 的受信代理配置统一决定，
 * 这里不再自行解析，伪造的转发头改变不了结果。
 */
private fun HttpRequest.clientIp(): String? =
    clientAddress.trim().takeIf(String::isNotEmpty)?.withoutPort()

private fun String.withoutPort(): String = when {
    startsWith('[') -> substringAfter('[').substringBefore(']')
    count { it == ':' } == 1 -> substringBeforeLast(':')
    else -> this
}
