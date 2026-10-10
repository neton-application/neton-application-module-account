package model

import kotlinx.serialization.Serializable
import neton.database.annotations.Table
import neton.database.annotations.Id
import neton.database.annotations.SoftDelete
import neton.database.annotations.CreatedAt
import neton.database.annotations.UpdatedAt

@Serializable
@Table("account_users")
data class Account(
    @Id
    val id: Long = 0,
    /** 身份来源追溯(MEMBER-IDENTITY-ADAPTER):builtin=内置账号;privchat=privchat user_id(id 即 privchat user_id)。创建后不可由普通业务接口改。 */
    val identityProvider: String = "builtin",
    val mobile: String? = null,
    /** 邮箱（V003）：绑定后可用于找回密码；小写归一存储 */
    val email: String? = null,
    val password: String? = null,
    val nickname: String,
    val avatar: String? = null,
    /** 账号状态，取值见 [AccountStatus]。 */
    val status: Int = 1,
    val registerIp: String? = null,
    val loginIp: String? = null,
    val loginDate: Long? = null,
    /** 平台账号名；spec MODULE_MEMBER_PROFILE_SPEC §2。application 是守门人，DB UNIQUE 兜底。 */
    val username: String? = null,
    /** 上次改 username 的 millis 时间戳；30 天限改判断依据。 */
    val usernameUpdatedAt: Long? = null,
    /** 0=unknown / 1=male / 2=female / 9=other */
    val gender: Int = 0,
    /** 个性签名，0–200 字符；null = 清空。 */
    val bio: String? = null,
    /** ISO `YYYY-MM-DD` 字符串；跨端避免 DATE 时区歧义。 */
    val birthday: String? = null,
    /** 1=系统生成的陪玩机器人账号，默认不进普通会员列表/积分/每日统计；与
     *  game_club_member.is_auto_player(club-scoped) 正交。spec GAME_CLUB_ROOM_TEMPLATE_SPEC。 */
    val isRobot: Int = 0,
    /** 1=无凭证的游客账号（客服 widget 访客、游戏游客登录、试用账号），默认不进普通会员
     *  列表/积分/每日统计；与 [isRobot] 正交 —— 机器人不是游客，游客也不是机器人。
     *  绑定凭证即升级为正式会员：同一行清零本列，id 不变，IM 身份与历史原样保留。
     *  spec CUSTOMER_SERVICE_PLATFORM_SPEC §3.2。 */
    val isGuest: Int = 0,
    /** 会话版本；改密/登出全端时递增，builtin token 携带并校验（MEMBER-IDENTITY §会话）。 */
    val sessionVersion: Long = 0,
    /** 当前占用的设备；开启单设备登录后，换设备登录会顶掉上一台。null=尚未记录。 */
    val currentDeviceId: String? = null,
    @SoftDelete
    val deleted: Int = 0,
    @CreatedAt
    val createdAt: Long? = null,
    @UpdatedAt
    val updatedAt: Long? = null
)

/**
 * 账号状态。
 *
 * 🔴 判断"能不能用这个账号"要写成 `status == NORMAL`，不要写 `status != DISABLED`。
 * 后者在新增状态时会**默默放行**——DELETED 就是这么被漏掉的一类：登录处原本四处
 * 都写着 `if (status == 0) 拒绝`，加一个新状态它们一个都拦不住。
 */
object AccountStatus {
    /** 正常。 */
    const val NORMAL = 1

    /** 被管理员禁用。 */
    const val DISABLED = 0

    /**
     * 用户自己注销（软删除）。
     *
     * 数据保留：后台账号管理仍然看得到这条记录，状态显示「已删除」——运营要能查到
     * "这个人注销过"，而不是账号凭空消失。用户侧则彻底不可用：不能登录，已签发的
     * token 由 session_version 递增作废。
     *
     * 这是 App Store 审核指南 5.1.1(v) 要求的"App 内可发起账号删除"的落点。
     */
    const val DELETED = 2
}
