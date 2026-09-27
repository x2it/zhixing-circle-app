package com.realtor.geeksales.ui.screen

import androidx.compose.foundation.background
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
import com.realtor.geeksales.ui.theme.IntentU
import com.realtor.geeksales.ui.theme.Success
import com.realtor.geeksales.ui.theme.TextPrimary
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

            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("意向分布", color = Accent, style = MaterialTheme.typography.labelMedium)
                    IntentRow("A", intentMap["A"] ?: 0, IntentA, maxCount)
                    IntentRow("B", intentMap["B"] ?: 0, IntentB, maxCount)
                    IntentRow("C", intentMap["C"] ?: 0, IntentC, maxCount)
                    IntentRow("D", intentMap["D"] ?: 0, IntentD, maxCount)
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
private fun IntentRow(level: String, count: Int, color: Color, maxCount: Int) {
    val barWidth = (count.toFloat() / maxCount * 120f).coerceAtLeast(2f).dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(level, color = color, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(20.dp))
        Box(Modifier.height(12.dp).width(barWidth).background(color))
        Text("$count", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
    }
}
