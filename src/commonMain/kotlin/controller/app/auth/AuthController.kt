package controller.app.auth

import logic.MemberAuthLogic
import controller.app.auth.dto.MemberRegisterRequest
import controller.app.auth.dto.UsernameLoginRequest
import controller.app.auth.dto.E164_MESSAGE
import controller.app.auth.dto.E164_REGEX
import controller.app.auth.dto.MemberLoginRequest
import controller.app.auth.dto.MemberLoginResponse
import controller.admin.auth.dto.SocialLoginRequest
import controller.admin.auth.dto.SocialRedirectVO
import kotlinx.serialization.Serializable
import enums.SmsScene
import neton.core.annotations.*
import neton.core.http.BadRequestException
import neton.core.http.HttpRequest
import neton.validation.annotations.Min
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Pattern
import neton.validation.annotations.Size

@Serializable
data class ValidateSmsCodeRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = E164_MESSAGE)
    val mobile: String,

    @property:NotBlank
    @property:Size(min = 4, max = 8)
    val code: String
)

@Serializable
data class MemberRefreshTokenRequest(
    @property:NotBlank
    @property:Size(min = 32, max = 4096)
    val refreshToken: String,
    /** 必须与登录时签发 token 的 device 一致；不一致 → BadRequest（防跨设备 refresh）。 */
    val deviceId: String? = null,
)

/** 匿名场景：登录、忘记密码 */
@Serializable
data class MemberSendSmsCodeRequest(
    @property:NotBlank
    @property:Pattern(regex = E164_REGEX, message = E164_MESSAGE)
    val mobile: String,

    @property:Min(1)
    val scene: Int
)

/** 认证场景的合法 scene 值集合（修改手机、修改密码） */
private val AUTHED_SCENES = setOf(SmsScene.MEMBER_UPDATE_MOBILE, SmsScene.MEMBER_UPDATE_PASSWORD)
/** 匿名场景的合法 scene 值集合（登录、忘记密码） */
private val ANON_SCENES = setOf(SmsScene.MEMBER_LOGIN, SmsScene.MEMBER_RESET_PASSWORD)

@Serializable
data class ValidateSmsCodeResponse(
    val valid: Boolean
)

