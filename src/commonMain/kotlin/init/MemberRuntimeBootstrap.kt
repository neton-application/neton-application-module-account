package init

import neton.core.component.NetonContext
import neton.logging.LoggerFactory
import neton.redis.RedisClient
import logic.MessageSendLogic
import logic.SocialUserLogic
import port.MemberIdentityAdapter
import impl.BuiltinMemberIdentityAdapter
import neton.security.jwt.JwtAuthenticator

import logic.*

// MANIFEST-P3 + MEMBER-IDENTITY-ADAPTER(M1): 手写 runtime bootstrap。
// member 不再直接依赖 privchat —— 身份后端走 MemberIdentityAdapter:
//   默认 bindIfAbsent(BuiltinMemberIdentityAdapter)(内置,M1 下 auth unsupported);
//   privchat 模式由 module-privchat 的 PrivchatMemberIdentityAdapter override(M2)。
object MemberRuntimeBootstrap {
    fun initialize(ctx: NetonContext) {
        val loggerFactory = ctx.get(LoggerFactory::class)
        val appFileLogic = ctx.get(logic.FileUploadLogic::class)

        // 身份后端 adapter:默认内置(自托管账号,本地建号+复用应用 JwtAuthenticator 签 token);
        // privchat 模式下 module-privchat 已 bind override → bindIfAbsent 保留它。
        val jwt = ctx.get(JwtAuthenticator::class)
        val db = ctx.get(neton.database.api.DbContext::class)
        ctx.bindIfAbsent(MemberIdentityAdapter::class,
            BuiltinMemberIdentityAdapter(jwt = jwt, db = db))
        val identityAdapter = ctx.get(MemberIdentityAdapter::class)

        // 跨模块依赖（nullable: 对应模块未装配时降级）
        val redis = ctx.getOrNull(RedisClient::class)
        val messageSendLogic = ctx.getOrNull(MessageSendLogic::class)
        val socialUserLogic = ctx.getOrNull(SocialUserLogic::class)

        // R8.4a 顺序: MemberLogic (@Logic 已 bind) → inviteLogic → RequiredActionsLogic → MemberAuthLogic。
        val memberLogic = ctx.get(MemberLogic::class)
        // 邀请码(MEMBER_INVITE_CODE):自动加好友端口可空(builtin=仅记录来源);
        // 注册策略由产品装配层 bind(privchat 从 conf 读),未装配用默认(仅 PHONE_SMS)。
        val authPolicy = ctx.getOrNull(port.MemberAuthPolicy::class) ?: port.MemberAuthPolicy()
        val inviteLogic = MemberInviteLogic(
            log = loggerFactory.get("logic.member-invite"),
            db = ctx.get(neton.database.api.DbContext::class),
            invitePort = ctx.getOrNull(port.MemberInvitePort::class),
            rewardRegistry = ctx.getOrNull(port.MemberInviteRewardRegistry::class),
        )
        ctx.bind(MemberInviteLogic::class, inviteLogic)
        // inviteCodeRequired 走登录后 required-actions gate(bind_invite_code),不再在注册期拦截
        val requiredActionsLogic = RequiredActionsLogic(memberLogic, authPolicy, inviteLogic)
        ctx.bind(RequiredActionsLogic::class, requiredActionsLogic)
        val loginDeviceLogic = MemberLoginDeviceLogic(
            log = loggerFactory.get("logic.member-login-device"),
            db = db,
        )
        ctx.bind(MemberLoginDeviceLogic::class, loginDeviceLogic)
        // 游客开通：依赖 identityAdapter，所以不能用 @Logic（KSP 顺序是 logics 早于 bootstrap）
        // 把本模块的配置定义登记进注册表。各模块各自登记，装配层不必知道谁有哪些配置。
        ctx.getOrNull(setting.SettingDefinitionRegistry::class)?.register(setting.MemberSettingKeys.definitions)

        ctx.bind(MemberGuestLogic::class, MemberGuestLogic(
            log = loggerFactory.get("logic.member.guest"),
            db = ctx.get(neton.database.api.DbContext::class),
            identityAdapter = identityAdapter,
        ))
        ctx.bind(MemberAuthLogic::class, MemberAuthLogic(
            log = loggerFactory.get("logic.member-auth"),
            identityAdapter = identityAdapter,
            requiredActionsLogic = requiredActionsLogic,
            redis = redis,
            messageSendLogic = messageSendLogic,
            socialUserLogic = socialUserLogic,
            inviteLogic = inviteLogic,
            authPolicy = authPolicy,
            welcomePort = ctx.getOrNull(port.MemberWelcomePort::class),
            loginDeviceLogic = loginDeviceLogic,
        ))
        ctx.bind(MemberProfileLogic::class, MemberProfileLogic(
            log = loggerFactory.get("logic.member-profile"),
            memberLogic = memberLogic,
            appFileLogic = appFileLogic,
            identityAdapter = identityAdapter,
        ))

        // 签到：现金奖励发放端口（可空）。产品装配层（privchat @Module dependsOn=[] 先 init）
        // bind MemberRewardPort 后签到可发现金；未装配（builtin）= null，纯积分行为。
        ctx.bind(MemberSignInLogic::class, MemberSignInLogic(
            log = loggerFactory.get("logic.member-signin"),
            db = ctx.get(neton.database.api.DbContext::class),
            rewardPort = ctx.getOrNull(port.MemberRewardPort::class),
            // 全局设置读取（连续签到周期）。未装配 = 用代码默认值，签到照常工作，
            // 只是不能在后台调周期。
            configs = ctx.getOrNull(logic.SystemSettingLogic::class),
        ))
    }
}
