package com.realtor.geeksales.ui.screen

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.realtor.geeksales.ui.theme.BgElev
import com.realtor.geeksales.ui.theme.BgElev2
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.Divider
import com.realtor.geeksales.ui.theme.Success
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.TextSecondary
import com.realtor.geeksales.ui.theme.Warning
import com.realtor.geeksales.viewmodel.ImportExportViewModel
import com.realtor.geeksales.viewmodel.SyncMode

@Composable
fun ImportExportScreen(
    onBack: () -> Unit,
    vm: ImportExportViewModel = hiltViewModel()
) {
    val status by vm.status.collectAsStateWithLifecycle()
    var lastNotified by remember { mutableStateOf("") }
    LaunchedEffect(status) {
        val m = status.message
        if (m.isNotEmpty() && m != lastNotified) {
            when {
                !status.running && status.isError -> { GlobalToast.showError(m); lastNotified = m }
                !status.running && m.contains("完成") -> { GlobalToast.showSuccess(m); lastNotified = m }
                !status.running && m.startsWith("失败") -> { GlobalToast.showError(m); lastNotified = m }
            }
        }
    }
    var exportPending by remember { mutableStateOf(false) }
    var groupPending by remember { mutableStateOf(false) }
    // API Key 输入状态
    var apiKeyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    val hasKey by remember { mutableStateOf(vm.hasApiKey()) }
    var mode by remember { mutableStateOf(vm.syncMode()) }
    var baseUrlInput by remember { mutableStateOf(vm.baseUrl()) }
    // 自定义字段输入状态
    var newFieldKey by remember { mutableStateOf("") }
    var newFieldLabel by remember { mutableStateOf("") }
    var newFieldType by remember { mutableStateOf("text") }
    var newFieldOptions by remember { mutableStateOf("") }
    var schemaVersion by remember { mutableStateOf(0) }
    val schema = remember(schemaVersion) { vm.currentSchema() }

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
    // 时光机恢复文件选择
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.restoreFrom(u)
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when {
                exportPending -> vm.exportContacts()
                groupPending -> vm.syncGroupsFromTags()
                else -> vm.importContacts()
            }
        }
    }
    // 短信动作标记（备份=1 / 同步云端=2），权限授权后执行对应动作
    val ctx = LocalContext.current
    var smsAction by remember { mutableStateOf(0) }
    // 短信权限（备份 / 云端同步）
    val smsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when {
                smsAction == 1 -> vm.backupSms()
                smsAction == 2 -> vm.exportSmsToWorkbuddy()
            }
        } else {
            GlobalToast.showError("未授予「短信」权限：备份与云端同步无法执行。请在 系统设置 → 应用 → TMA → 权限 → 短信 中开启后重试")
        }
    }
    // 通话动作标记（同步云端=1 / 仅拉取=2），权限授权后执行（通话同步需要读本机通话记录）
    var callAction by remember { mutableStateOf(0) }
    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when {
                callAction == 1 -> vm.syncCallsToWorkbuddy()
                callAction == 2 -> vm.pullCallsFromWorkbuddy()
            }
        } else {
            GlobalToast.showError("未授予「通话记录」权限：云端备份与拉取无法执行。请在 系统设置 → 应用 → TMA → 权限 → 电话/通话记录 中开启后重试")
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(title = "数据", subtitle = "导入 · 导出 · 知行同步 · 时光机", onBack = onBack)
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
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoadingState(message = status.message)
                        // 确定性进度（同步/导入导出/快照逐步上报 0..1）
                        val p = status.progress
                        if (p != null) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { p },
                                modifier = Modifier.fillMaxWidth(),
                                color = Accent,
                                trackColor = Divider
                            )
                            Text(
                                "进度 ${(p * 100).toInt()}%",
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
            val busy = status.running

            // ================= 知行朋友圈 · 云端同步 =================
            SectionCard("知行朋友圈 · 云端同步", "联系人 / 跟进 / 短信 双向同步 · 自动备份") {
                // 服务器地址（可切换，平台迁移/关停时更换）
                Text("服务器地址（平台迁移时可更换）", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(com.realtor.geeksales.data.remote.WorkbuddyApi.DEFAULT_BASE_URL, color = TextMuted, style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Divider,
                            focusedContainerColor = BgElev2, unfocusedContainerColor = BgElev,
                            cursorColor = Accent,
                            unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
                            unfocusedLabelColor = TextSecondary, focusedLabelColor = Accent
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    GeekGhostButton("保存", color = if (busy) TextMuted else Accent, onClick = {
                        if (busy || baseUrlInput.isBlank()) return@GeekGhostButton
                        vm.setBaseUrl(baseUrlInput)
                        GlobalToast.showSuccess("服务器地址已更新")
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("地址应为 域名 + /api（如 https://people.app.workbuddy.host/api）；保存时自动补 /api 并清理多余字符", color = TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    GeekGhostButton("恢复默认", color = if (busy) TextMuted else Warning, onClick = {
                        if (busy) return@GeekGhostButton
                        vm.resetBaseUrl()
                        GlobalToast.showSuccess("已恢复默认地址 ${com.realtor.geeksales.data.remote.WorkbuddyApi.DEFAULT_BASE_URL}")
                    })
                }
                Spacer(Modifier.height(4.dp))
                // API Key 配置
                Text("API Key（在知行朋友圈「API 接入」页生成）", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (hasKey) "已配置（重新输入可覆盖）" else "zx_ 开头的 35 位密钥", color = TextMuted, style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Divider,
                            focusedContainerColor = BgElev2, unfocusedContainerColor = BgElev,
                            cursorColor = Accent,
                            unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
                            unfocusedLabelColor = TextSecondary, focusedLabelColor = Accent
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    GeekGhostButton(if (keyVisible) "隐藏" else "显示", onClick = { keyVisible = !keyVisible }, color = TextMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekGhostButton("保存密钥", color = if (busy) TextMuted else Accent, onClick = {
                        if (busy || apiKeyInput.isBlank()) return@GeekGhostButton
                        val ok = vm.saveApiKey(apiKeyInput)
                        if (ok) { GlobalToast.showSuccess("API Key 已保存（加密存储）"); apiKeyInput = "" } else GlobalToast.showError("密钥保存失败")
                    })
                    if (hasKey) {
                        GeekGhostButton("清除密钥", color = if (busy) TextMuted else Danger, onClick = { if (!busy) { vm.clearApiKey(); GlobalToast.showSuccess("密钥已清除") } })
                    }
                }
                // 同步模式
                Spacer(Modifier.height(4.dp))
                Text("同步冲突策略", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                SyncModeSelector(mode = mode, enabled = !busy, onSelect = { mode = it; vm.setSyncMode(it) })
                Text(
                    text = when (mode) {
                        SyncMode.SMART -> "推荐：两端改动都保留，同一字段冲突时以本地最新为准；绝不删除任何一端数据。"
                        SyncMode.CLOUD_FIRST -> "以知行朋友圈为准覆盖本地：适合把网站当主工作台、手机当客户端。"
                        SyncMode.LOCAL_FIRST -> "以本地为准覆盖线上：适合本地深度操作、线上纯备份。"
                    },
                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekPrimaryButton(if (busy) "处理中…" else "备份并导入", { if (!busy) vm.importFromWorkbuddy() }, Modifier.weight(1f), enabled = !busy)
                    GeekGhostButton(if (busy) "处理中…" else "备份并导出", color = if (busy) TextMuted else Success, onClick = { if (!busy) vm.exportToWorkbuddy() })
                }
                Text("每次同步前自动全量备份到「下载/TMA备份」；导入默认不删除本地数据。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                Text("⚠ API Key 关联你的知行朋友圈账号：一人一账号一密钥，请勿与他人共用，否则云端数据会混淆。", color = Warning, style = MaterialTheme.typography.bodyMedium)
            }

            // ================= 线上模板 · 万物可插 =================
            SectionCard("线上模板 · 字段可插", "以线上模板为准：拉取字段定义，App 表单/详情/导入导出自动跟随；本地可自定义字段并推送线上") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekPrimaryButton(if (busy) "处理中…" else "拉取线上模板", { if (!busy) { vm.pullSchema(); schemaVersion++ } }, Modifier.weight(1f), enabled = !busy)
                    Text("当前 ${schema.size} 个字段（内置 ${schema.count { it.builtin }} · 扩展 ${schema.count { !it.builtin }}）", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
                }
                if (schema.any { !it.builtin }) {
                    Text("扩展字段：", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    schema.filter { !it.builtin }.forEach { fd ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${fd.label}（${fd.key}·${fd.type}）", color = TextPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            GeekGhostButton("移除", color = Danger, onClick = { if (!busy) { vm.removeCustomField(fd.key); schemaVersion++ } })
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text("添加自定义字段（同步到线上模板）", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = newFieldKey,
                    onValueChange = { newFieldKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("字段 Key（英文小写，如 scriptVersion）", color = TextMuted, style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Divider,
                        focusedContainerColor = BgElev2, unfocusedContainerColor = BgElev,
                        cursorColor = Accent,
                        unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary, focusedLabelColor = Accent
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                androidx.compose.material3.OutlinedTextField(
                    value = newFieldLabel,
                    onValueChange = { newFieldLabel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("显示名（如 话术版本）", color = TextMuted, style = MaterialTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Divider,
                        focusedContainerColor = BgElev2, unfocusedContainerColor = BgElev,
                        cursorColor = Accent,
                        unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary, focusedLabelColor = Accent
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("text" to "文本", "number" to "数字", "date" to "日期", "select" to "单选", "multiselect" to "多选", "textarea" to "长文本").forEach { (t, n) ->
                        val on = newFieldType == t
                        Text(
                            text = n,
                            color = if (on) Accent else TextSecondary,
                            modifier = Modifier
                                .background(if (on) Accent.copy(alpha = 0.16f) else BgElev2)
                                .border(1.dp, if (on) Accent else Divider)
                                .clickable { newFieldType = t }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
                if (newFieldType == "select" || newFieldType == "multiselect") {
                    androidx.compose.material3.OutlinedTextField(
                        value = newFieldOptions,
                        onValueChange = { newFieldOptions = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("选项（逗号分隔，如 新客,老客,转介绍）", color = TextMuted, style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Divider,
                            focusedContainerColor = BgElev2, unfocusedContainerColor = BgElev,
                            cursorColor = Accent,
                            unfocusedTextColor = TextPrimary, focusedTextColor = TextPrimary,
                            unfocusedLabelColor = TextSecondary, focusedLabelColor = Accent
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
                GeekGhostButton("添加字段", color = if (busy) TextMuted else Accent, onClick = {
                    if (busy) return@GeekGhostButton
                    vm.addCustomField(
                        newFieldKey,
                        newFieldLabel,
                        newFieldType,
                        newFieldOptions.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }
                    )
                    schemaVersion++
                    newFieldKey = ""; newFieldLabel = ""; newFieldOptions = ""
                })
                Text("拉取线上模板后，表单/详情/导入导出表头自动跟随线上字段；自定义字段同步到线上模板，全网生效。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }

            // ================= 时光机 =================
            SectionCard("时光机 · 数据回溯", "客户 / 跟进 / 标签 / 短信 全量快照与恢复") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekPrimaryButton(if (busy) "处理中…" else "立即快照", { if (!busy) vm.snapshotNow() }, Modifier.weight(1f), enabled = !busy)
                    GeekGhostButton(if (busy) "处理中…" else "从备份恢复", color = if (busy) TextMuted else Warning, onClick = { if (!busy) pickRestore.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/*")) })
                }
                Text("快照 = 含客户/跟进/标签/短信的完整 XLSX；恢复为覆盖模式，恢复前会自动再备份当前状态（双保险）。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }

            // ================= 短信备份与同步 =================
            SectionCard("短信备份与同步", "本地增量备份 · 云端双向同步（需 READ_SMS 权限）") {
                val smsGranted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GeekPrimaryButton(if (busy) "处理中…" else "备份短信(CSV)", { if (!busy) { smsAction = 1; smsPermLauncher.launch(Manifest.permission.READ_SMS) } }, Modifier.weight(1f), enabled = !busy)
                    GeekGhostButton(if (busy) "处理中…" else "同步到云端", color = if (busy) TextMuted else Success, onClick = { if (!busy) { smsAction = 2; smsPermLauncher.launch(Manifest.permission.READ_SMS) } })
                }
                Text(
                    if (smsGranted) "短信权限：已授权" else "短信权限：未授权（点击上方按钮会请求授权；拒绝后需到系统设置开启）",
                    color = if (smsGranted) Success else Danger, style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekGhostButton(if (busy) "处理中…" else "从云端拉取", color = if (busy) TextMuted else Accent, onClick = { if (!busy) vm.importSmsFromWorkbuddy() })
                    Spacer(Modifier.weight(1f))
                }
                Text("短信为最敏感数据：默认仅本地增量备份（下载/TMA备份）；「同步到云端」仅在您主动点击时执行，需知行朋友圈已开放短信接口。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }

            // ================= 通话记录备份与同步 =================
            SectionCard("通话记录备份与同步", "本机镜像 · 云端双向同步（需 READ_CALL_LOG 权限）") {
                val callGranted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekPrimaryButton(if (busy) "处理中…" else "备份+同步到云端", {
                        if (!busy) { callAction = 1; callPermLauncher.launch(Manifest.permission.READ_CALL_LOG) }
                    }, Modifier.weight(1f), enabled = !busy)
                    GeekGhostButton(if (busy) "处理中…" else "从云端拉取", color = if (busy) TextMuted else Accent, onClick = {
                        if (!busy) { callAction = 2; callPermLauncher.launch(Manifest.permission.READ_CALL_LOG) }
                    })
                }
                Text(
                    if (callGranted) "通话记录权限：已授权" else "通话记录权限：未授权（点击上方按钮会请求授权；拒绝后需到系统设置开启）",
                    color = if (callGranted) Success else Danger, style = MaterialTheme.typography.labelMedium
                )
                Text("通话记录同步 = 云端通话备份：开启后本机通话（呼入/呼出/未接+时长）自动镜像并上传，客户详情「互动档案」可查看。云端开关默认关闭，首次同步会自动为你开启。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }

            // ================= 导入数据 =================
            SectionCard("导入数据", "CSV / XLSX / 系统通讯录") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekPrimaryButton("导入 CSV", { if (!busy) pickCsv.launch(arrayOf("text/*", "text/csv", "application/csv")) }, Modifier.weight(1f), enabled = !busy)
                    GeekPrimaryButton("导入 XLSX", { if (!busy) pickXlsx.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) }, Modifier.weight(1f), enabled = !busy)
                }
                GeekGhostButton(if (busy) "处理中…" else "从通讯录导入", color = if (busy) TextMuted else Accent, onClick = {
                    if (busy) return@GeekGhostButton
                    exportPending = false
                    permLauncher.launch(Manifest.permission.READ_CONTACTS)
                })
            }

            // ================= 导出与模板 =================
            SectionCard("导出与模板", "CSV / XLSX / 系统通讯录 / 模板") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekGhostButton("导出 CSV", color = if (busy) TextMuted else Success, onClick = { if (!busy) createCsv.launch("tma_customers.csv") })
                    GeekGhostButton("导出 XLSX", color = if (busy) TextMuted else Success, onClick = { if (!busy) createXlsx.launch("tma_customers.xlsx") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekGhostButton(if (busy) "处理中…" else "导出到通讯录", color = if (busy) TextMuted else Accent, onClick = {
                        if (busy) return@GeekGhostButton
                        exportPending = true
                        groupPending = false
                        permLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                    })
                    GeekGhostButton("下载导入模板", color = if (busy) TextMuted else Warning, onClick = { if (!busy) createTemplate.launch("tma_template.xlsx") })
                }
                // 标签 → 通讯录分组（云端 v2.0：按标签建系统分组，便于圈选群发）
                GeekGhostButton(if (busy) "处理中…" else "标签 → 通讯录分组", color = if (busy) TextMuted else Accent, onClick = {
                    if (busy) return@GeekGhostButton
                    exportPending = false
                    groupPending = true
                    permLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                })
            }

            // ================= 执行状态 =================
            SectionCard("执行状态", null) {
                val c = when {
                    status.running -> Warning
                    status.isError -> Danger
                    status.message.startsWith("失败") -> Danger
                    else -> Success
                }
                if (status.message.isNotEmpty()) {
                    Text(status.message, color = c, style = MaterialTheme.typography.bodyMedium)
                }
                // 执行状态卡同步展示确定性进度（与顶部卡片一致）
                val p = status.progress
                if (status.running && p != null) {
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth(),
                        color = Accent,
                        trackColor = Divider
                    )
                    Text(
                        "进度 ${(p * 100).toInt()}%",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.End)
                    )
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
                status.syncSummary?.let { s ->
                    Spacer(Modifier.height(4.dp))
                    Text(s, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // ================= 危险操作 =================
            SectionCard("危险操作", null) {
                Text("清空所有客户数据，不可恢复，建议先导出备份。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                GeekGhostButton(if (busy) "处理中…" else "清空全部数据", color = if (busy) TextMuted else Danger, onClick = { if (!busy) vm.clearAll() })
            }

            // ================= 字段说明（跟随当前行业模板，不写死） =================
            SectionCard("字段说明", "列顺序跟随当前模板；换行业后拉取新模板即可更新") {
                val fields = vm.currentSchema().sortedBy { it.order }
                val baseNames = fields.filter { it.builtin }.map { it.label }
                val extNames = fields.filter { !it.builtin }.map { it.label }
                val tierMeta = vm.meta()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "CSV/XLSX 内置列（当前模板）：${baseNames.joinToString("、")}",
                        color = TextPrimary, style = MaterialTheme.typography.bodyMedium
                    )
                    if (extNames.isNotEmpty()) {
                        Text("扩展列（模板自定义）：${extNames.joinToString("、")}", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "分层选项：${tierMeta.tiers.joinToString(" ") { tierMeta.tierLabel(it) }}（跟随模板）",
                        color = TextMuted, style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "第一行为表头（中文）；手机号必填，重复自动去重；扩展列可选。",
                        color = TextMuted, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/** 分区卡片：左侧强调条 + 标题 + 副标题，数据页统一视觉 */
@Composable
private fun SectionCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    GeekCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 3.dp, height = 14.dp).background(Accent))
                Spacer(Modifier.padding(start = 6.dp))
                Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            if (subtitle != null) {
                Text(subtitle, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
            content()
        }
    }
}

/** 同步模式三选一 */
@Composable
private fun SyncModeSelector(mode: SyncMode, enabled: Boolean, onSelect: (SyncMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SyncMode.entries.forEach { m ->
            val selected = mode == m
            val color = when (m) {
                SyncMode.SMART -> Accent
                SyncMode.CLOUD_FIRST -> Warning
                SyncMode.LOCAL_FIRST -> Success
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (enabled) Modifier.clickable { onSelect(m) } else Modifier)
                    .background(if (selected) color.copy(alpha = 0.14f) else BgElev2)
                    .border(1.dp, if (selected) color else Divider)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(m.label, color = if (selected) color else TextSecondary, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
