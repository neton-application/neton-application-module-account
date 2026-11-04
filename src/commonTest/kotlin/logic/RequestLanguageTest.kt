package logic

import contract.core.UserLanguage
import contract.core.UserLanguageReader
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Request-scoped language resolution semantics (pure, no database): resolve once, reuse the result,
 * stay lazy so paths that never need the language never query, and fall back when there is none.
 */
class RequestLanguageTest {

    private class CountingReader(private val answer: UserLanguage?, var calls: Int = 0) : UserLanguageReader {
        override suspend fun language(userId: Long): UserLanguage? { calls++; return answer }
    }

    @Test
    fun `reads once and reuses the memoised value across consumers`() = runBlocking {
        val reader = CountingReader(UserLanguage.EN)
        val ctx = RequestLanguage(reader, 1L)
        assertEquals(0, reader.calls)                          // lazy: not resolved until asked
        assertEquals(UserLanguage.EN, ctx.language())           // first ask hits the store
        assertEquals(UserLanguage.EN, ctx.language())           // later asks reuse it
        assertEquals(UserLanguage.EN, ctx.languageOrNull())
        assertEquals(1, reader.calls)                            // exactly one query for the request
    }

    @Test
    fun `no preference reads once and falls back to the site default`() = runBlocking {
        val reader = CountingReader(null)
        val ctx = RequestLanguage(reader, 2L)
        assertNull(ctx.languageOrNull())
        assertEquals(UserLanguage.ZH_CN, ctx.language())         // UserLanguage.DEFAULT
        assertEquals(UserLanguage.JA, ctx.language(UserLanguage.JA)) // caller may pass another fallback
        assertEquals(1, reader.calls)                            // still a single read
    }

    @Test
    fun `a path that never needs the language never queries the store`() = runBlocking {
        val reader = CountingReader(UserLanguage.JA)
        RequestLanguage(reader, 3L)                              // constructed, but language() not called
        assertEquals(0, reader.calls)
    }
}
