package port

/**
 * 账号公开资料的批量读取端口。
 *
 * 给其他模块展示「某个账号是谁」用：评论作者、点赞列表、排行榜……调用方拿到一批账号 id，
 * **一次**取回昵称和头像，不逐个查、也不读 `account_users` 表（模块间不读别人的表，
 * content-platform-architecture §4.1）。
 *
 * - 谁实现：account 绑定默认实现 [impl.AccountTableProfileReader]（bindIfAbsent，读本库
 *   `account_users`）；产品 / 身份适配器（如资料真源在外部系统）在 account 之前绑定即可覆盖。
 * - 谁调用：任何需要展示他人身份的模块，请求时 `ctx.getOrNull(AccountProfileReader::class)`，
 *   取不到就只展示 id。
 * - 失败：数据库异常原样抛出，由调用方决定降级（通常降级为只展示 id）。
 *
 * 只返回**对任何人都可公开**的字段。手机号、邮箱、用户名都不在这里：用户名在 PrivChat 里
 * 只对好友可见（资料可见性投影），属于需要按观看者判断的字段，不适合一个无观看者的批量端口。
 */
interface AccountProfileReader {
    /**
     * 批量读取公开资料。返回里只包含存在的账号；不存在的 id 不出现在结果里。
     * 已注销的账号仍会返回，但 [AccountPublicProfile.deleted] 为 true，昵称、头像为空。
     */
    suspend fun profiles(ids: Collection<Long>): Map<Long, AccountPublicProfile>
}

/** 账号的公开展示资料。 */
data class AccountPublicProfile(
    val id: Long,
    /** 展示昵称；账号已注销时为 null。 */
    val nickname: String?,
    /** 头像地址，按账号当前存储原样给出（上传头像后存的是可直接访问的 URL）；未设置或已注销为 null。 */
    val avatar: String?,
    /** 账号已注销（用户自己删除或被软删除）。调用方应展示为「已注销用户」而不是旧昵称。 */
    val deleted: Boolean = false,
)
