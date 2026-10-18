package error

import contract.testing.ErrorMessageGuard
import kotlin.test.Test

/** 业务错误 message 必须是 [logic.AccountErrorCodes] 里的机器码（spec application/account-errors）。 */
class ErrorMessageGuardTest {
    @Test
    fun commonMainThrowsOnlyMachineCodes() {
        ErrorMessageGuard.assertModuleClean(allowlist = emptySet())
    }
}
