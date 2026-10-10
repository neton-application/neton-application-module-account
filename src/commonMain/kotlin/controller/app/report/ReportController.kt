package controller.app.report

import controller.app.report.dto.SubmitReportRequest
import logic.AccountReportLogic
import neton.core.annotations.*
import neton.core.interfaces.Identity

/**
 * 举报入口（App 端）。路径单层业务前缀 `/member/report`（不写 `/app/`，由 mount 加）。
 * 客户端：消息长按举报 / 用户资料页举报用户。
 */
@Controller("/member/report")
class ReportController(
    private val reportLogic: AccountReportLogic
) {

    /** 提交举报，返回举报记录 id。 */
    @Post("/submit")
    suspend fun submit(identity: Identity, @Body request: SubmitReportRequest): Long =
        reportLogic.submit(identity.id.toLong(), request)
}