@Controller("/auth")
class AuthController(
    private val memberAuthLogic: MemberAuthLogic
) {

    @Post("/login")
    @AllowAnonymous
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = "Login attempts exceeded, please try again later")
    suspend fun login(@Body request: MemberLoginRequest, httpRequest: HttpRequest): MemberLoginResponse {
        return memberAuthLogic.login(request, httpRequest.clientIp())
    }

    /** 通用注册入口(MEMBER_INVITE_CODE §5.1):v1 = USERNAME_PASSWORD;PHONE_SMS 走 sms-login。 */
    @Post("/register")
    @AllowAnonymous
    suspend fun register(@Body request: MemberRegisterRequest, httpRequest: HttpRequest): MemberLoginResponse {
        return memberAuthLogic.register(request, httpRequest.clientIp())
    }

    /** 账号密码登录(USERNAME_PASSWORD 注册的用户)。 */
    @Post("/login-username")
    @AllowAnonymous
    suspend fun loginUsername(@Body request: UsernameLoginRequest, httpRequest: HttpRequest): MemberLoginResponse {
        return memberAuthLogic.usernameLogin(
            request.username,
            request.password,
            request.device,
            httpRequest.clientIp(),
        )
    }

    @Post("/sms-login")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = "SMS login attempts exceeded, please try again later")
    suspend fun smsLogin(@Body request: MemberLoginRequest, httpRequest: HttpRequest): MemberLoginResponse {
        return memberAuthLogic.smsLogin(request, httpRequest.clientIp())
    }

    @Post("/logout")
    suspend fun logout(identity: neton.core.interfaces.Identity) {
        memberAuthLogic.logout(identity.id.toLong())
    }

    /**
     * 注销自己的账号（软删除）。App Store 审核指南 5.1.1(v) 要求 App 内可发起。
     *
     * 只能注销**自己**：用户 id 取自会话身份，不接受请求体传入——否则就成了
     * 「随便报一个 id 就能删别人账号」。
     */
    @Post("/delete-account")
    suspend fun deleteAccount(identity: neton.core.interfaces.Identity) {
        memberAuthLogic.deleteOwnAccount(identity.id.toLong())
    }

    @Post("/refresh-token")
    @AllowAnonymous
    suspend fun refreshToken(@Body request: MemberRefreshTokenRequest): MemberLoginResponse {
        return memberAuthLogic.refreshToken(request.refreshToken, request.deviceId)
    }

    /**
     * 匿名发送验证码：仅允许登录（scene=1）和忘记密码（scene=4）场景。
     * 修改手机/修改密码需要认证，走 /member/user/send-sms-code 接口。
     */
    @Post("/send-sms-code")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = "SMS code sending limit exceeded, please try again later")
    suspend fun sendSmsCode(@Body request: MemberSendSmsCodeRequest) {
        val scene = try {
            SmsScene.fromScene(request.scene)
        } catch (_: IllegalArgumentException) {
            throw BadRequestException("Invalid scene: ${request.scene}")
        }
        if (scene !in ANON_SCENES) {
            throw BadRequestException("Scene ${request.scene} requires authentication, use /member/user/send-sms-code")
        }
        memberAuthLogic.sendSmsCode(request.mobile, scene)
    }

    /** 邮箱验证码（匿名只开放找回密码场景）。 */
    @Post("/send-email-code")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = "Email code sending limit exceeded, please try again later")
    suspend fun sendEmailCode(@Body request: controller.app.member.dto.SendEmailCodeRequest) {
        if (request.scene != "reset_password") throw BadRequestException("Scene ${request.scene} requires authentication, use /member/user/send-email-code")
        memberAuthLogic.sendEmailCode(request.email, "reset_password")
    }

    @Put("/reset-password-by-email")
    @AllowAnonymous
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = "Too many attempts, please try again later")
    suspend fun resetPasswordByEmail(@Body request: controller.app.member.dto.ResetPasswordByEmailRequest) {
        memberAuthLogic.resetPasswordByEmail(request.email, request.code, request.newPassword)
    }

    @Post("/validate-sms-code")
    @AllowAnonymous
    @RateLimit(windowSeconds = 60, maxRequests = 5, scope = RateLimitScope.IP, message = "Too many verification attempts, please try again later")
    suspend fun validateSmsCode(@Body request: ValidateSmsCodeRequest): ValidateSmsCodeResponse {
        val valid = memberAuthLogic.validateSmsCode(request.mobile, request.code)
        return ValidateSmsCodeResponse(valid = valid)
    }

    @Get("/social-auth-redirect")
    @AllowAnonymous
    suspend fun socialAuthRedirect(@Query type: String, @Query redirectUri: String? = null): SocialRedirectVO {
        val url = memberAuthLogic.socialAuthRedirect(type, redirectUri ?: "")
        return SocialRedirectVO(url = url)
    }

    @Post("/social-login")
    @AllowAnonymous
    @RateLimit(windowSeconds = 300, maxRequests = 10, scope = RateLimitScope.IP, message = "Social login attempts exceeded, please try again later")
    suspend fun socialLogin(@Body request: SocialLoginRequest, httpRequest: HttpRequest): MemberLoginResponse {
        return memberAuthLogic.socialLogin(
            socialType = request.socialType,
            code = request.code,
            redirectUri = request.redirectUri ?: "",
            device = request.device,
            remoteIp = httpRequest.clientIp(),
        )
    }
}

/**
 * 认证与设备审计只使用 TCP 对端地址。8080 当前可被客户端直连，信任 X-Real-IP 会允许
 * 任意客户端伪造登录来源。以后若只允许受信反向代理访问，应在网关层统一解析并覆盖来源。
 */
private fun HttpRequest.clientIp(): String? =
    peerAddress.trim().takeIf(String::isNotEmpty)?.withoutPort()

private fun String.withoutPort(): String = when {
    startsWith('[') -> substringAfter('[').substringBefore(']')
    count { it == ':' } == 1 -> substringBeforeLast(':')
    else -> this
}
