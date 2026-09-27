package com.realtor.geeksales.ui.screen

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import com.realtor.geeksales.ui.components.GeekCard
import com.realtor.geeksales.ui.components.GeekGhostButton
import com.realtor.geeksales.ui.components.GeekPrimaryButton
import com.realtor.geeksales.ui.components.GeekTopBar
import com.realtor.geeksales.ui.components.LoadingState
import com.realtor.geeksales.ui.components.GlobalToast
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.Success
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.Warning
import com.realtor.geeksales.viewmodel.ImportExportViewModel

@Composable
fun ImportExportScreen(
    onBack: () -> Unit,
    vm: ImportExportViewModel = hiltViewModel()
) {
    val status by vm.status.collectAsStateWithLifecycle()
    // 记录已提示过的消息，避免历史成功/失败消息在每次进入页面时重复弹 Toast
    var lastNotified by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(status) {
        val m = status.message
        if (m.isNotEmpty() && m != lastNotified) {
            when {
                !status.running && m.contains("完成") -> { GlobalToast.showSuccess(m); lastNotified = m }
                !status.running && m.startsWith("失败") -> { GlobalToast.showError(m); lastNotified = m }
            }
        }
    }
    var exportPending by remember { mutableStateOf(false) }

    val pickCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.importCsv(u)
    }
    val pickXlsx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.importXlsx(u)
    }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u: Uri? ->
        if (u != null) vm.exportCsv(u)
    }
    val createXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { u: Uri? ->
        if (u != null) vm.exportXlsx(u)
    }
    val createTemplate = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { u: Uri? ->
        if (u != null) vm.templateXlsx(u)
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (exportPending) vm.exportContacts() else vm.importContacts()
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(title = "数据", subtitle = "导入 · 导出 · 模板", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (status.running) {
                GeekCard(Modifier.fillMaxWidth()) {
                    LoadingState(message = status.message)
                }
            }
            // 操作进行中：其余区域整体不可点（防狂点导致并发导入/锁竞争）
            val busy = status.running

            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导入数据", color = Accent, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeekPrimaryButton("导入 CSV", { if (!busy) pickCsv.launch(arrayOf("text/*", "text/csv", "application/csv")) }, Modifier.weight(1f), enabled = !busy)
                        GeekPrimaryButton("导入 XLSX", { if (!busy) pickXlsx.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) }, Modifier.weight(1f), enabled = !busy)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeekGhostButton(if (busy) "处理中…" else "从通讯录导入", color = if (busy) TextMuted else Accent, onClick = {
                            if (busy) return@GeekGhostButton
                            exportPending = false
                            permLauncher.launch(Manifest.permission.READ_CONTACTS)
                        })
                    }
                }
            }
            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导出与模板", color = Accent, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeekGhostButton("导出 CSV", color = if (busy) TextMuted else Success, onClick = { if (!busy) createCsv.launch("tma_customers.csv") })
                        GeekGhostButton("导出 XLSX", color = if (busy) TextMuted else Success, onClick = { if (!busy) createXlsx.launch("tma_customers.xlsx") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeekGhostButton(if (busy) "处理中…" else "导出到通讯录", color = if (busy) TextMuted else Accent, onClick = {
                            if (busy) return@GeekGhostButton
                            exportPending = true
                            permLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                        })
                        GeekGhostButton("下载导入模板", color = if (busy) TextMuted else Warning, onClick = { if (!busy) createTemplate.launch("tma_template.xlsx") })
                    }
                }
            }
            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("执行状态", color = Accent, style = MaterialTheme.typography.labelMedium)
                    val c = when {
                        status.running -> Warning
                        status.message.startsWith("失败") -> Danger
                        else -> Success
                    }
                    if (status.message.isNotEmpty()) {
                        Text(status.message, color = c, style = MaterialTheme.typography.bodyMedium)
                    }
                    status.report?.let { r ->
                        if (r.error == null) {
                            Spacer(Modifier.height(4.dp))
                            Text("总计：${r.total}", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text("成功：${r.success}", color = Success, style = MaterialTheme.typography.bodyMedium)
                            Text("重复跳过：${r.duplicated}", color = Warning, style = MaterialTheme.typography.bodyMedium)
                            Text("无效号码：${r.invalid}", color = Danger, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    status.exportCount?.let { n ->
                        Spacer(Modifier.height(4.dp))
                        Text("已导出：$n 条", color = Success, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("危险操作", color = Danger, style = MaterialTheme.typography.labelMedium)
                    Text("清空所有客户数据，不可恢复，建议先导出备份。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    GeekGhostButton(if (busy) "处理中…" else "清空全部数据", color = if (busy) TextMuted else Danger, onClick = { if (!busy) vm.clearAll() })
                }
            }
            GeekCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("字段说明", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "列顺序(CSV/XLSX)：姓名、手机号、备用电话、性别、年龄、微信、来源、意向区域、预算下限(万)、预算上限(万)、房型、意向楼盘、意向等级(A/B/C/D/U)、备注、下次跟进(YYYY-MM-DD)、邮箱、公司、职位、地址、昵称、网站、生日(YYYY-MM-DD)、即时消息。扩展列可选，第一行为表头(中文)。重复手机号自动去重。",
                        color = TextPrimary, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}