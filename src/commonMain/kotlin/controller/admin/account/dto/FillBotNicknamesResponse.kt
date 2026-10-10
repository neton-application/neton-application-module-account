package controller.admin.account.dto

import kotlinx.serialization.Serializable

/**
 * 批量给 is_robot=1 且 nickname 为空/null 的陪玩账号补昵称结果统计。
 *
 *   scanned          扫到的候选数 (= filled + skippedPoolEmpty + errors)
 *   filled           成功补昵称的 bot 数
 *   skippedPoolEmpty 词库未初始化 / 任一边空 → generator 返 null,该条跳过
 *   errors           写库失败的条数 (network / 唯一冲突 / 其他)
 */
@Serializable
data class FillBotNicknamesResponse(
    val scanned: Int,
    val filled: Int,
    val skippedPoolEmpty: Int,
    val errors: Int,
)
