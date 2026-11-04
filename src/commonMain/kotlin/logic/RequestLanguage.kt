package logic

import contract.core.UserLanguage
import contract.core.UserLanguageReader

/**
 * Resolves the "current language" for a single request at most once and reuses the result.
 *
 * A consumer calls [language] wherever it writes text for this user; the first call reads the
 * preference store through the injected [reader], and every later call returns the memoised value,
 * so one request never issues N preference queries just to render one message. Resolution is lazy:
 * a request that never needs the language (for example one that only returns an error code) never
 * touches the store. The value is derived from the reader per request, so the language is not baked
 * into the token - the user's next request reflects a changed preference immediately.
 *
 * The injected reader is already scoped to one identity realm (the account reader for a C-side user,
 * the console reader for an admin), so two subjects that happen to share a numeric id across realms
 * never share a value. One instance belongs to exactly one request; do not reuse it across requests.
 */
class RequestLanguage(
    private val reader: UserLanguageReader,
    private val userId: Long,
) {
    private var done = false
    private var cached: UserLanguage? = null

    /** The user's stored language, or null when they have none. Reads the store at most once. */
    suspend fun languageOrNull(): UserLanguage? {
        if (!done) {
            cached = reader.language(userId)
            done = true
        }
        return cached
    }

    /** The user's language, falling back to [fallback] when they have no preference. */
    suspend fun language(fallback: UserLanguage = UserLanguage.DEFAULT): UserLanguage =
        languageOrNull() ?: fallback
}
