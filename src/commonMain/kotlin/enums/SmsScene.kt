package enums

/**
 * 账号短信验证码发送场景。
 *
 * 模板编码保留 `member_*` 前缀：它们是各部署里已经配好的短信模板 key，改了就发不出短信。
 *
 * scene 整数与前端约定，templateCode 对应短信模板在系统中的编码（由 MessageSendLogic 查表匹配）。
 */
enum class SmsScene(
    val scene: Int,
    val templateCode: String,
    val description: String,
    /** 验证码挑战的用途：码只能用于发它的那个用途（system 的 VerificationCodeService）。 */
    val purpose: String,
) {
    LOGIN(1, "member_login", "账号 - 手机号登录", "login"),
    UPDATE_MOBILE(2, "member_update_mobile", "账号 - 修改手机", "update_mobile"),
    UPDATE_PASSWORD(3, "member_update_password", "账号 - 修改密码", "update_password"),
    RESET_PASSWORD(4, "member_reset_password", "账号 - 忘记密码", "reset_password");

    /** 消息模板编码，沿用各部署里已经配好的模板名。 */
    val messageTemplate: String get() = "sms_verification_$templateCode"

    companion object {
        fun fromScene(scene: Int): SmsScene =
            entries.firstOrNull { it.scene == scene }
                ?: throw IllegalArgumentException("Unknown SMS scene: $scene")
    }
}
