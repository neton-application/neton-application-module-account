package controller.admin.nickname

import logic.AccountErrorCodes
import neton.core.http.ValidationError
import controller.admin.nickname.dto.ImportNicknameWordsRequest
import controller.admin.nickname.dto.ImportNicknameWordsResponse
import controller.admin.nickname.dto.NicknameWordPageResponse
import controller.admin.nickname.dto.NicknameWordRow
import controller.admin.nickname.dto.UpdateNicknameWordStatusRequest
import kotlin.time.Clock
import logic.NicknameGenerator
import model.AccountNicknameNoun
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
import table.AccountNicknameNounTable

/**
 * Admin 名词词库管理。结构跟 [NicknameAdjectiveController] 完全对称,
 * 只是 table / route 不同。
 */
@Controller("/account/nickname-noun")
class NicknameNounController(
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
        val result = AccountNicknameNounTable.query {
            where {
                and(
                    whenNotBlank(word) { AccountNicknameNoun::word like "%$it%" },
                    whenPresent(status) { AccountNicknameNoun::status eq it },
                )
            }
            orderBy(AccountNicknameNoun::id.desc())
        }.page(page, pageSize)
        return NicknameWordPageResponse(
            list = result.items.map { it.toRow() },
            total = result.total,
            page = page,
            pageSize = pageSize,
        )
    }

    @Get("/export")
    @Permission("account:nickname:query")
    suspend fun export(): List<String> {
        return AccountNicknameNounTable.query {
            where { AccountNicknameNoun::status eq 1 }
            orderBy(AccountNicknameNoun::id.asc())
        }.list().map { it.word }
    }

    @Post("/import")
    @Permission("account:nickname:create")
    suspend fun import(@Body request: ImportNicknameWordsRequest): ImportNicknameWordsResponse {
        val received = request.words.size
        if (received == 0) {
            return ImportNicknameWordsResponse(received = 0, inserted = 0, skipped = 0)
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val unique = request.words
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 32 }
            .toSet()
        var inserted = 0
        for (w in unique) {
            val existed = AccountNicknameNounTable.oneWhere {
                AccountNicknameNoun::word eq w
            }
            if (existed == null) {
                AccountNicknameNounTable.insert(
                    AccountNicknameNoun(word = w, status = 1, createdAt = now, updatedAt = now)
                )
                inserted++
            }
        }
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
        val row = AccountNicknameNounTable.get(id)
            ?: throw NotFoundException(AccountErrorCodes.NICKNAME_WORD_NOT_FOUND)
        if (request.status !in 0..1) {
            throw BadRequestException(
                AccountErrorCodes.INVALID_NICKNAME_WORD_STATUS,
                listOf(ValidationError("status", AccountErrorCodes.INVALID_NICKNAME_WORD_STATUS, AccountErrorCodes.INVALID_NICKNAME_WORD_STATUS)),
            )
        }
        val now = Clock.System.now().toEpochMilliseconds()
        AccountNicknameNounTable.update(row.copy(status = request.status, updatedAt = now))
        nicknameGenerator.reload()
    }

    @Delete("/{id}")
    @Permission("account:nickname:delete")
    suspend fun delete(@PathVariable id: Long) {
        val row = AccountNicknameNounTable.get(id)
            ?: throw NotFoundException(AccountErrorCodes.NICKNAME_WORD_NOT_FOUND)
        AccountNicknameNounTable.destroy(row.id)
        nicknameGenerator.reload()
    }

    @Post("/reload")
    @Permission("account:nickname:update")
    suspend fun reload() {
        nicknameGenerator.reload()
    }
}

private fun AccountNicknameNoun.toRow(): NicknameWordRow = NicknameWordRow(
    id = id,
    word = word,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
