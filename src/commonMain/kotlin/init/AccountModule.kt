package init

import neton.core.annotations.Module

/**
 * account 模块声明锚点。
 *
 * 账号是很多业务的地基：身份、认证、资料、登录设备与会话、昵称词库、举报、注册后补全机制。
 * 它只依赖 system / infra，**不依赖任何业务模块**；业务要接入账号流程，走
 * [port.AccountExtensions] 登记的扩展点（见 member 的邀请码接入）。
 *
 * 身份后端经 [port.AccountIdentityAdapter] 抽象：默认内置账号，privchat 模式由
 * module-privchat override。runtime 装配见 [AccountRuntimeBootstrap]。
 */
@Module(dependsOn = ["system", "infra"], migrations = true)
object AccountModule
