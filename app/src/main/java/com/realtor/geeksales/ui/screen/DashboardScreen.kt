package com.realtor.geeksales.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realtor.geeksales.navigation.Routes
import com.realtor.geeksales.ui.components.GeekCard
import com.realtor.geeksales.ui.components.GeekGhostButton
import com.realtor.geeksales.ui.components.GeekTopBar
import com.realtor.geeksales.ui.components.StatTile
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.IntentA
import com.realtor.geeksales.ui.theme.IntentB
import com.realtor.geeksales.ui.theme.IntentC
import com.realtor.geeksales.ui.theme.IntentD
import com.realtor.geeksales.ui.theme.IntentS
import com.realtor.geeksales.ui.theme.IntentU
import com.realtor.geeksales.ui.theme.IntentV
import com.realtor.geeksales.ui.theme.Success
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.TextSecondary
import com.realtor.geeksales.ui.theme.Warning
import com.realtor.geeksales.viewmodel.DashboardViewModel

@Composable
fun DashboardScreen(
    onNav: (String) -> Unit,
    vm: DashboardViewModel = hiltViewModel()
) {
    val totalCustomers by vm.totalCustomers.collectAsStateWithLifecycle()
    val calledToday by vm.calledToday.collectAsStateWithLifecycle()
    val notCalledToday by vm.notCalledToday.collectAsStateWithLifecycle()
    val overdue by vm.overdue.collectAsStateWithLifecycle()
    val followUpsToday by vm.followUpsToday.collectAsStateWithLifecycle()
    val intentCounts by vm.intentCounts.collectAsStateWithLifecycle()
    val overdueCustomers by vm.overdueCustomers.collectAsStateWithLifecycle()
    val todayCustomers by vm.todayCustomers.collectAsStateWithLifecycle()

    val intentMap = intentCounts.associate { it.level to it.cnt }
    val maxCount = (intentMap.values.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(title = "TMA // 工作台", subtitle = "Only the next call.")
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    StatTile("总客户", totalCustomers.toString(), accent = Accent)
                }
                Box(Modifier.weight(1f)) {
                    StatTile("今日已拨打", calledToday.toString(), accent = Success)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    StatTile("今日未拨打", notCalledToday.toString(), accent = Warning)
                }
                Box(Modifier.weight(1f)) {
                    StatTile("过期跟进", overdue.toString(), accent = Danger)
                }
                Box(Modifier.weight(1f)) {
                    StatTile("今日跟进", followUpsToday.toString(), accent = Accent)
                }
            }

            // 今日跟进（界面①：逾期 + 今日，点击直达客户详情）
            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("今日跟进 · 逾期${overdueCustomers.size} · 今日${todayCustomers.size}", color = Accent, style = MaterialTheme.typography.labelMedium)
                    if (overdueCustomers.isEmpty() && todayCustomers.isEmpty()) {
                        Text("暂无待跟进客户", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        overdueCustomers.take(5).forEach { c ->
                            FollowupRow(c, "逾期", Danger) { onNav("${com.realtor.geeksales.navigation.Routes.CUSTOMER_DETAIL.replace("{id}", c.id.toString())}") }
                        }
                        todayCustomers.take(5).forEach { c ->
                            FollowupRow(c, "今日", Warning) { onNav("${com.realtor.geeksales.navigation.Routes.CUSTOMER_DETAIL.replace("{id}", c.id.toString())}") }
                        }
                    }
                }
            }

            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("分层分布（S成交高价值 A高意向 B已接触 C信息完整 D线索 V已成交 U未分类）", color = Accent, style = MaterialTheme.typography.labelMedium)
                    IntentRow("S", intentMap["S"] ?: 0, IntentS, maxCount)
                    IntentRow("A", intentMap["A"] ?: 0, IntentA, maxCount)
                    IntentRow("B", intentMap["B"] ?: 0, IntentB, maxCount)
                    IntentRow("C", intentMap["C"] ?: 0, IntentC, maxCount)
                    IntentRow("D", intentMap["D"] ?: 0, IntentD, maxCount)
                    IntentRow("V", intentMap["V"] ?: 0, IntentV, maxCount)
                    IntentRow("U", intentMap["U"] ?: 0, IntentU, maxCount)
                }
            }

            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("快速入口", color = Accent, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeekGhostButton("客户列表", onClick = { onNav(Routes.CUSTOMER_LIST) }, modifier = Modifier.weight(1f), color = Accent)
                        GeekGhostButton("拨号队列", onClick = { onNav(Routes.DIAL_QUEUE) }, modifier = Modifier.weight(1f), color = Accent)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeekGhostButton("导入 / 导出", onClick = { onNav(Routes.IMPORT_EXPORT) }, modifier = Modifier.weight(1f), color = Accent)
                        GeekGhostButton("设置", onClick = { onNav(Routes.SETTINGS) }, modifier = Modifier.weight(1f), color = Accent)
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun FollowupRow(c: com.realtor.geeksales.data.db.Customer, tag: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {        Text(tag, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = TextPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (c.nextFollowAt != null) {
                Text("下次跟进 ${com.realtor.geeksales.util.Formatter.full(c.nextFollowAt)}", color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (!c.phone.isNullOrBlank()) {
            Text(c.phone, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun IntentRow(level: String, count: Int, color: Color, maxCount: Int) {
    val barWidth = (count.toFloat() / maxCount * 120f).coerceAtLeast(2f).dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(level, color = color, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(20.dp))
        Box(Modifier.height(12.dp).width(barWidth).background(color))
        Text("$count", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
    }
}
