package controller.admin.nickname

import controller.admin.nickname.dto.ImportNicknameWordsRequest
import controller.admin.nickname.dto.ImportNicknameWordsResponse
import controller.admin.nickname.dto.NicknameWordPageResponse
import controller.admin.nickname.dto.NicknameWordRow
import controller.admin.nickname.dto.UpdateNicknameWordStatusRequest
import kotlin.time.Clock
import logic.NicknameGenerator
import model.AccountNicknameAdjective
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Delete
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Query
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import neton.database.dsl.*
import table.AccountNicknameAdjectiveTable

/**
 * Admin 形容词词库管理。
 *
 * 工作流:
 *   1. admin 准备一份 .txt (一行一词),前端读成 `string[]` POST `/import`
 *   2. server 去重 (`ON CONFLICT (word) DO NOTHING`),返新增/跳过 count
 *   3. 改完后 `/reload` 让 [NicknameGenerator] in-memory 池刷新生效
 *
 * 导出: `/export` 走 list-all 路径,前端拿 `string[]` 后保存为 .txt 即可。
 */
@Controller("/account/nickname-adjective")
class NicknameAdjectiveController(
    private val nicknameGenerator: NicknameGenerator,
) {

    @Get("/page")
    @Permission("account:nickname:query")
    suspend fun page(
        @Query page: Int = 1,
        @Query pageSize: Int = 50,
        @Query word: String? = null,
        @Query status: Int? = null,
    ): NicknameWordPageResponse {
        val result = AccountNicknameAdjectiveTable.query {
            where {
                and(
                    whenNotBlank(word) { AccountNicknameAdjective::word like "%$it%" },
                    whenPresent(status) { AccountNicknameAdjective::status eq it },
                )
            }
            orderBy(AccountNicknameAdjective::id.desc())
        }.page(page, pageSize)
        return NicknameWordPageResponse(
            list = result.items.map { it.toRow() },
            total = result.total,
            page = page,
            pageSize = pageSize,
        )
    }

    /** 导出全量启用词条 (admin 前端拼 .txt 下载用)。 */
    @Get("/export")
    @Permission("account:nickname:query")
    suspend fun export(): List<String> {
        return AccountNicknameAdjectiveTable.query {
            where { AccountNicknameAdjective::status eq 1 }
            orderBy(AccountNicknameAdjective::id.asc())
        }.list().map { it.word }
    }

    /** 批量导入 — `ON CONFLICT (word) DO NOTHING` 让重复 import 安全。 */
    @Post("/import")
    @Permission("account:nickname:create")
    suspend fun import(@Body request: ImportNicknameWordsRequest): ImportNicknameWordsResponse {
        val received = request.words.size
        if (received == 0) {
            return ImportNicknameWordsResponse(received = 0, inserted = 0, skipped = 0)
        }
        val now = Clock.System.now().toEpochMilliseconds()
        // 先去掉本次请求里的重复(空白 / 大小写规范化)
        val unique = request.words
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 32 }
            .toSet()
        var inserted = 0
        for (w in unique) {
            val existed = AccountNicknameAdjectiveTable.oneWhere {
                AccountNicknameAdjective::word eq w
            }
            if (existed == null) {
                AccountNicknameAdjectiveTable.insert(
                    AccountNicknameAdjective(word = w, status = 1, createdAt = now, updatedAt = now)
                )
                inserted++
            }
        }
        // 词库变了, 刷一下 generator in-memory 池
        nicknameGenerator.reload()
        return ImportNicknameWordsResponse(
            received = received,
            inserted = inserted,
            skipped = received - inserted,
        )
    }

    @Put("/{id}/status")
    @Permission("account:nickname:update")
    suspend fun updateStatus(
        @PathVariable id: Long,
        @Body request: UpdateNicknameWordStatusRequest,
    ) {
        val row = AccountNicknameAdjectiveTable.get(id)
            ?: throw NotFoundException("nickname adjective id=$id not found")
        if (request.status !in 0..1) {
            throw BadRequestException("status must be 0 or 1")
        }
        val now = Clock.System.now().toEpochMilliseconds()
        AccountNicknameAdjectiveTable.update(row.copy(status = request.status, updatedAt = now))
        nicknameGenerator.reload()
    }

    @Delete("/{id}")
    @Permission("account:nickname:delete")
    suspend fun delete(@PathVariable id: Long) {
        val row = AccountNicknameAdjectiveTable.get(id)
            ?: throw NotFoundException("nickname adjective id=$id not found")
        AccountNicknameAdjectiveTable.destroy(row.id)
        nicknameGenerator.reload()
    }

    @Post("/reload")
    @Permission("account:nickname:update")
    suspend fun reload() {
        nicknameGenerator.reload()
    }
}

private fun AccountNicknameAdjective.toRow(): NicknameWordRow = NicknameWordRow(
    id = id,
    word = word,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
