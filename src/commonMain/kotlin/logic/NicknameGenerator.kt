package logic

import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.AccountNicknameAdjective
import model.AccountNicknameNoun
import neton.database.dsl.*
import neton.logging.Logger
import table.AccountNicknameAdjectiveTable
import table.AccountNicknameNounTable

/**
 * 自动昵称生成器 —— 服务客户端「填昵称引导页」prefill 默认值。
 *
 * **生成规则**: `形容词 + 名词`,e.g. `"勇敢的狮子"`
 * - 形容词/名词独立两张表 (`account_nickname_adjective` / `account_nickname_noun`)
 * - 撞库概率 = 1 / (形容词数 × 名词数), 词库够大时业务可接受 (不做唯一性约束)
 * - 词库未初始化 / 单边空 → 返 `null`,caller 自行兜底
 *
 * **性能**: 启动时 [reload] 把全部 status=1 词条 load 进内存(~10k × 2 词大概
 * ~200KB),之后 [next] 走 in-memory 随机选词,零 DB 压力。admin 后台导入
 * 新词后调 [reload] 让新词生效;无需重启 application。
 *
 * **并发**: [reload] / [next] 走 [Mutex] 串行化,避免读写竞争(K/N 多 worker
 * 路径下尤其重要)。reload 频率低 (admin 手动操作),次数级影响忽略。
 */
@neton.core.annotations.Logic(logger = "logic.account-nickname")
class NicknameGenerator(
    private val log: Logger,
) {
    private val mutex = Mutex()
    @Volatile private var adjectives: List<String> = emptyList()
    @Volatile private var nouns: List<String> = emptyList()
    @Volatile private var loaded: Boolean = false

    /** 从 DB 一次性 load 全部 status=1 词条到内存。
     *
     *  - 首次 [next] 调用时自动触发 (lazy);application 启动期 non-suspend
     *    不便调 suspend reload,放后延到第一次 suggest 调用,延迟可接受
     *  - admin 后台 import / disable / enable 词条后由 controller 调本方法
     *    显式刷新
     */
    suspend fun reload() {
        val adjs = AccountNicknameAdjectiveTable.query {
            where { AccountNicknameAdjective::status eq 1 }
        }.list().map { it.word }
        val ns = AccountNicknameNounTable.query {
            where { AccountNicknameNoun::status eq 1 }
        }.list().map { it.word }
        mutex.withLock {
            adjectives = adjs
            nouns = ns
            loaded = true
        }
        log.info("nickname.generator.reload adj_count=${adjs.size} noun_count=${ns.size}")
    }

    /** 返回随机组合 nickname。词库任一边为空 → 返 `null`,caller 走兜底
     *  (例如客户端展示 `Member_<mobile后4位>` 风格的旧默认值)。
     *
     *  首次调用自动 [reload];之后 in-memory 选词 0 DB 压力。 */
    suspend fun next(): String? {
        if (!loaded) reload()
        return mutex.withLock {
            if (adjectives.isEmpty() || nouns.isEmpty()) return@withLock null
            val a = adjectives[Random.nextInt(adjectives.size)]
            val n = nouns[Random.nextInt(nouns.size)]
            "$a$n"
        }
    }
}
