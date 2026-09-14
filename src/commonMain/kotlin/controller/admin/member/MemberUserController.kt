package controller.admin.member

import controller.admin.member.dto.FillBotNicknamesResponse
import controller.admin.member.dto.MemberVO
import controller.admin.member.dto.UpdateMemberRequest
import controller.admin.member.dto.UpdateMemberUserLevelRequest
import controller.admin.member.dto.UpdateMemberUserPasswordRequest
import controller.admin.member.dto.UpdateMemberUserPointRequest
import dto.PageResponse
import logic.MemberGroupLogic
import logic.MemberLevelLogic
import logic.MemberLogic
import logic.NicknameGenerator
import logic.MemberGeoLocationService
import logic.MemberLoginDeviceLogic
import model.Member
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Body
import neton.core.annotations.PathVariable
import neton.core.annotations.Query

@Controller("/member/user")
class MemberUserController(
    private val memberLogic: MemberLogic,
    private val nicknameGenerator: NicknameGenerator,
    private val memberLevelLogic: MemberLevelLogic,
    private val memberGroupLogic: MemberGroupLogic,
    private val memberLoginDeviceLogic: MemberLoginDeviceLogic,
) {
    private val geoLocation = MemberGeoLocationService.fromConfig()

    @Put("/update")
    @Permission("member:user:update")
    suspend fun update(@Body request: UpdateMemberRequest) {
        val existing = memberLogic.get(request.id)
            ?: throw IllegalArgumentException("Member not found: ${request.id}")
        memberLogic.update(
            existing.copy(
                mobile = request.mobile,
                nickname = request.nickname,
                avatar = request.avatar,
                status = request.status,
                levelId = request.levelId,
                groupId = request.groupId
            )
        )
    }

    @Put("/update-level")
    @Permission("member:user:update")
    suspend fun updateLevel(@Body req: UpdateMemberUserLevelRequest) {
        memberLogic.updateLevel(req.id, req.levelId, 0, null)
    }

    @Put("/update-point")
    @Permission("member:user:update")
    suspend fun updatePoint(@Body req: UpdateMemberUserPointRequest) {
        memberLogic.updatePoint(req.id, req.point, 1, "Admin adjust", null)
    }

    /**
     * 管理员重设会员登录密码（客服/找回场景）。不需要旧密码，也不返回任何密码信息；
     * 明文经 PasswordHasher 加盐哈希后落库，改完会员即可用新密码登录。
     * 单独权限点 `member:user:update-password`——重设密码等于接管账号，不能和普通编辑同权。
     */
    @Put("/update-password")
    @Permission("member:user:update-password")
    suspend fun updatePassword(@Body req: UpdateMemberUserPasswordRequest) {
        // @Size 之外再显式挡一次：空串/纯空格在部分校验实现下会漏过（历史坑）。
        val password = req.password.trim()
        if (password.length < 8) {
            throw IllegalArgumentException("Password must be at least 8 characters")
        }
        memberLogic.updatePassword(req.id, password)
    }

    @Get("/get/{id}")
    @Permission("member:user:query")
    suspend fun get(@PathVariable id: Long): MemberVO? {
        val member = memberLogic.get(id) ?: return null
        return member.toAdminVO(
            levelName = member.levelId?.let { memberLevelLogic.get(it)?.name },
            groupName = member.groupId?.let { memberGroupLogic.getById(it)?.name },
        )
    }

    @Get("/login-devices")
    @Permission("member:user:query")
    suspend fun loginDevices(@Query userId: Long) = memberLoginDeviceLogic.list(userId)

    @Get("/page")
    @Permission("member:user:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query nickname: String? = null,
        @Query mobile: String? = null,
        // uid 收字符串自己解析，不声明成 Long?：框架的 parseLong 对 "" 和 "abc"
        // 一律返回 null，声明成 Long? 就分不清「没填」和「填了但不是数字」，
        // 后者会静默丢掉筛选条件、把全部用户列出来，看着像是「什么都能匹配」。
        @Query uid: String? = null,
        @Query username: String? = null,
        @Query status: Int? = null,
        @Query levelId: Long? = null,
        @Query groupId: Long? = null,
        // 默认隐藏陪玩机器人；admin 需要时 ?includeRobot=true。
        @Query includeRobot: Boolean = false,
        // 默认隐藏无凭证游客（客服 widget 访客等）；admin 需要时 ?includeGuest=true。
        @Query includeGuest: Boolean = false
    ): PageResponse<MemberVO> {
        val uidText = uid?.trim().orEmpty()
        val uidValue = if (uidText.isEmpty()) null else uidText.toLongOrNull()
            ?: return PageResponse(emptyList(), 0, pageNo, pageSize, 0)

        return memberLogic.page(
        pageNo,
        pageSize,
        nickname,
        mobile,
        uidValue,
        username,
        status,
        levelId,
        groupId,
        includeRobot,
        includeGuest,
        ).let { page ->
            // 等级/分组名整页一次性解析：逐行去问会变成 N+1，而两张表都是几十行的
            // 小字典，整表拉下来比按 id 逐个查还便宜。
            val levelNames = memberLevelLogic.list().associate { it.id to it.name }
            val groupNames = memberGroupLogic.listAll().associate { it.id to it.name }
            PageResponse(
                list = page.list.map {
                    it.toAdminVO(
                        levelName = it.levelId?.let(levelNames::get),
                        groupName = it.groupId?.let(groupNames::get),
                    )
                },
                total = page.total,
                page = page.page,
                size = page.size,
                totalPages = page.totalPages,
            )
        }
    }

    private fun Member.toAdminVO(
        levelName: String? = null,
        groupName: String? = null,
    ) = MemberVO(
        id = id,
        identityProvider = identityProvider,
        username = username,
        usernameUpdatedAt = usernameUpdatedAt,
        mobile = mobile,
        nickname = nickname,
        avatar = avatar,
        gender = gender,
        bio = bio,
        birthday = birthday,
        isRobot = isRobot,
        isGuest = isGuest,
        status = status,
        levelId = levelId,
        levelName = levelName,
        experience = experience,
        point = point,
        groupId = groupId,
        groupName = groupName,
        registerIp = registerIp,
        // 注册地按注册 IP 反查：新注册、还没登录过的账号 login_ip 是空的，
        // 只认登录 IP 的话这类账号在后台完全看不出来自哪里。
        registerRegion = geoLocation.resolve(registerIp),
        loginIp = loginIp,
        loginRegion = geoLocation.resolve(loginIp),
        loginDate = loginDate,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** 扫所有 is_robot=1 且 nickname 为 null/空 的陪玩账号，用 [NicknameGenerator] 词库随机补昵称。
     *
     *  - 词库未初始化 / 任一边空 → generator 返 null → 该条 skipped (pool_empty)
     *  - 已有非空 nickname 的 bot 不动 (admin 想重抽得删除原 nickname 再调)
     *  - 每条单独尝试,某条失败不阻断其他 (errors 计数)
     *
     *  权限走 member:user:update (跟改昵称同一级别)。
     */
    @Post("/fill-bot-nicknames")
    @Permission("member:user:update")
    suspend fun fillBotNicknames(
        /** true=只补 nickname='' 的 (增量); false (默认)=重抽所有 is_robot=1 (强制刷新). */
        @Query onlyEmpty: Boolean = false,
    ): FillBotNicknamesResponse {
        val result = memberLogic.fillBotNicknames(nicknameGenerator, onlyEmpty = onlyEmpty)
        return FillBotNicknamesResponse(
            scanned = result.scanned,
            filled = result.filled,
            skippedPoolEmpty = result.skippedPoolEmpty,
            errors = result.errors,
        )
    }
}
