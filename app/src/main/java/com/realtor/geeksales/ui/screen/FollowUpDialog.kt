@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.realtor.geeksales.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realtor.geeksales.data.db.FollowResult
import com.realtor.geeksales.ui.components.AsciiDivider
import com.realtor.geeksales.ui.components.GeekCard
import com.realtor.geeksales.ui.components.GeekGhostButton
import com.realtor.geeksales.ui.components.GeekPrimaryButton
import com.realtor.geeksales.ui.components.GeekTextField
import com.realtor.geeksales.ui.components.resultColor
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.BgElev2
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.Divider
import com.realtor.geeksales.ui.theme.Success
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextSecondary
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.Warning
import com.realtor.geeksales.util.Formatter
import com.realtor.geeksales.viewmodel.CustomerDetailViewModel

@Composable
fun FollowUpDialog(
    customerId: Long,
    prefillPhone: String = "",
    prefillName: String = "",
    prefillDurationSec: Int = 0,
    allowUnknown: Boolean = false,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    vm: CustomerDetailViewModel? = null,
    // Fallback for saving FollowUp without a detail VM (post-call prompt uses MainActivityViewModel)
    saveDirect: ((result: FollowResult, note: String?, remindAt: Long?, phoneIfMissing: String?, nameIfMissing: String?) -> Unit)? = null
) {
    var result by remember { mutableStateOf(FollowResult.CONNECTED) }
    var note by remember { mutableStateOf("") }
    var dateStr by remember { mutableStateOf(Formatter.day(Formatter.addDays(System.currentTimeMillis(), 2))) }
    var timeStr by remember { mutableStateOf("10:00") }
    var phone by remember { mutableStateOf(prefillPhone) }
    var name by remember { mutableStateOf(prefillName) }

    val remindAt: Long? = runCatching {
        val s = "$dateStr $timeStr"
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).parse(s)?.time
    }.getOrNull()

    Column(
        Modifier
            .fillMaxWidth()
            .background(Bg)
            .padding(16.dp)
    ) {
        Text("登记跟进", color = Accent, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text("通话时长  ${Formatter.humanDuration(prefillDurationSec)}", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        if (allowUnknown) {
            GeekTextField(name, { name = it }, label = "客户姓名 (自动创建)", placeholder = "新客户姓名")
            Spacer(Modifier.height(6.dp))
            GeekTextField(phone, { phone = it }, label = "手机号 (自动创建)", placeholder = "必填")
            Spacer(Modifier.height(6.dp))
        }

        Text("沟通结果", color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        // 统一容器：外层单边框 + 行间细分割线（不再每行独立描边，避免线条交错杂乱）
        Column(Modifier.fillMaxWidth().background(BgElev2).border(1.dp, Divider)) {
            FollowResult.values().forEachIndexed { i, r ->
                val selected = result == r
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (selected) Modifier.background(resultColor(r).copy(alpha = 0.14f)) else Modifier)
                        .clickable { result = r }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (selected) "✓" else "○", color = resultColor(r), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
                    Text(resultLabel(r), color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(resultHint(r), color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (i < FollowResult.values().size - 1) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        GeekTextField(note, { note = it }, label = "备注", placeholder = "沟通重点：需求 / 关键信息 / 下一步…", singleLine = false, minLines = 3)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 日期：点击弹出选择器，不再手填（防误填/截断）
            var showDatePicker by remember { mutableStateOf(false) }
            Column(Modifier.weight(1f)) {
                Text("下次跟进(日期)", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    dateStr,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BgElev2).border(1.dp, Divider)
                        .clickable { showDatePicker = true }
                        .padding(vertical = 12.dp, horizontal = 12.dp)
                )
            }
            GeekTextField(timeStr, { timeStr = it }, label = "时间", modifier = Modifier.width(140.dp), placeholder = "10:00")
            if (showDatePicker) {
                val dpState = androidx.compose.material3.rememberDatePickerState(
                    initialSelectedDateMillis = runCatching {
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(dateStr)?.time
                    }.getOrNull() ?: System.currentTimeMillis()
                )
                androidx.compose.material3.DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            dpState.selectedDateMillis?.let { ms ->
                                dateStr = Formatter.day(ms)
                            }
                            showDatePicker = false
                        }) { Text("确定") }
                        TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                    }
                ) { androidx.compose.material3.DatePicker(state = dpState, showModeToggle = false) }
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("3小时后", "明天10点", "后天10点", "一周后").forEach { preset ->
                GeekGhostButton(preset, color = Warning, onClick = {
                    val (d, t) = when (preset) {
                        "3小时后" -> Formatter.day(System.currentTimeMillis()) to
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(Formatter.addHours(System.currentTimeMillis(), 3)))
                        "明天10点" -> Formatter.day(Formatter.addDays(Formatter.todayStart(), 1)) to "10:00"
                        "后天10点" -> Formatter.day(Formatter.addDays(Formatter.todayStart(), 2)) to "10:00"
                        else -> Formatter.day(Formatter.addDays(Formatter.todayStart(), 7)) to "10:00"
                    }
                    dateStr = d; timeStr = t
                })
            }
        }
        Spacer(Modifier.height(10.dp))
        AsciiDivider()
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GeekGhostButton("取消", color = Danger, onClick = onDismiss)
            Spacer(Modifier.weight(1f))
            GeekPrimaryButton("保存登记", {
                val rem = if (dateStr.isBlank()) null else remindAt
                if (saveDirect != null) {
                    saveDirect(result, note.takeIf { it.isNotBlank() }, rem, phone.takeIf { it.isNotBlank() }, name.takeIf { it.isNotBlank() })
                } else {
                    vm?.recordFollowUp(result, note.takeIf { it.isNotBlank() }, rem, prefillDurationSec)
                    onSaved()
                }
            })
        }
    }
}

private fun resultLabel(r: FollowResult): String = when (r) {
    FollowResult.CONNECTED -> "接通 · 有效沟通"
    FollowResult.APPOINTMENT -> "约谈 / 面谈"
    FollowResult.PENDING -> "待跟进 · 未置可否"
    FollowResult.NOT_REACHED -> "未接通 / 无人接"
    FollowResult.NOT_INTERESTED -> "明确拒绝"
    FollowResult.WRONG_NUMBER -> "错号 / 空号"
    FollowResult.SHUTDOWN -> "关机 / 停机"
}

private fun resultHint(r: FollowResult): String = when (r) {
    FollowResult.CONNECTED -> "已沟通，记录要点"
    FollowResult.APPOINTMENT -> "已约时间地点"
    FollowResult.PENDING -> "留待下次联系"
    FollowResult.NOT_REACHED -> "换个时间再打"
    FollowResult.NOT_INTERESTED -> "标记原因后暂缓"
    FollowResult.WRONG_NUMBER -> "核对号码"
    FollowResult.SHUTDOWN -> "停机风险，谨慎跟进"
}
