package logic

/**
 * account 模块的业务错误机器码（spec `application/account-errors.md`）。
 *
 * 规则：异常的 `message` 只放这里的机器码（`^[A-Z][A-Z0-9_]{2,}$`），客户端 / 后台按码翻译
 * （后台语言包 `error.<CODE>`）。数字 `code` 取 `NetonErrorCode` 的类别。跟请求字段有关的问题
 * 另带字段级错误 `ValidationError(<字段>, CODE, CODE)`；只对排障有用的细节写日志，不进响应。
 *
 * 新增码：先加在这里，再补 spec 与 neton-application-front-account 两种语言的 `error.<CODE>`。
 */
object AccountErrorCodes {
    // ---- 登录与账号状态 ----
    /** 手机号 / 用户名不存在、没设过密码、密码错、当前密码错：同一个码，不暴露账号是否存在。 */
    const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
    const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
    const val ACCOUNT_DELETED = "ACCOUNT_DELETED"
    const val ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
    /** 部署关闭了 App 内自助注销。 */
    const val ACCOUNT_DELETION_DISABLED = "ACCOUNT_DELETION_DISABLED"

    // ---- 令牌 ----
    const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
    const val NOT_A_REFRESH_TOKEN = "NOT_A_REFRESH_TOKEN"
    const val INVALID_REFRESH_TOKEN_SUBJECT = "INVALID_REFRESH_TOKEN_SUBJECT"
    const val REFRESH_TOKEN_EXPIRED = "REFRESH_TOKEN_EXPIRED"
    const val REFRESH_TOKEN_REVOKED = "REFRESH_TOKEN_REVOKED"
    /** 刷新令牌必须带签发时的 deviceId。 */
    const val DEVICE_ID_REQUIRED = "DEVICE_ID_REQUIRED"

    // ---- 手机号 ----
    /** 手机号格式不对（用户身份手机号要求 E.164，如 `+8615000000000`）或为空。 */
    const val INVALID_MOBILE = "INVALID_MOBILE"
    /** 换绑 / 发换绑验证码：号码已属于另一个账号。 */
    const val MOBILE_IN_USE = "MOBILE_IN_USE"
    /** 注册流程免验证绑定：当前账号已绑过手机号（改号走换绑）。 */
    const val MOBILE_ALREADY_BOUND = "MOBILE_ALREADY_BOUND"
    /** 注册流程免验证绑定：号码已属于另一个账号。 */
    const val MOBILE_ALREADY_TAKEN = "MOBILE_ALREADY_TAKEN"
    /** 修改密码发码：当前账号没有绑定手机号。 */
    const val MOBILE_NOT_BOUND = "MOBILE_NOT_BOUND"

    // ---- 短信验证码 ----
    const val SMS_CODE_REQUIRED = "SMS_CODE_REQUIRED"
    const val SMS_CODE_INVALID = "SMS_CODE_INVALID"
    /** 码过期、从未发过，或（找回密码时）号码没注册 —— 后两者故意不区分。 */
    const val SMS_CODE_EXPIRED = "SMS_CODE_EXPIRED"
    /** 猜错次数到上限，这条码已作废，需重新获取。 */
    const val SMS_CODE_ATTEMPTS_EXCEEDED = "SMS_CODE_ATTEMPTS_EXCEEDED"
    /** 验证码场景值不认识。 */
    const val INVALID_SCENE = "INVALID_SCENE"
    /** 匿名发码接口收到了需要登录的场景（修改手机 / 修改密码 / 绑定邮箱）。 */
    const val SCENE_REQUIRES_AUTH = "SCENE_REQUIRES_AUTH"
    /** 登录态发码接口收到了匿名场景（登录 / 忘记密码）。 */
    const val SCENE_NOT_ALLOWED = "SCENE_NOT_ALLOWED"
    /** 验证码服务未装配（没有 Redis）。 */
    const val VERIFICATION_UNAVAILABLE = "VERIFICATION_UNAVAILABLE"

    // ---- 邮箱 ----
    const val INVALID_EMAIL = "INVALID_EMAIL"
    const val EMAIL_TAKEN = "EMAIL_TAKEN"
    /** 邮箱验证码错、过期或未发（找回密码时邮箱未绑定也是它）。 */
    const val INVALID_EMAIL_CODE = "INVALID_EMAIL_CODE"
    const val EMAIL_CODE_ATTEMPTS_EXCEEDED = "EMAIL_CODE_ATTEMPTS_EXCEEDED"

    // ---- 密码 ----
    const val PASSWORD_TOO_SHORT = "PASSWORD_TOO_SHORT"
    const val PASSWORD_TOO_LONG = "PASSWORD_TOO_LONG"
    /** 已设过密码的账号修改密码时没填当前密码。 */
    const val OLD_PASSWORD_REQUIRED = "OLD_PASSWORD_REQUIRED"

