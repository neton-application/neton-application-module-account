package logic

import port.AccountIdentityAdapter
import port.CreateMemberAccountCommand
import port.IssueMemberTokenCommand
import port.RefreshMemberTokenCommand
import port.AccountTokenBundle
import controller.admin.auth.dto.LoginDeviceInfo
import controller.app.auth.dto.E164_MESSAGE
import controller.app.auth.dto.E164_REGEX
import controller.app.auth.dto.RequiredAction
import enums.SmsScene
import model.Account
import table.AccountTable
import controller.app.auth.dto.AccountLoginRequest
import controller.app.auth.dto.AccountLoginResponse
import neton.database.dsl.*

import neton.logging.Logger
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import neton.security.password.PasswordHasher
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

class AccountAuthLogic(
    private val log: Logger,
    private val db: neton.database.api.DbContext,
    private val identityAdapter: AccountIdentityAdapter,
    private val requiredActionsLogic: RequiredActionsLogic,
    private val verificationCodes: logic.VerificationCodeService? = null,
    private val socialUserLogic: SocialUserLogic? = null,
    private val authPolicy: port.AccountAuthPolicy = port.AccountAuthPolicy(),
    /** 其他模块登记的注册扩展（如 member 的邀请码绑定、欢迎语）。 */
    private val extensions: port.AccountExtensions = port.AccountExtensions(),
    private val loginDeviceLogic: AccountLoginDeviceLogic? = null,
    private val throttle: security.LoginThrottle = security.LoginThrottle(null),
) {

    /** 账号不存在时用它做一次等价的哈希校验，让失败耗时与真实账号一致。 */
    private val dummyHash: String by lazy { PasswordHasher.hash("timing-equalizer-not-a-password") }

    companion object {
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private const val DEFAULT_PLATFORM = "unknown"

        private val E164 = Regex(E164_REGEX)

        /** 账号身份域，验证码与登录冷却都按它隔离（与管理员的同号手机互不影响）。 */
        private const val REALM = "account"
    }

    /**
     * 复核并归一手机号：仅去首尾空格，**不**自动 prepend `+`、**不**做国家级深度校验。
     * 严格要求 E.164（`+` + 8-15 位数字）；非法 → BadRequest INVALID_PHONE_FORMAT。
     *
     * neton-validation 在多模块 `bindIfAbsent` 模式下后注册的 validator 会被静默忽略
     * （framework 层 bug），此处兜底确保不正确格式的手机号无法进入认证流程。
     */
    private fun requireE164(mobile: String): String {
        val trimmed = mobile.trim()
        if (!E164.matches(trimmed)) throw BadRequestException(E164_MESSAGE)
        return trimmed
    }

    private fun maskMobile(mobile: String): String {
        // E.164 形如 `+8615000000000`：保留 `+` 和国家码部分 + 末 4 位。
        return if (mobile.length < 8) "***" else "${mobile.take(4)}****${mobile.takeLast(4)}"
    }

    /**
     * 手机号 + 密码登录（评审 R08）：号码未注册、没设过密码、密码错误同一句提示，且都做一次等价的
     * 哈希校验，不让人借此判断某个号码是否注册；按号码计失败次数，连续失败后冷却。
     * 账号停用 / 注销的提示只在密码正确之后给出，不构成枚举。
     */
    suspend fun login(request: AccountLoginRequest, remoteIp: String? = null): AccountLoginResponse {
        val mobile = requireE164(request.mobile)
        throttle.ensureNotLocked(REALM, mobile)
        val found = AccountTable.oneWhere { Account::mobile eq mobile }
        val password = request.password ?: ""
        val passwordVerification = PasswordHasher.verify(password, found?.password ?: dummyHash)
        if (found?.password == null || !passwordVerification.verified) {
            throttle.recordFailure(REALM, mobile)
            throw BadRequestException("Invalid mobile or password")
        }
        throttle.reset(REALM, mobile)
        var member: Account = found
        if (passwordVerification.needsRehash) {
            member = member.copy(password = PasswordHasher.hash(password))
            AccountTable.update(member)
        }

        // 🔴 白名单判断：只有 NORMAL 放行。
        // 写成 `== DISABLED 就拒绝` 的话，新增状态（如 DELETED）会被默默放行。
        if (member.status != model.AccountStatus.NORMAL) {
            throw BadRequestException(
                if (member.status == model.AccountStatus.DELETED) "Account is deleted"
                else "Account is disabled"
            )
        }

        recordSuccessfulLogin(member, remoteIp)

        log.info("account.login.success", mapOf("userId" to member.id, "mobile" to maskMobile(mobile)))
        return buildResponse(member.id, request.device, remoteIp)
    }

    suspend fun smsLogin(request: AccountLoginRequest, remoteIp: String? = null): AccountLoginResponse {
        val mobile = requireE164(request.mobile)
        val smsCode = request.smsCode ?: throw BadRequestException("SMS code is required")
        verifySmsCode(mobile, SmsScene.LOGIN, smsCode)

        val member = AccountTable.oneWhere { Account::mobile eq mobile }
            ?: registerNewUser(
                mode = port.AccountAuthPolicy.MODE_PHONE_SMS,
                identifierMasked = maskMobile(mobile),
                inviteCode = request.inviteCode,
                buildMember = { registerViaAdapter(mobile, nickname = request.nickname, registerIp = remoteIp) },
            )

        // 🔴 白名单判断：只有 NORMAL 放行。
        // 写成 `== DISABLED 就拒绝` 的话，新增状态（如 DELETED）会被默默放行。
        if (member.status != model.AccountStatus.NORMAL) {
            throw BadRequestException(
                if (member.status == model.AccountStatus.DELETED) "Account is deleted"
                else "Account is disabled"
            )
        }

        recordSuccessfulLogin(member, remoteIp)

        log.info("account.sms.login.success", mapOf("userId" to member.id, "mobile" to maskMobile(mobile)))
        return buildResponse(member.id, request.device, remoteIp)
    }

    /**
     * 用户自己注销账号（软删除）。
     *
     * 🔴 **顺序是先撤销会话、后落状态，而且撤销失败就整体失败。**
     *
     * 会话是权威的那一半：application 的 `account_users.session_version` 和 privchat 的
     * `privchat_devices.session_version` 是两个库里的两份数据，IM 鉴权读的是后者。
     * 只把本库状态改成 DELETED，旧 token 照样能连 IM、照样能刷新出新 access token——
     * 那不是注销，只是把登录入口关上。
     *
     * 反过来，若先写状态再撤销、撤销失败时又"幂等地"提前返回，重试永远补不上那一步：
     * 状态已经是 DELETED，函数看一眼就结束了。所以这里**不按状态提前返回**——重复注销
     * 会把撤销再执行一遍（server 侧对已 Revoked 的设备是空操作），让没做成的那一步有机会补上。
     *
     * 软删除而非物理删除：记录保留，后台账号管理仍看得到，状态显示「已删除」。运营要能
     * 查到"这个人注销过"，而不是账号凭空消失。
     *
     * App Store 审核指南 5.1.1(v) 要求 App 内可发起账号删除，这是它的服务端落点。
     */
    suspend fun deleteOwnAccount(userId: Long) {
        // 0) 开关。客户端不显示入口只是展示层，这里才是真正关得掉的地方。
        if (!authPolicy.accountDeletionEnabled) {
            throw neton.core.http.HttpException(
                neton.core.http.NetonErrorCode.PERMISSION_DENIED,
                "Account deletion is not enabled",
            )
        }
        val member = AccountTable.get(userId)
            ?: throw NotFoundException("Account not found")

        // 1) 先撤销全部设备会话。失败就抛出去——宁可让客户端看到失败去重试，
        //    也不能返回"注销成功"而会话还活着。
        identityAdapter.revokeAllSessions(userId, reason = "account_deleted")

        // 2) 再落本库状态。条件更新而不是读后写：并发注销/并发登录不会互相覆盖。
        AccountTable.query { where { Account::id eq userId } }
            .update {
                set(Account::status, model.AccountStatus.DELETED)
                increment(Account::sessionVersion)
            }
        log.info("account.delete", mapOf("userId" to userId, "already" to (member.status == model.AccountStatus.DELETED)))
    }

    /** 退出当前会话：同一次登录签出的 access 与 refresh 一起失效（评审 R04）。 */
    suspend fun logout(userId: Long, sessionId: String?, deviceId: String?) {
        identityAdapter.logoutSession(userId, sessionId, deviceId)
        log.info("account.logout", mapOf("userId" to userId))
    }

    /**
     * 代理到 server `/api/service/auth/refresh`（spec §6.1）。token 验证、device_id 匹配、
     * session_version 校验全部由 server 完成；application 不再持有自签 refresh token。
     */
    suspend fun refreshToken(refreshToken: String, requestDeviceId: String?): AccountLoginResponse {
        val deviceId = requestDeviceId?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("device_id is required for refresh")
        val bundle = identityAdapter.refreshToken(
            RefreshMemberTokenCommand(refreshToken = refreshToken, deviceId = deviceId),
        )
        // 🔴 刷新也要看账号状态：server 校验的是 token 与设备会话，不知道 application
        // 这边把账号标成了什么。少了这一道，注销后只要设备会话撤销有任何遗漏，旧
        // refresh token 就能一直换出新的 access token——"注销"形同虚设。
        //
        // 放在签发之后：先让 server 认出这是谁（refresh token 里才有 uid），再按 uid 查状态。
        val refreshed = AccountTable.get(bundle.userId)
        if (refreshed != null && refreshed.status != model.AccountStatus.NORMAL) {
            throw neton.core.http.HttpException(
                neton.core.http.NetonErrorCode.USER_BANNED,
                if (refreshed.status == model.AccountStatus.DELETED) "ACCOUNT_DELETED" else "ACCOUNT_DISABLED",
            )
        }
        log.info("account.token.refreshed", mapOf("userId" to bundle.userId))
        // R8.4a：refresh 路径也带最新 required actions（用户可能在另一端完成
        // 了 onboarding，本端 refresh 后应该立刻拿到空数组）。
        val actions = requiredActionsLogic.computeForUid(bundle.userId)
        return bundle.toMemberLoginResponse(actions)
    }

    suspend fun sendSmsCode(rawMobile: String, scene: SmsScene) {
        val mobile = requireE164(rawMobile)
        // Scene-specific pre-validation
        when (scene) {
            SmsScene.UPDATE_MOBILE -> {
                // 修改手机：新手机号不能已被其他用户使用
                val existing = AccountTable.oneWhere { Account::mobile eq mobile }
                if (existing != null) {
                    throw BadRequestException("Mobile number is already in use")
                }
            }
            SmsScene.RESET_PASSWORD,
            SmsScene.UPDATE_PASSWORD -> {
                // 重置/修改密码：号码没注册就不发，但也不报错 —— 报错等于告诉任何人这个号码是不是用户。
                if (AccountTable.oneWhere { Account::mobile eq mobile } == null) {
                    log.info("account.sms_code.skip_unknown_mobile", mapOf("scene" to scene.purpose))
                    return
                }
            }
            SmsScene.LOGIN -> Unit // 登录：无需前置校验，允许自动注册
        }
        codes().issue(REALM, scene.purpose, logic.VerificationCodeService.Channel.SMS, mobile, scene.messageTemplate)
    }

    // ── 邮箱验证码：找回密码（匿名）与绑定邮箱（登录后）──

    fun normalizeEmail(raw: String): String {
        val e = raw.trim().lowercase()
        if (!EMAIL_REGEX.matches(e)) throw BadRequestException("INVALID_EMAIL")
        return e
    }

    /**
     * 找回密码：邮箱没绑过就**不发**也不报错（报错等于把「这个邮箱是不是我们的用户」告诉任何人）；
     * 绑定邮箱：邮箱已被别人占用要报错，否则用户填完验证码才发现绑不上。
     */
    suspend fun sendEmailCode(rawEmail: String, scene: String, userId: Long? = null) {
        val email = normalizeEmail(rawEmail)
        when (scene) {
            "reset_password" -> {
                if (AccountTable.oneWhere { Account::email eq email } == null) {
                    log.info("account.email_code.skip_unknown_email scene=$scene")
                    return
                }
            }
            "bind_email" -> {
                val existing = AccountTable.oneWhere { Account::email eq email }
                if (existing != null && existing.id != userId) throw BadRequestException("EMAIL_TAKEN")
            }
            else -> throw BadRequestException("INVALID_SCENE")
        }
        codes().issue(REALM, scene, logic.VerificationCodeService.Channel.EMAIL, email, "mail_verification_$scene")
    }

    suspend fun bindEmail(userId: Long, rawEmail: String, code: String) {
        val email = normalizeEmail(rawEmail)
        verifyEmailCode(email, "bind_email", code)
        val existing = AccountTable.oneWhere { Account::email eq email }
        if (existing != null && existing.id != userId) throw BadRequestException("EMAIL_TAKEN")
        val member = AccountTable.get(userId) ?: throw NotFoundException("Account not found: $userId")
        AccountTable.update(member.copy(email = email))
    }

    /**
     * 绑定或换绑手机号（没绑过就是绑定，绑过就是换绑）。
     *
     * 证明的是「新号码归本人」：验证码发到新号码上。旧号码不再验证 —— 换绑往往正是因为旧号
     * 已经收不到短信；本人身份由入口的 @FreshAuth（近期重新验证过身份）保证。
     */
    suspend fun changeMobile(userId: Long, rawMobile: String, code: String) {
        val mobile = requireE164(rawMobile)
        verifySmsCode(mobile, SmsScene.UPDATE_MOBILE, code)
        val account = AccountTable.get(userId) ?: throw NotFoundException("Account not found: $userId")
        if (account.mobile == mobile) return
        // 发码时查过一次占用，这里再查一次：两次之间号码可能被别人绑走。不查的话撞上唯一索引，
        // 用户看到的是一条数据库异常。
        val holder = AccountTable.oneWhere { Account::mobile eq mobile }
        if (holder != null && holder.id != userId) throw BadRequestException("Mobile number is already in use")
        AccountTable.update(account.copy(mobile = mobile))
        identityAdapter.onMobileChanged(
            port.AccountMobileChangedEvent(memberId = userId, newMobile = mobile, oldMobile = account.mobile),
        )
    }

    /**
     * 凭短信验证码重设密码。码可以是「修改密码」（scene=3，登录态下发到本人手机）或「忘记密码」
     * （scene=4）发出的，按哪一条还没用掉来选；两种用途以外的码（如登录码）一律不收。
     */
    suspend fun resetPasswordBySms(rawMobile: String, code: String, newPassword: String) {
        val mobile = requireE164(rawMobile)
        val scene = if (codes().hasPending(REALM, SmsScene.UPDATE_PASSWORD.purpose, logic.VerificationCodeService.Channel.SMS, mobile)) {
            SmsScene.UPDATE_PASSWORD
        } else {
            SmsScene.RESET_PASSWORD
        }
        verifySmsCode(mobile, scene, code)
        val account = AccountTable.oneWhere { Account::mobile eq mobile }
            ?: throw BadRequestException("SMS code expired or not sent")
        AccountTable.update(account.copy(password = PasswordHasher.hash(newPassword)))
        identityAdapter.onPasswordChanged(port.AccountPasswordChangedEvent(memberId = account.id))
    }

    suspend fun resetPasswordByEmail(rawEmail: String, code: String, newPassword: String) {
        val email = normalizeEmail(rawEmail)
        verifyEmailCode(email, "reset_password", code)
        val member = AccountTable.oneWhere { Account::email eq email } ?: throw BadRequestException("INVALID_EMAIL_CODE")
        // 会话版本由 onPasswordChanged 统一自增，这里不再另加一次。
        AccountTable.update(member.copy(password = PasswordHasher.hash(newPassword)))
        identityAdapter.onPasswordChanged(port.AccountPasswordChangedEvent(memberId = member.id))
    }


    // --- Social login ---

    suspend fun socialAuthRedirect(socialType: String, redirectUri: String): String {
        val social = socialUserLogic
            ?: throw BadRequestException("Social login not configured")
        return social.getAuthRedirectUrl(socialType, redirectUri)
    }

    suspend fun socialLogin(
        socialType: String,
        code: String,
        redirectUri: String,
        device: LoginDeviceInfo?,
        remoteIp: String? = null,
    ): AccountLoginResponse {
        val social = socialUserLogic
            ?: throw BadRequestException("Social login not configured")

        val socialUser = social.socialLogin(socialType, code, redirectUri, userType = 2)

        val member: Account
        if (socialUser.userId == 0L) {
            // 走 server 取 uid，再写镜像（spec UPSTREAM §5：所有 fork 注册分支必须先 server 分配 uid）
            val ref = identityAdapter.createOrBindAccount(
                CreateMemberAccountCommand(
                    username = socialUser.openId.takeIf { it.isNotEmpty() },
                    displayName = socialUser.nickname,
                    avatarUrl = socialUser.avatar,
                )
            )
            val newMember = Account(
                id = ref.memberId,
                identityProvider = ref.provider,
                // 社交昵称缺失时留空，不自动编造（空昵称由 complete_profile 引导补齐）。
                nickname = socialUser.nickname ?: "",
                avatar = socialUser.avatar,
                registerIp = remoteIp.normalizedIp(),
            )
            member = insertAccountWithProvidedId(db, newMember)
            social.bind(member.id, userType = 2, socialType, code, redirectUri)
            log.info("account.social.auto_register", mapOf("userId" to member.id, "socialType" to socialType))
        } else {
            member = AccountTable.get(socialUser.userId)
                ?: throw NotFoundException("Bound member not found")
        }

        // 🔴 白名单判断：只有 NORMAL 放行。
        // 写成 `== DISABLED 就拒绝` 的话，新增状态（如 DELETED）会被默默放行。
        if (member.status != model.AccountStatus.NORMAL) {
            throw BadRequestException(
                if (member.status == model.AccountStatus.DELETED) "Account is deleted"
                else "Account is disabled"
            )
        }

        recordSuccessfulLogin(member, remoteIp)

        log.info("account.social.login.success", mapOf("userId" to member.id, "socialType" to socialType))
        return buildResponse(member.id, device, remoteIp)
    }

    // --- Private helpers ---

    private fun codes(): logic.VerificationCodeService =
        verificationCodes ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.SERVICE_UNAVAILABLE, "VERIFICATION_UNAVAILABLE")

    /** 码按「账号域 + 用途 + 接收方」核验并消费，原子计错、猜错到上限作废（system 的 VerificationCodeService）。 */
    private suspend fun verifySmsCode(mobile: String, scene: SmsScene, code: String) {
        when (codes().verify(REALM, scene.purpose, logic.VerificationCodeService.Channel.SMS, mobile, code)) {
            logic.VerificationCodeService.Result.OK -> Unit
            logic.VerificationCodeService.Result.INVALID -> throw BadRequestException("Invalid SMS code")
            logic.VerificationCodeService.Result.LOCKED -> throw BadRequestException("Too many wrong attempts, please request a new code")
            logic.VerificationCodeService.Result.EXPIRED -> throw BadRequestException("SMS code expired or not sent")
        }
    }

    private suspend fun verifyEmailCode(email: String, purpose: String, code: String) {
        when (codes().verify(REALM, purpose, logic.VerificationCodeService.Channel.EMAIL, email, code)) {
            logic.VerificationCodeService.Result.OK -> Unit
            logic.VerificationCodeService.Result.LOCKED -> throw BadRequestException("Too many wrong attempts, please request a new code")
            else -> throw BadRequestException("INVALID_EMAIL_CODE")
        }
    }

    /**
     * SMS 自动注册：先看 server 是否已有该手机号，命中则复用 uid，否则 createUser。
     * 写本地镜像用 [insertAccountWithProvidedId]（caller-provided id）。
     *
     * 入口已要求 E.164（`E164_REGEX`），这里直接透传给 server。
     */
    /**
     * 统一注册收敛：所有注册方式共用同一条时序——
     *
     *   扩展校验（失败 = 注册失败）→ 建账号 → 扩展的注册后回调
     *
     * account 自己不认识邀请码：注册请求里的 `inviteCode` 原样透传给扩展（member 的邀请能力
     * 在那里校验、绑定、派发自动加好友与邀请奖励、发欢迎语）。没装任何扩展时，带码注册直接
     * 拒绝，而不是悄悄吞掉这个码 —— 用户以为自己被邀请了，其实什么都没发生。
     */
    private suspend fun registerNewUser(
        mode: String,
        identifierMasked: String?,
        inviteCode: String?,
        buildMember: suspend () -> Account,
    ): Account {
        val context = port.AccountRegistrationContext(
            mode = mode,
            identifierMasked = identifierMasked,
            inviteCode = inviteCode?.trim()?.takeIf { it.isNotEmpty() },
        )
        val hooks = extensions.registrationHooks()
        if (context.inviteCode != null && hooks.none { it.consumesInviteCode }) {
            throw BadRequestException("INVITE_CODE_UNSUPPORTED")
        }
        for (hook in hooks) hook.validate(context)
        val account = buildMember()
        for (hook in hooks) hook.afterRegistered(context, account)
        return account
    }

    /** USERNAME_PASSWORD 注册(MEMBER_INVITE_CODE §5.1)。 */
    suspend fun register(
        request: controller.app.auth.dto.AccountRegisterRequest,
        remoteIp: String? = null,
    ): AccountLoginResponse {
        if (request.mode != port.AccountAuthPolicy.MODE_USERNAME_PASSWORD) {
            throw BadRequestException("UNSUPPORTED_REGISTER_MODE")
        }
        if (port.AccountAuthPolicy.MODE_USERNAME_PASSWORD !in authPolicy.registerModes) {
            throw BadRequestException("REGISTER_MODE_DISABLED")
        }
        // username 规则 = AccountProfileLogic 同一真源(lowercase 归一 + 格式 + 保留字)
        val username = request.username.trim().lowercase()
        if (!AccountProfileLogic.USERNAME_REGEX.matches(username)) {
            throw BadRequestException("INVALID_USERNAME_FORMAT")
        }
        if (username in AccountProfileLogic.USERNAME_RESERVED) {
            throw BadRequestException("USERNAME_RESERVED")
        }
        if (request.password.length < 8) {
            throw BadRequestException("PASSWORD_TOO_SHORT")
        }
        // nickname 不在注册期强制:空昵称由 complete_profile required-action 引导补全
        if (AccountTable.oneWhere { Account::username eq username } != null) {
            throw BadRequestException("USERNAME_TAKEN")
        }
        val member = registerNewUser(
            mode = port.AccountAuthPolicy.MODE_USERNAME_PASSWORD,
            identifierMasked = maskUsername(username),
            inviteCode = request.inviteCode,
        ) {
            val ref = identityAdapter.createOrBindAccount(CreateMemberAccountCommand(username = username))
            insertAccountWithProvidedId(
                db,
                Account(
                    id = ref.memberId,
                    identityProvider = ref.provider,
                    username = username,
                    usernameUpdatedAt = Clock.System.now().toEpochMilliseconds(),
                    password = PasswordHasher.hash(request.password),
                    nickname = request.nickname?.trim().orEmpty(),
                    registerIp = remoteIp.normalizedIp(),
                ),
            ).also {
                log.info("account.register.username", mapOf("userId" to it.id, "username" to username))
            }
        }
        return buildResponse(member.id, request.device, remoteIp)
    }

    /** 账号密码登录(USERNAME_PASSWORD 注册的用户)。 */
    suspend fun usernameLogin(
        username: String,
        password: String,
        device: LoginDeviceInfo?,
        remoteIp: String? = null,
    ): AccountLoginResponse {
        val normalized = username.trim().lowercase()
        // 凭证错误是认证失败(401/10011),不是参数错误(400/10100);message 用机器码,
        // 客户端按码映射本地化文案(用户拍板:错误必须人话,不泄原始异常)。
        // 用户不存在 / 没设密码也做一次等价哈希校验，失败耗时不暴露账号是否存在；按账号冷却（评审 R08）。
        throttle.ensureNotLocked(REALM, normalized)
        val found = AccountTable.oneWhere { Account::username eq normalized }
        val verification = PasswordHasher.verify(password, found?.password ?: dummyHash)
        if (found?.password == null || !verification.verified) {
            throttle.recordFailure(REALM, normalized)
            throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_CREDENTIALS, "INVALID_CREDENTIALS")
        }
        throttle.reset(REALM, normalized)
        var member: Account = found
        if (verification.needsRehash) {
            member = member.copy(password = PasswordHasher.hash(password))
            AccountTable.update(member)
        }
        // 同上：白名单判断，新增状态不会漏。
        if (member.status != model.AccountStatus.NORMAL) {
            throw neton.core.http.HttpException(
                neton.core.http.NetonErrorCode.USER_BANNED,
                if (member.status == model.AccountStatus.DELETED) "ACCOUNT_DELETED" else "ACCOUNT_DISABLED",
            )
        }
        recordSuccessfulLogin(member, remoteIp)
        log.info("account.login.username.success", mapOf("userId" to member.id))
        return buildResponse(member.id, device, remoteIp)
    }

    private fun maskUsername(username: String): String =
        if (username.length <= 4) "${username.first()}***"
        else "${username.take(2)}***${username.takeLast(2)}"

    private suspend fun registerViaAdapter(
        mobile: String,
        nickname: String? = null,
        registerIp: String? = null,
    ): Account {
        val ref = identityAdapter.createOrBindAccount(CreateMemberAccountCommand(mobile = mobile))
        // 昵称:注册表单直填则落库(跳过 complete_profile);否则留空走首登引导。
        val newMember = Account(
            id = ref.memberId,
            identityProvider = ref.provider,
            mobile = mobile,
            nickname = nickname?.trim().orEmpty(),
            registerIp = registerIp.normalizedIp(),
        )
        if (!ref.created) {
            // 绑回了 server 既有账号但 platform 无 member 镜像：生产=手机号回收/换绑场景，
            // 测试环境=platform 与 IM 库不同步（半重置）。可观测告警，便于排查“新注册继承旧会话”。
            log.warn(
                "account.sms.rebind_existing_im_account",
                mapOf("userId" to ref.memberId, "mobile" to maskMobile(mobile))
            )
        }
        log.info(
            "account.sms.auto_register",
            mapOf("userId" to ref.memberId, "mobile" to maskMobile(mobile), "created" to ref.created)
        )
        return insertAccountWithProvidedId(db, newMember)
    }

    private suspend fun recordSuccessfulLogin(member: Account, remoteIp: String?) {
        AccountTable.update(
            member.copy(
                loginIp = remoteIp.normalizedIp(),
                loginDate = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    private fun String?.normalizedIp(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.take(64)

    /**
     * 给指定 uid + 设备直接颁发完整的登录返回（spec QR_API §5）。
     *
     * 用途：扫码登录确认时，application 已经通过 mobile 的 member token 拿到 scanner_uid，
     * 还需要给 **Web 端** 设备签发 token 推回 Web 的 unauth 连接。
     */
    suspend fun issueLoginResponseForUid(
        uid: Long,
        device: LoginDeviceInfo?,
    ): AccountLoginResponse = buildResponse(uid, device)

    /**
     * 调 server `/api/service/auth/issue` 颁发 unified token，把 [UnifiedLoginResponse] 映射成
     * 客户端可见的 [AccountLoginResponse]（spec §8）。
     */
    private suspend fun buildResponse(
        uid: Long,
        device: LoginDeviceInfo?,
        remoteIp: String? = null,
    ): AccountLoginResponse {
        // device 信息透传给 adapter;具体后端(privchat)再映射成它的设备/令牌请求。
        val bundle = identityAdapter.issueToken(
            IssueMemberTokenCommand(
                memberId = uid,
                deviceId = device?.deviceId?.takeIf { it.isNotBlank() },
                platform = device?.platform?.takeIf { it.isNotBlank() },
                deviceName = device?.deviceName?.takeIf { it.isNotBlank() },
                deviceModel = device?.deviceModel?.takeIf { it.isNotBlank() },
                osVersion = device?.osVersion?.takeIf { it.isNotBlank() },
                appVersion = device?.appVersion?.takeIf { it.isNotBlank() },
                // IP 必须来自服务端连接信息，不能相信客户端 JSON 自报的 ipAddress。
                ipAddress = remoteIp.normalizedIp(),
            ),
        )
        loginDeviceLogic?.record(uid, bundle.deviceId, device, remoteIp.normalizedIp())
        // R8.4a：注入 Post-login Required Actions。token 签发不感知它们;application 层计算后随响应下发。
        val actions = requiredActionsLogic.computeForUid(uid)
        return bundle.toMemberLoginResponse(actions)
    }

    private fun AccountTokenBundle.toMemberLoginResponse(
        requiredActions: List<RequiredAction>,
    ): AccountLoginResponse =
        AccountLoginResponse(
            userId = userId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresIn = expiresIn,
            refreshExpiresIn = refreshExpiresIn,
            deviceId = deviceId,
            sessionVersion = sessionVersion,
            deviceCreated = deviceCreated,
            scope = scope,
            issuer = issuer,
            requiredActions = requiredActions,
        )
}
