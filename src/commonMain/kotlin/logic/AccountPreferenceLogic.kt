package logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import neton.core.http.BadRequestException
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.http.ValidationError
import neton.database.api.DbContext
import neton.database.api.Row
import neton.logging.Logger
import kotlin.time.Clock

/**
 * 账号偏好（spec application/account-preferences.md）：每个用户按命名空间存一份 JSON 文档，
 * 例如 `app.channels` 存「我的频道」排序。服务端不解读内容，只负责校验、限额与乐观并发。
 *
 * - 命名空间：小写点分段，见 [NAMESPACE_REGEX]，长度上限 [Limits.maxNamespaceLength]。
 * - 值：任意非 null 的 JSON，按紧凑编码后的 UTF-8 字节数限额 [Limits.maxValueBytes]。
 * - 版本：不存在时为 0；创建后为 1，每次写入 +1。写入必须带上「我看到的版本」，
 *   对不上就 409 [AccountErrorCodes.PREFERENCE_VERSION_CONFLICT]，客户端重新读取后合并再写，不会悄悄覆盖另一端的修改。
 */
class AccountPreferenceLogic(
    private val log: Logger,
    private val db: DbContext,
    private val limits: Limits = Limits(),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    data class Limits(
        val maxNamespaceLength: Int = 64,
        val maxValueBytes: Int = 16 * 1024,
        val maxNamespacesPerUser: Int = 64,
    ) {
        init {
            require(maxNamespaceLength in 1..64) { "maxNamespaceLength must be within 1..64 (column width)" }
            require(maxValueBytes > 0) { "maxValueBytes must be positive" }
            require(maxNamespacesPerUser > 0) { "maxNamespacesPerUser must be positive" }
        }
    }

    /** 读取结果；不存在时 [value] 为 null、[version] 为 0、[updatedAt] 为 null。 */
    data class Preference(
        val namespace: String,
        val value: JsonElement?,
        val version: Long,
        val updatedAt: Long?,
    )

    data class Summary(
        val namespace: String,
        val version: Long,
        val sizeBytes: Int,
        val updatedAt: Long,
    )

    companion object {
        val NAMESPACE_REGEX = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*$")

        private val json = Json

        /** 同一用户的「新建命名空间」串行化用的事务级咨询锁前缀，避免并发创建越过数量上限。 */
        private const val CREATE_LOCK_PREFIX = "account_preferences:"
    }

    fun requireNamespace(raw: String): String {
        if (raw.isEmpty() || raw.length > limits.maxNamespaceLength || !NAMESPACE_REGEX.matches(raw)) {
            throw BadRequestException(AccountErrorCodes.INVALID_PREFERENCE_NAMESPACE, listOf(ValidationError("namespace", AccountErrorCodes.INVALID_PREFERENCE_NAMESPACE, AccountErrorCodes.INVALID_PREFERENCE_NAMESPACE)))
        }
        return raw
    }

    suspend fun get(userId: Long, rawNamespace: String): Preference {
        val namespace = requireNamespace(rawNamespace)
        val row = db.fetchAll(
            "SELECT value, version, updated_at FROM account_preferences WHERE user_id = :u AND namespace = :ns",
            mapOf("u" to userId, "ns" to namespace),
        ).firstOrNull() ?: return Preference(namespace, null, 0, null)
        return Preference(namespace, parseStored(userId, namespace, row.string("value")), row.long("version"), row.long("updated_at"))
    }

    suspend fun list(userId: Long): List<Summary> =
        db.fetchAll(
            """
            SELECT namespace, version, octet_length(value) AS size_bytes, updated_at
            FROM account_preferences WHERE user_id = :u ORDER BY namespace
            """.trimIndent(),
            mapOf("u" to userId),
        ).map { Summary(it.string("namespace"), it.long("version"), it.long("size_bytes").toInt(), it.long("updated_at")) }

    /**
     * 写入。[expectedVersion] 为 null 或 0 表示「我认为它还不存在」，只创建；大于 0 时只在当前版本
     * 正好相等时覆盖。两种情况对不上都是 409 [AccountErrorCodes.PREFERENCE_VERSION_CONFLICT]。
     */
    suspend fun put(userId: Long, rawNamespace: String, value: JsonElement?, expectedVersion: Long?): Preference {
        val namespace = requireNamespace(rawNamespace)
        if (value == null || value is JsonNull) throw BadRequestException(AccountErrorCodes.PREFERENCE_VALUE_REQUIRED, listOf(ValidationError("value", AccountErrorCodes.PREFERENCE_VALUE_REQUIRED, AccountErrorCodes.PREFERENCE_VALUE_REQUIRED)))
        val expected = expectedVersion ?: 0L
        if (expected < 0) throw BadRequestException(AccountErrorCodes.INVALID_PREFERENCE_VERSION, listOf(ValidationError("version", AccountErrorCodes.INVALID_PREFERENCE_VERSION, AccountErrorCodes.INVALID_PREFERENCE_VERSION)))
        val encoded = json.encodeToString(JsonElement.serializer(), value)
        if (encoded.encodeToByteArray().size > limits.maxValueBytes) {
            throw HttpException(NetonErrorCode.PAYLOAD_TOO_LARGE, AccountErrorCodes.PREFERENCE_VALUE_TOO_LARGE)
        }
        val now = clock()
        val row = (if (expected == 0L) create(userId, namespace, encoded, now) else update(userId, namespace, encoded, expected, now))
            ?: throw HttpException(NetonErrorCode.OPERATION_CONFLICT, AccountErrorCodes.PREFERENCE_VERSION_CONFLICT)
        return Preference(namespace, value, row.long("version"), row.long("updated_at"))
    }

    /**
     * 删除。[expectedVersion] 为 null 时无条件删除（不存在也算成功）；给了版本则只删该版本，
     * 不存在或版本不符都是 409。
     */
    suspend fun delete(userId: Long, rawNamespace: String, expectedVersion: Long?) {
        val namespace = requireNamespace(rawNamespace)
        if (expectedVersion == null) {
            db.execute(
                "DELETE FROM account_preferences WHERE user_id = :u AND namespace = :ns",
                mapOf("u" to userId, "ns" to namespace),
            )
            return
        }
        if (expectedVersion <= 0) throw BadRequestException(AccountErrorCodes.INVALID_PREFERENCE_VERSION, listOf(ValidationError("version", AccountErrorCodes.INVALID_PREFERENCE_VERSION, AccountErrorCodes.INVALID_PREFERENCE_VERSION)))
        val deleted = db.execute(
            "DELETE FROM account_preferences WHERE user_id = :u AND namespace = :ns AND version = :v",
            mapOf("u" to userId, "ns" to namespace, "v" to expectedVersion),
        )
        if (deleted == 0L) throw HttpException(NetonErrorCode.OPERATION_CONFLICT, AccountErrorCodes.PREFERENCE_VERSION_CONFLICT)
    }

    /** 账号注销时清掉该用户的全部偏好。 */
    suspend fun deleteAllFor(userId: Long): Long {
        val n = db.execute("DELETE FROM account_preferences WHERE user_id = :u", mapOf("u" to userId))
        if (n > 0) log.info("account.preferences.purged", mapOf("userId" to userId, "count" to n))
        return n
    }

    private suspend fun create(userId: Long, namespace: String, encoded: String, now: Long): Row? = db.transaction {
        // 数量上限要「数 + 插」原子：同一用户的创建在事务级咨询锁下串行（锁随事务结束释放）。
        fetchAll(
            "SELECT 1 AS locked FROM pg_advisory_xact_lock(hashtextextended(:k, 0))",
            mapOf("k" to CREATE_LOCK_PREFIX + userId),
        )
        val count = fetchAll(
            "SELECT COUNT(*) AS c FROM account_preferences WHERE user_id = :u",
            mapOf("u" to userId),
        ).single().long("c")
        val exists = fetchAll(
            "SELECT 1 AS e FROM account_preferences WHERE user_id = :u AND namespace = :ns",
            mapOf("u" to userId, "ns" to namespace),
        ).isNotEmpty()
        if (exists) return@transaction null
        if (count >= limits.maxNamespacesPerUser) {
            throw HttpException(NetonErrorCode.PARAM_OUT_OF_RANGE, AccountErrorCodes.PREFERENCE_NAMESPACE_LIMIT_EXCEEDED)
        }
        fetchAll(
            """
            INSERT INTO account_preferences (user_id, namespace, value, version, created_at, updated_at)
            VALUES (:u, :ns, :v, 1, :now, :now)
            ON CONFLICT (user_id, namespace) DO NOTHING
            RETURNING version, updated_at
            """.trimIndent(),
            mapOf("u" to userId, "ns" to namespace, "v" to encoded, "now" to now),
        ).firstOrNull()
    }

    private suspend fun update(userId: Long, namespace: String, encoded: String, expected: Long, now: Long): Row? =
        db.fetchAll(
            """
            UPDATE account_preferences
            SET value = :v, version = version + 1, updated_at = GREATEST(:now, updated_at)
            WHERE user_id = :u AND namespace = :ns AND version = :expected
            RETURNING version, updated_at
            """.trimIndent(),
            mapOf("u" to userId, "ns" to namespace, "v" to encoded, "expected" to expected, "now" to now),
        ).firstOrNull()

    private fun parseStored(userId: Long, namespace: String, text: String): JsonElement =
        runCatching { json.parseToJsonElement(text) }.getOrElse { e ->
            // 只有经过 put 校验的 JSON 才会落库；读不出来说明库被绕过写坏了，不能当作「不存在」吞掉。
            log.error("account.preferences.corrupt", mapOf("userId" to userId, "namespace" to namespace), cause = e)
            throw HttpException(NetonErrorCode.DATABASE_ERROR, AccountErrorCodes.PREFERENCE_CORRUPT)
        }
}
