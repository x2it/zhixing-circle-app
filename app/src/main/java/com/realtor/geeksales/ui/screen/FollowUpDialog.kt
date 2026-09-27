package com.realtor.geeksales.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
        Column(Modifier.fillMaxWidth().background(BgElev2).padding(10.dp)) {
            FollowResult.values().forEach { r ->
                val selected = result == r
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (selected) Modifier.background(resultColor(r).copy(alpha = 0.12f)) else Modifier)
                        .border(1.dp, if (selected) resultColor(r) else Divider)
                        .clickable { result = r }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (selected) "✓ " else "○ ", color = resultColor(r), style = MaterialTheme.typography.titleMedium)
                    Text(r.name, color = resultColor(r), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(10.dp))
                    Text(resultHint(r), color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        GeekTextField(note, { note = it }, label = "备注", placeholder = "沟通重点：预算/房源/看房时间…", singleLine = false, minLines = 3)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GeekTextField(dateStr, { dateStr = it }, label = "下次跟进(日期)", modifier = Modifier.weight(1f), placeholder = "2025-08-30")
            GeekTextField(timeStr, { timeStr = it }, label = "时间", modifier = Modifier.width(140.dp), placeholder = "10:00")
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("今天+3h", "明天10点", "后天10点", "1周后").forEach { preset ->
                GeekGhostButton(preset, color = Warning, onClick = {
                    val (d, t) = when (preset) {
                        "今天+3h" -> Formatter.day(System.currentTimeMillis()) to
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

private fun resultHint(r: FollowResult): String = when (r) {
    FollowResult.CONNECTED -> "接通 & 有效沟通"
    FollowResult.APPOINTMENT -> "约看房 / 面谈"
    FollowResult.PENDING -> "待跟进，未置可否"
    FollowResult.NOT_REACHED -> "未接通 / 无人接"
    FollowResult.NOT_INTERESTED -> "明确拒绝"
    FollowResult.WRONG_NUMBER -> "错号 / 空号"
    FollowResult.SHUTDOWN -> "关机 / 停机"
}
