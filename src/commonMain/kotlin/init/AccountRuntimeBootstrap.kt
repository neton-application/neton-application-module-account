package init

import neton.core.component.NetonContext
import neton.logging.LoggerFactory
import neton.redis.RedisClient
import logic.SocialUserLogic
import port.AccountIdentityAdapter
import impl.BuiltinAccountIdentityAdapter
import neton.security.jwt.JwtAuthenticator

import logic.*

// MANIFEST-P3 + MEMBER-IDENTITY-ADAPTER(M1): 手写 runtime bootstrap。
// member 不再直接依赖 privchat —— 身份后端走 AccountIdentityAdapter:
//   默认 bindIfAbsent(BuiltinAccountIdentityAdapter)(内置,M1 下 auth unsupported);
//   privchat 模式由 module-privchat 的 PrivchatMemberIdentityAdapter override(M2)。
object AccountRuntimeBootstrap {
    fun initialize(ctx: NetonContext) {
        val loggerFactory = ctx.get(LoggerFactory::class)
        val appFileLogic = ctx.get(logic.FileUploadLogic::class)

        // 身份后端 adapter:默认内置(自托管账号,本地建号+复用应用 JwtAuthenticator 签 token);
        // privchat 模式下 module-privchat 已 bind override → bindIfAbsent 保留它。
        val jwt = ctx.get(JwtAuthenticator::class)
        val db = ctx.get(neton.database.api.DbContext::class)
        ctx.bindIfAbsent(AccountIdentityAdapter::class,
            BuiltinAccountIdentityAdapter(jwt = jwt, db = db, revocation = ctx.get(security.SessionRevocation::class)))
        val identityAdapter = ctx.get(AccountIdentityAdapter::class)
        // 账号域登记：/app、/manage 只接受 realm=account 的令牌（或外部统一令牌）。
        // 登记表由 system 的安全装配创建；没有它说明 system 未装配，鉴权本身就不存在。
        ctx.get(security.TokenRealmRegistry::class).register(security.AccountTokenRealm(adapter = { identityAdapter }))

        // 跨模块依赖（nullable: 对应模块未装配时降级）
        val redis = ctx.getOrNull(RedisClient::class)
        val socialUserLogic = ctx.getOrNull(SocialUserLogic::class)

        val accountLogic = ctx.get(AccountLogic::class)
        // 注册策略由产品装配层 bind（privchat 从 conf 读），未装配用默认（仅 PHONE_SMS）。
        val authPolicy = ctx.getOrNull(port.AccountAuthPolicy::class) ?: port.AccountAuthPolicy()

        // 扩展点：依赖 account 的模块（拓扑序更晚）在各自 bootstrap 里往这里登记，
        // 读取发生在请求期，所以晚登记没有关系。account 自己不认识任何扩展。
        val extensions = port.AccountExtensions()
        ctx.bind(port.AccountExtensions::class, extensions)

        val requiredActionsLogic = RequiredActionsLogic(accountLogic, authPolicy, extensions)
        ctx.bind(RequiredActionsLogic::class, requiredActionsLogic)
        val loginDeviceLogic = AccountLoginDeviceLogic(
            log = loggerFactory.get("logic.account-login-device"),
            db = db,
        )
        ctx.bind(AccountLoginDeviceLogic::class, loginDeviceLogic)
        // 游客开通：依赖 identityAdapter，所以不能用 @Logic（KSP 顺序是 logics 早于 bootstrap）
        // 把本模块的配置定义登记进注册表。各模块各自登记，装配层不必知道谁有哪些配置。
        ctx.getOrNull(setting.SettingDefinitionRegistry::class)?.register(setting.AccountSettingKeys.definitions)

        ctx.bind(AccountGuestLogic::class, AccountGuestLogic(
            log = loggerFactory.get("logic.account.guest"),
            db = ctx.get(neton.database.api.DbContext::class),
            identityAdapter = identityAdapter,
        ))
        ctx.bind(AccountAuthLogic::class, AccountAuthLogic(
            log = loggerFactory.get("logic.account-auth"),
            db = ctx.get(neton.database.api.DbContext::class),
            identityAdapter = identityAdapter,
            requiredActionsLogic = requiredActionsLogic,
            verificationCodes = ctx.getOrNull(logic.VerificationCodeService::class),
            throttle = security.LoginThrottle(redis),
            socialUserLogic = socialUserLogic,
            authPolicy = authPolicy,
            extensions = extensions,
            loginDeviceLogic = loginDeviceLogic,
        ))
        ctx.bind(AccountProfileLogic::class, AccountProfileLogic(
            log = loggerFactory.get("logic.account-profile"),
            accountLogic = accountLogic,
            appFileLogic = appFileLogic,
            identityAdapter = identityAdapter,
        ))
    }
}
