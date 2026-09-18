package logic

import port.MemberIdentityAdapter
import port.CreateMemberAccountCommand
import port.IssueMemberTokenCommand
import port.RefreshMemberTokenCommand
import port.MemberTokenBundle
import controller.admin.auth.dto.LoginDeviceInfo
import controller.app.auth.dto.E164_MESSAGE
import controller.app.auth.dto.E164_REGEX
import controller.app.auth.dto.RequiredAction
import enums.SmsScene
import model.Member
import table.MemberTable
import controller.app.auth.dto.MemberLoginRequest
import controller.app.auth.dto.MemberLoginResponse
import neton.database.dsl.*

import neton.logging.Logger
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import neton.redis.RedisClient
import neton.security.password.PasswordHasher
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock

class MemberAuthLogic(
    private val log: Logger,
    private val db: neton.database.api.DbContext,
    private val identityAdapter: MemberIdentityAdapter,
    private val requiredActionsLogic: RequiredActionsLogic,
    private val redis: RedisClient? = null,
    private val messageSendLogic: MessageSendLogic? = null,
    private val socialUserLogic: SocialUserLogic? = null,
    private val inviteLogic: MemberInviteLogic? = null,
    private val authPolicy: port.MemberAuthPolicy = port.MemberAuthPolicy(),
    private val welcomePort: port.MemberWelcomePort? = null,
    private val loginDeviceLogic: MemberLoginDeviceLogic? = null,
) {

    companion object {
        private const val SMS_CODE_PREFIX = "sms:code:"
        private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        private const val SMS_CODE_TTL_SECONDS = 300L  // 5 minutes
        private const val DEFAULT_PLATFORM = "unknown"
        // logout 黑名单 TTL：与 server unified token 默认 access TTL 对齐（1h）
        private const val LOGOUT_BLACKLIST_TTL_SECONDS = 3600L

        private val E164 = Regex(E164_REGEX)
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

    suspend fun login(request: MemberLoginRequest, remoteIp: String? = null): MemberLoginResponse {
        val mobile = requireE164(request.mobile)
        var member = MemberTable.oneWhere {
            Member::mobile eq mobile
        } ?: throw BadRequestException("Invalid mobile or password")

        if (member.password == null) {
            throw BadRequestException("Password not set for this account")
        }

        val password = request.password ?: ""
        val storedPassword = member.password
        val passwordVerification = PasswordHasher.verify(password, storedPassword)
        if (!passwordVerification.verified) {
            throw BadRequestException("Invalid mobile or password")
        }
        if (passwordVerification.needsRehash) {
            member = member.copy(password = PasswordHasher.hash(password))
            MemberTable.update(member)
        }

        // 🔴 白名单判断：只有 NORMAL 放行。
        // 写成 `== DISABLED 就拒绝` 的话，新增状态（如 DELETED）会被默默放行。
        if (member.status != model.MemberStatus.NORMAL) {
            throw BadRequestException(
                if (member.status == model.MemberStatus.DELETED) "Account is deleted"
                else "Account is disabled"
            )
        }

        recordSuccessfulLogin(member, remoteIp)

        log.info("member.login.success", mapOf("userId" to member.id, "mobile" to maskMobile(mobile)))
        return buildResponse(member.id, request.device, remoteIp)
    }

    suspend fun smsLogin(request: MemberLoginRequest, remoteIp: String? = null): MemberLoginResponse {
        val mobile = requireE164(request.mobile)
        val smsCode = request.smsCode ?: throw BadRequestException("SMS code is required")
        verifySmsCode(mobile, smsCode)

        val member = MemberTable.oneWhere { Member::mobile eq mobile }
            ?: registerNewUser(
                mode = port.MemberAuthPolicy.MODE_PHONE_SMS,
                identifierMasked = maskMobile(mobile),
                inviteCode = request.inviteCode,
                buildMember = { registerViaAdapter(mobile, nickname = request.nickname, registerIp = remoteIp) },
            )

        // 🔴 白名单判断：只有 NORMAL 放行。
        // 写成 `== DISABLED 就拒绝` 的话，新增状态（如 DELETED）会被默默放行。
        if (member.status != model.MemberStatus.NORMAL) {
            throw BadRequestException(
                if (member.status == model.MemberStatus.DELETED) "Account is deleted"
                else "Account is disabled"
            )
        }

        recordSuccessfulLogin(member, remoteIp)

        log.info("member.sms.login.success", mapOf("userId" to member.id, "mobile" to maskMobile(mobile)))
        return buildResponse(member.id, request.device, remoteIp)
    }

    /**
     * 用户自己注销账号（软删除）。
     *
     * 🔴 **顺序是先撤销会话、后落状态，而且撤销失败就整体失败。**
     *
     * 会话是权威的那一半：application 的 `member_users.session_version` 和 privchat 的
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
        val member = MemberTable.get(userId)
            ?: throw NotFoundException("Member not found")

        // 1) 先撤销全部设备会话。失败就抛出去——宁可让客户端看到失败去重试，
        //    也不能返回"注销成功"而会话还活着。
        identityAdapter.revokeAllSessions(userId, reason = "account_deleted")

        // 2) 再落本库状态。条件更新而不是读后写：并发注销/并发登录不会互相覆盖。
        MemberTable.query { where { Member::id eq userId } }
            .update {
                set(Member::status, model.MemberStatus.DELETED)
                increment(Member::sessionVersion)
            }
        log.info("member.delete", mapOf("userId" to userId, "already" to (member.status == model.MemberStatus.DELETED)))
    }

    suspend fun logout(userId: Long) {
        // Blacklist the user's tokens
        redis?.set("auth:member:blacklist:$userId", "1", ttl = LOGOUT_BLACKLIST_TTL_SECONDS.seconds)
        log.info("member.logout", mapOf("userId" to userId))
    }

    /**
     * 代理到 server `/api/service/auth/refresh`（spec §6.1）。token 验证、device_id 匹配、
     * session_version 校验全部由 server 完成；application 不再持有自签 refresh token。
     */
    suspend fun refreshToken(refreshToken: String, requestDeviceId: String?): MemberLoginResponse {
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
        val refreshed = MemberTable.get(bundle.userId)
        if (refreshed != null && refreshed.status != model.MemberStatus.NORMAL) {
            throw neton.core.http.HttpException(
                neton.core.http.NetonErrorCode.USER_BANNED,
                if (refreshed.status == model.MemberStatus.DELETED) "ACCOUNT_DELETED" else "ACCOUNT_DISABLED",
            )
        }
        log.info("member.token.refreshed", mapOf("userId" to bundle.userId))
        // R8.4a：refresh 路径也带最新 required actions（用户可能在另一端完成
        // 了 onboarding，本端 refresh 后应该立刻拿到空数组）。
        val actions = requiredActionsLogic.computeForUid(bundle.userId)
        return bundle.toMemberLoginResponse(actions)
    }

    suspend fun sendSmsCode(rawMobile: String, scene: SmsScene) {
        val mobile = requireE164(rawMobile)
        // Scene-specific pre-validation
        when (scene) {
            SmsScene.MEMBER_UPDATE_MOBILE -> {
                // 修改手机：新手机号不能已被其他用户使用
                val existing = MemberTable.oneWhere { Member::mobile eq mobile }
                if (existing != null) {
                    throw BadRequestException("Mobile number is already in use")
                }
            }
            SmsScene.MEMBER_RESET_PASSWORD,
            SmsScene.MEMBER_UPDATE_PASSWORD -> {
                // 重置/修改密码：手机号必须已注册
                MemberTable.oneWhere { Member::mobile eq mobile }
                    ?: throw BadRequestException("Mobile number is not registered")
            }
            SmsScene.MEMBER_LOGIN -> Unit // 登录：无需前置校验，允许自动注册
        }

        if (messageSendLogic != null) {
            messageSendLogic.sendVerificationCode(mobile, scene.templateCode)
        } else {
            // Fallback: store code directly in Redis when SMS channel is not configured.
            // Random.Default maps to arc4random on Native (cryptographically secure).
            val code = Random.Default.nextInt(100000, 1000000).toString()
            redis?.set("$SMS_CODE_PREFIX$mobile", code, ttl = SMS_CODE_TTL_SECONDS.seconds)
            log.info("member.sms.code.generated", mapOf("mobile" to maskMobile(mobile), "scene" to scene.name))
        }
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
        val sender = messageSendLogic ?: throw BadRequestException("EMAIL_CHANNEL_UNAVAILABLE")
        when (scene) {
            "reset_password" -> {
                if (MemberTable.oneWhere { Member::email eq email } == null) {
                    log.info("member.email_code.skip_unknown_email scene=$scene")
                    return
                }
            }
            "bind_email" -> {
                val existing = MemberTable.oneWhere { Member::email eq email }
                if (existing != null && existing.id != userId) throw BadRequestException("EMAIL_TAKEN")
            }
            else -> throw BadRequestException("INVALID_SCENE")
        }
        sender.sendEmailCode(email, scene)
    }

    suspend fun bindEmail(userId: Long, rawEmail: String, code: String) {
        val email = normalizeEmail(rawEmail)
        val sender = messageSendLogic ?: throw BadRequestException("EMAIL_CHANNEL_UNAVAILABLE")
        if (!sender.verifyEmailCode(email, code)) throw BadRequestException("INVALID_EMAIL_CODE")
        val existing = MemberTable.oneWhere { Member::email eq email }
        if (existing != null && existing.id != userId) throw BadRequestException("EMAIL_TAKEN")
        val member = MemberTable.get(userId) ?: throw NotFoundException("Member not found: $userId")
        MemberTable.update(member.copy(email = email))
    }

    suspend fun resetPasswordByEmail(rawEmail: String, code: String, newPassword: String) {
        val email = normalizeEmail(rawEmail)
        val sender = messageSendLogic ?: throw BadRequestException("EMAIL_CHANNEL_UNAVAILABLE")
        if (!sender.verifyEmailCode(email, code)) throw BadRequestException("INVALID_EMAIL_CODE")
        val member = MemberTable.oneWhere { Member::email eq email } ?: throw BadRequestException("INVALID_EMAIL_CODE")
        MemberTable.update(member.copy(password = PasswordHasher.hash(newPassword), sessionVersion = member.sessionVersion + 1))
        identityAdapter.onPasswordChanged(port.MemberPasswordChangedEvent(memberId = member.id))
    }

    suspend fun validateSmsCode(rawMobile: String, code: String): Boolean {
        return try {
            verifySmsCode(requireE164(rawMobile), code)
            true
        } catch (_: Exception) {
            false
        }
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
    ): MemberLoginResponse {
        val social = socialUserLogic
            ?: throw BadRequestException("Social login not configured")

        val socialUser = social.socialLogin(socialType, code, redirectUri, userType = 2)

        val member: Member
        if (socialUser.userId == 0L) {
            // 走 server 取 uid，再写镜像（spec UPSTREAM §5：所有 fork 注册分支必须先 server 分配 uid）
            val ref = identityAdapter.createOrBindAccount(
                CreateMemberAccountCommand(
                    username = socialUser.openId.takeIf { it.isNotEmpty() },
                    displayName = socialUser.nickname,
                    avatarUrl = socialUser.avatar,
                )
            )
            val newMember = Member(
                id = ref.memberId,
                identityProvider = ref.provider,
                // 社交昵称缺失时留空，不自动编造（空昵称由 complete_profile 引导补齐）。
                nickname = socialUser.nickname ?: "",
                avatar = socialUser.avatar,
                registerIp = remoteIp.normalizedIp(),
            )
            member = insertMemberWithProvidedId(db, newMember)
            social.bind(member.id, userType = 2, socialType, code, redirectUri)
            log.info("member.social.auto_register", mapOf("userId" to member.id, "socialType" to socialType))
        } else {
            member = MemberTable.get(socialUser.userId)
                ?: throw NotFoundException("Bound member not found")
        }

        // 🔴 白名单判断：只有 NORMAL 放行。
        // 写成 `== DISABLED 就拒绝` 的话，新增状态（如 DELETED）会被默默放行。
        if (member.status != model.MemberStatus.NORMAL) {
            throw BadRequestException(
                if (member.status == model.MemberStatus.DELETED) "Account is deleted"
                else "Account is disabled"
            )
        }

        recordSuccessfulLogin(member, remoteIp)

        log.info("member.social.login.success", mapOf("userId" to member.id, "socialType" to socialType))
        return buildResponse(member.id, device, remoteIp)
    }

    // --- Private helpers ---

    private suspend fun verifySmsCode(mobile: String, code: String) {
        val redisInstance = redis ?: throw BadRequestException("SMS code service unavailable")
        val key = "$SMS_CODE_PREFIX$mobile"

        // Atomically verify and consume the SMS code via Lua script to prevent TOCTOU.
        // Returns: 0 = key not found, 1 = wrong code, 2 = matched and deleted.
        val script = """
            local val = redis.call('GET', KEYS[1])
            if val == false then return 0 end
            if val == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 2
            end
            return 1
        """.trimIndent()

        when (redisInstance.evalToLong(script, listOf(key), listOf(code))) {
            0L -> throw BadRequestException("SMS code expired or not sent")
            1L -> throw BadRequestException("Invalid SMS code")
            // 2L = matched and consumed; fall through
        }
    }

    /**
     * SMS 自动注册：先看 server 是否已有该手机号，命中则复用 uid，否则 createUser。
     * 写本地镜像用 [insertMemberWithProvidedId]（caller-provided id）。
     *
     * 入口已要求 E.164（`E164_REGEX`），这里直接透传给 server。
     */
    /**
     * 统一注册收敛(MEMBER_INVITE_CODE §5.3):所有注册方式共用——
     * 邀请码校验(悲观,失败=注册失败)→ 建用户 → 同事务计数+record → 提交后自动加好友。
     * `inviteCodeRequired` 只在这里生效(评审 B:存量用户登录不受影响)。
     */
    private suspend fun registerNewUser(
        mode: String,
        identifierMasked: String?,
        inviteCode: String?,
        buildMember: suspend () -> Member,
    ): Member {
        // inviteCodeRequired 不再在注册期拦截:改为登录后 required-actions
        // gate(bind_invite_code,见 RequiredActionsLogic)。带码注册仍走原路径。
        val trimmedCode = inviteCode?.trim()?.takeIf { it.isNotEmpty() }
        val codeEntity = trimmedCode?.let {
            val logic = inviteLogic ?: throw BadRequestException("INVITE_CODE_UNSUPPORTED")
            logic.validate(it)
        }
        val member = buildMember()
        if (codeEntity != null && inviteLogic != null) {
            val record = inviteLogic.applyInviteForNewUser(codeEntity, member.id, mode, identifierMasked)
            inviteLogic.dispatchAutoFriend(record.id)
            inviteLogic.dispatchInviteReward(record.id)
        }
        // 注册欢迎系统消息(弱一致,失败仅日志不影响注册)
        welcomePort?.let { port ->
            runCatching { port.sendWelcome(member.id) }
                .onFailure { log.warn("member.register.welcome_failed", mapOf("userId" to member.id, "error" to (it.message ?: ""))) }
        }
        return member
    }

    /** USERNAME_PASSWORD 注册(MEMBER_INVITE_CODE §5.1)。 */
    suspend fun register(
        request: controller.app.auth.dto.MemberRegisterRequest,
        remoteIp: String? = null,
    ): MemberLoginResponse {
        if (request.mode != port.MemberAuthPolicy.MODE_USERNAME_PASSWORD) {
            throw BadRequestException("UNSUPPORTED_REGISTER_MODE")
        }
        if (port.MemberAuthPolicy.MODE_USERNAME_PASSWORD !in authPolicy.registerModes) {
            throw BadRequestException("REGISTER_MODE_DISABLED")
        }
        // username 规则 = MemberProfileLogic 同一真源(lowercase 归一 + 格式 + 保留字)
        val username = request.username.trim().lowercase()
        if (!MemberProfileLogic.USERNAME_REGEX.matches(username)) {
            throw BadRequestException("INVALID_USERNAME_FORMAT")
        }
        if (username in MemberProfileLogic.USERNAME_RESERVED) {
            throw BadRequestException("USERNAME_RESERVED")
        }
        if (request.password.length < 8) {
            throw BadRequestException("PASSWORD_TOO_SHORT")
        }
        // nickname 不在注册期强制:空昵称由 complete_profile required-action 引导补全
        if (MemberTable.oneWhere { Member::username eq username } != null) {
            throw BadRequestException("USERNAME_TAKEN")
        }
        val member = registerNewUser(
            mode = port.MemberAuthPolicy.MODE_USERNAME_PASSWORD,
            identifierMasked = maskUsername(username),
            inviteCode = request.inviteCode,
        ) {
            val ref = identityAdapter.createOrBindAccount(CreateMemberAccountCommand(username = username))
            insertMemberWithProvidedId(
                db,
                Member(
                    id = ref.memberId,
                    identityProvider = ref.provider,
                    username = username,
                    usernameUpdatedAt = Clock.System.now().toEpochMilliseconds(),
                    password = PasswordHasher.hash(request.password),
                    nickname = request.nickname?.trim().orEmpty(),
                    registerIp = remoteIp.normalizedIp(),
                ),
            ).also {
                log.info("member.register.username", mapOf("userId" to it.id, "username" to username))
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
    ): MemberLoginResponse {
        val normalized = username.trim().lowercase()
        // 凭证错误是认证失败(401/10011),不是参数错误(400/10100);message 用机器码,
        // 客户端按码映射本地化文案(用户拍板:错误必须人话,不泄原始异常)。
        var member = MemberTable.oneWhere { Member::username eq normalized }
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_CREDENTIALS, "INVALID_CREDENTIALS")
        val stored = member.password
            ?: throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_CREDENTIALS, "INVALID_CREDENTIALS")
        val verification = PasswordHasher.verify(password, stored)
        if (!verification.verified) {
            throw neton.core.http.HttpException(neton.core.http.NetonErrorCode.INVALID_CREDENTIALS, "INVALID_CREDENTIALS")
        }
        if (verification.needsRehash) {
            member = member.copy(password = PasswordHasher.hash(password))
            MemberTable.update(member)
        }
        // 同上：白名单判断，新增状态不会漏。
        if (member.status != model.MemberStatus.NORMAL) {
            throw neton.core.http.HttpException(
                neton.core.http.NetonErrorCode.USER_BANNED,
                if (member.status == model.MemberStatus.DELETED) "ACCOUNT_DELETED" else "ACCOUNT_DISABLED",
            )
        }
        recordSuccessfulLogin(member, remoteIp)
        log.info("member.login.username.success", mapOf("userId" to member.id))
        return buildResponse(member.id, device, remoteIp)
    }

    private fun maskUsername(username: String): String =
        if (username.length <= 4) "${username.first()}***"
        else "${username.take(2)}***${username.takeLast(2)}"

    private suspend fun registerViaAdapter(
        mobile: String,
        nickname: String? = null,
        registerIp: String? = null,
    ): Member {
        val ref = identityAdapter.createOrBindAccount(CreateMemberAccountCommand(mobile = mobile))
        // 昵称:注册表单直填则落库(跳过 complete_profile);否则留空走首登引导。
        val newMember = Member(
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
                "member.sms.rebind_existing_im_account",
                mapOf("userId" to ref.memberId, "mobile" to maskMobile(mobile))
            )
        }
        log.info(
            "member.sms.auto_register",
            mapOf("userId" to ref.memberId, "mobile" to maskMobile(mobile), "created" to ref.created)
        )
        return insertMemberWithProvidedId(db, newMember)
    }

    private suspend fun recordSuccessfulLogin(member: Member, remoteIp: String?) {
        MemberTable.update(
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
    ): MemberLoginResponse = buildResponse(uid, device)

    /**
     * 调 server `/api/service/auth/issue` 颁发 unified token，把 [UnifiedLoginResponse] 映射成
     * 客户端可见的 [MemberLoginResponse]（spec §8）。
     */
    private suspend fun buildResponse(
        uid: Long,
        device: LoginDeviceInfo?,
        remoteIp: String? = null,
    ): MemberLoginResponse {
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

    private fun MemberTokenBundle.toMemberLoginResponse(
        requiredActions: List<RequiredAction>,
    ): MemberLoginResponse =
        MemberLoginResponse(
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
