package logic

import controller.app.report.dto.SubmitReportRequest
import enums.ReportReason
import enums.ReportTargetType
import model.Report
import table.ReportTable
import neton.core.http.BadRequestException
import neton.logging.Logger

/**
 * 举报最小治理链路（App Store UGC 1.2）：校验 + 落库。
 * admin 后台消费举报记录（不在此模块处理工单流转）。
 */
@neton.core.annotations.Logic(logger = "logic.account-report")
class AccountReportLogic(
    private val log: Logger
) {

    private companion object {
        const val MAX_DESCRIPTION = 500
    }

    /** 提交举报，返回举报记录 id。 */
    suspend fun submit(reporterUid: Long, request: SubmitReportRequest): Long {
        val targetType = request.targetType.trim()
        if (!ReportTargetType.isValid(targetType)) {
            throw BadRequestException("Invalid report target type: ${request.targetType}")
        }
        val targetId = request.targetId.trim()
        if (targetId.isEmpty()) {
            throw BadRequestException("Report target id is empty")
        }
        if (!ReportReason.isValid(request.reason)) {
            throw BadRequestException("Invalid report reason: ${request.reason}")
        }
        val description = request.description?.trim()?.takeIf { it.isNotEmpty() }
        if (description != null && description.length > MAX_DESCRIPTION) {
            throw BadRequestException("Report description too long (max $MAX_DESCRIPTION)")
        }

        val id = ReportTable.insert(
            Report(
                reporterUid = reporterUid,
                targetType = targetType,
                targetId = targetId,
                reportedUid = request.reportedUid,
                reason = request.reason,
                description = description,
                evidenceMessageId = request.evidenceMessageId,
                status = 0,
            )
        ).id
        log.info("report submitted id=$id reporter=$reporterUid type=$targetType target=$targetId reason=${request.reason}")
        return id
    }
}
