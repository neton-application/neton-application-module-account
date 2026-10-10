package controller.admin.account.dto

import kotlinx.serialization.Serializable
import neton.validation.annotations.Min
import neton.validation.annotations.Size

/**
 * 管理员重设会员登录密码。明文只在服务端内存里存在到哈希那一步，绝不回传、不入日志。
 * 长度下限与注册链路一致（8 位，见 AccountAuthLogic 用户名注册校验）。
 */
@Serializable
data class UpdateAccountPasswordRequest(
    @property:Min(1)
    val id: Long,

    @property:Size(min = 8, max = 64)
    val password: String
)