    // ---- 注册 / 社交登录 ----
    const val INVITE_CODE_UNSUPPORTED = "INVITE_CODE_UNSUPPORTED"
    const val UNSUPPORTED_REGISTER_MODE = "UNSUPPORTED_REGISTER_MODE"
    const val REGISTER_MODE_DISABLED = "REGISTER_MODE_DISABLED"
    const val SOCIAL_LOGIN_UNAVAILABLE = "SOCIAL_LOGIN_UNAVAILABLE"

    // ---- 资料 ----
    const val INVALID_NICKNAME_LENGTH = "INVALID_NICKNAME_LENGTH"
    const val INVALID_AVATAR_FILE_ID = "INVALID_AVATAR_FILE_ID"
    /** 文件不存在或不属于本人（不区分，避免暴露 fileId 存在性）。 */
    const val AVATAR_FILE_NOT_FOUND = "AVATAR_FILE_NOT_FOUND"
    const val AVATAR_FILE_INACTIVE = "AVATAR_FILE_INACTIVE"
    const val INVALID_AVATAR_BUSINESS_TYPE = "INVALID_AVATAR_BUSINESS_TYPE"
    const val AVATAR_FILE_NO_URL = "AVATAR_FILE_NO_URL"
    const val INVALID_USERNAME_FORMAT = "INVALID_USERNAME_FORMAT"
    const val USERNAME_RESERVED = "USERNAME_RESERVED"
    /** 30 天内只能改一次用户名；剩余秒数在 `Retry-After` 响应头。 */
    const val USERNAME_RATE_LIMITED = "USERNAME_RATE_LIMITED"
    const val USERNAME_TAKEN = "USERNAME_TAKEN"
    const val INVALID_BIO_LENGTH = "INVALID_BIO_LENGTH"
    const val INVALID_GENDER = "INVALID_GENDER"
    const val INVALID_BIRTHDAY_FORMAT = "INVALID_BIRTHDAY_FORMAT"

    // ---- 举报 ----
    const val INVALID_REPORT_TARGET_TYPE = "INVALID_REPORT_TARGET_TYPE"
    const val REPORT_TARGET_ID_REQUIRED = "REPORT_TARGET_ID_REQUIRED"
    const val INVALID_REPORT_REASON = "INVALID_REPORT_REASON"
    const val REPORT_DESCRIPTION_TOO_LONG = "REPORT_DESCRIPTION_TOO_LONG"

    // ---- 昵称词库（后台） ----
    const val NICKNAME_WORD_NOT_FOUND = "NICKNAME_WORD_NOT_FOUND"
    const val INVALID_NICKNAME_WORD_STATUS = "INVALID_NICKNAME_WORD_STATUS"

    // ---- 账号偏好 ----
    const val INVALID_PREFERENCE_NAMESPACE = "INVALID_PREFERENCE_NAMESPACE"
    const val PREFERENCE_VALUE_REQUIRED = "PREFERENCE_VALUE_REQUIRED"
    const val INVALID_PREFERENCE_VERSION = "INVALID_PREFERENCE_VERSION"
    const val PREFERENCE_VALUE_TOO_LARGE = "PREFERENCE_VALUE_TOO_LARGE"
    const val PREFERENCE_NAMESPACE_LIMIT_EXCEEDED = "PREFERENCE_NAMESPACE_LIMIT_EXCEEDED"
    const val PREFERENCE_VERSION_CONFLICT = "PREFERENCE_VERSION_CONFLICT"
    /** 库里存的值解析不了（数据损坏），记日志排查。 */
    const val PREFERENCE_CORRUPT = "PREFERENCE_CORRUPT"

    // ---- 入口限流（@RateLimit 的 message） ----
    /** 手机号密码 / 用户名密码 / 短信登录。 */
    const val LOGIN_RATE_LIMITED = "LOGIN_RATE_LIMITED"
    const val REGISTER_RATE_LIMITED = "REGISTER_RATE_LIMITED"
    const val REFRESH_RATE_LIMITED = "REFRESH_RATE_LIMITED"
    const val SMS_SEND_RATE_LIMITED = "SMS_SEND_RATE_LIMITED"
    const val EMAIL_SEND_RATE_LIMITED = "EMAIL_SEND_RATE_LIMITED"
    const val SOCIAL_LOGIN_RATE_LIMITED = "SOCIAL_LOGIN_RATE_LIMITED"
    const val PREFERENCE_WRITE_RATE_LIMITED = "PREFERENCE_WRITE_RATE_LIMITED"
    /** 换绑手机、绑定邮箱、找回密码（短信 / 邮箱）提交次数过多。 */
    const val ATTEMPTS_RATE_LIMITED = "ATTEMPTS_RATE_LIMITED"
}
