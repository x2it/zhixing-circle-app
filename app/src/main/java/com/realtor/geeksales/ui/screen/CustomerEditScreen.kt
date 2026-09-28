@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.realtor.geeksales.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.data.schema.BuiltinKeys
import com.realtor.geeksales.data.schema.FieldDef
import com.realtor.geeksales.ui.components.GeekGhostButton
import com.realtor.geeksales.ui.components.GeekPrimaryButton
import com.realtor.geeksales.ui.components.GeekTextField
import com.realtor.geeksales.ui.components.GeekTopBar
import com.realtor.geeksales.ui.components.intentColor
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.BgElev2
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.Divider
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.TextSecondary
import com.realtor.geeksales.ui.theme.Warning
import com.realtor.geeksales.util.Formatter
import com.realtor.geeksales.viewmodel.CustomerEditViewModel
import com.realtor.geeksales.viewmodel.EditFormState

@Composable
fun CustomerEditScreen(
    id: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: CustomerEditViewModel = hiltViewModel()
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val schema by vm.schema.collectAsStateWithLifecycle()
    val savedOk by vm.savedOk.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.initFor(id) }
    LaunchedEffect(savedOk) { if (savedOk) onSaved() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(
            title = if (form.id == 0L) "新建客户" else "编辑客户",
            subtitle = "字段跟随线上模板 · 必填：姓名 / 手机号",
            onBack = onBack,
            actions = { GeekPrimaryButton("保存", { vm.save() }) }
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 按 schema 分组渲染（组序 = 组内最小 order）
            val groups = remember(schema) {
                schema.groupBy { it.group }
                    .map { (g, fs) -> g to fs.sortedBy { it.order } }
                    .sortedBy { (_, fs) -> fs.firstOrNull()?.order ?: 0 }
            }
            groups.forEach { (groupName, fields) ->
                Section(groupName)
                fields.forEach { f ->
                    if (f.builtin) {
                        BuiltinField(f, form, vm)
                    } else {
                        ExtField(f, form.extFields[f.key].orEmpty(), { v -> vm.updateExt(f.key, v) })
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekPrimaryButton("保存并返回", { vm.save() }, Modifier.weight(1f))
                GeekGhostButton("取消", color = Danger, onClick = onBack)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 内置字段：key 对应 Customer 列，保留既有控件行为（等级 chips、日期解析、号码校验） */
@Composable
private fun BuiltinField(f: FieldDef, form: EditFormState, vm: CustomerEditViewModel) {
    when (f.key) {
        BuiltinKeys.NAME -> GeekTextField(
            form.name, { vm.update { s -> s.copy(name = it) } },
            label = "${f.label} *", placeholder = "例：张三"
        )
        BuiltinKeys.PHONE -> GeekTextField(
            form.phone, { vm.update { s -> s.copy(phone = it) } },
            label = "${f.label} *", placeholder = "13800138000",
            isError = form.phone.isNotBlank() && !Formatter.isValidCnPhone(form.phone)
        )
        BuiltinKeys.PHONE2 -> GeekTextField(form.phone2, { vm.update { s -> s.copy(phone2 = it) } }, label = f.label, placeholder = "座机/家人电话")
        BuiltinKeys.GENDER -> GeekTextField(form.gender, { vm.update { s -> s.copy(gender = it) } }, label = f.label, placeholder = "男/女")
        BuiltinKeys.AGE -> GeekTextField(form.age, { vm.update { s -> s.copy(age = it) } }, label = f.label, placeholder = "30")
        BuiltinKeys.WECHAT -> GeekTextField(form.wechat, { vm.update { s -> s.copy(wechat = it) } }, label = f.label)
        BuiltinKeys.SOURCE -> GeekTextField(form.source, { vm.update { s -> s.copy(source = it) } }, label = f.label, placeholder = "端口-安居客/朋友转介绍/到访…")
        BuiltinKeys.TAGS -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 模板标签选择器：身份标签 + 属性标签（线上模板下发，随模板自动适配）
            val meta = vm.templateMeta.collectAsState().value
            val templateTags = meta.allTags
            if (templateTags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    templateTags.forEach { tag ->
                        val selected = form.tags.split(',', '，').map { it.trim() }.contains(tag)
                        androidx.compose.runtime.key(tag) {
                            Text(
                                text = tag,
                                color = if (selected) Accent else TextSecondary,
                                modifier = Modifier
                                    .background(if (selected) Accent.copy(alpha = 0.16f) else BgElev2)
                                    .border(1.dp, if (selected) Accent else Divider)
                                    .clickable {
                                        val current = form.tags.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
                                        if (tag in current) current.remove(tag) else current.add(tag)
                                        vm.update { s -> s.copy(tags = current.joinToString(",")) }
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            GeekTextField(
                form.tags, { vm.update { s -> s.copy(tags = it) } },
                label = "${f.label} (逗号分隔，可自定义)", placeholder = "如：高意向, 自住, 复购"
            )
        }
        BuiltinKeys.EMAIL -> GeekTextField(form.email, { vm.update { s -> s.copy(email = it) } }, label = f.label, placeholder = "name@example.com")
        BuiltinKeys.IM -> GeekTextField(form.im, { vm.update { s -> s.copy(im = it) } }, label = f.label, placeholder = "微信/QQ")
        BuiltinKeys.COMPANY -> GeekTextField(form.company, { vm.update { s -> s.copy(company = it) } }, label = f.label, placeholder = "公司/门店")
        BuiltinKeys.JOB_TITLE -> GeekTextField(form.jobTitle, { vm.update { s -> s.copy(jobTitle = it) } }, label = f.label, placeholder = "职业")
        BuiltinKeys.BIRTHDAY -> GeekTextField(form.birthday, { vm.update { s -> s.copy(birthday = it) } }, label = f.label, placeholder = "1990-01-01")
        BuiltinKeys.NICKNAME -> GeekTextField(form.nickname, { vm.update { s -> s.copy(nickname = it) } }, label = f.label, placeholder = "别称")
        BuiltinKeys.ADDRESS -> GeekTextField(form.address, { vm.update { s -> s.copy(address = it) } }, label = f.label, placeholder = "常用地址")
        BuiltinKeys.WEBSITE -> GeekTextField(form.website, { vm.update { s -> s.copy(website = it) } }, label = f.label, placeholder = "https://…")
        BuiltinKeys.AREA_PREF -> GeekTextField(form.areaPref, { vm.update { s -> s.copy(areaPref = it) } }, label = f.label, placeholder = "朝阳国贸 | 通州副中心")
        BuiltinKeys.BUDGET_MIN -> GeekTextField(form.budgetMin, { vm.update { s -> s.copy(budgetMin = it) } }, label = f.label, placeholder = "400")
        BuiltinKeys.BUDGET_MAX -> GeekTextField(form.budgetMax, { vm.update { s -> s.copy(budgetMax = it) } }, label = f.label, placeholder = "600")
        BuiltinKeys.HOUSE_TYPE -> GeekTextField(form.houseType, { vm.update { s -> s.copy(houseType = it) } }, label = f.label, placeholder = "三居/叠拼/平层")
        BuiltinKeys.TARGET_PROJECT -> GeekTextField(form.targetProject, { vm.update { s -> s.copy(targetProject = it) } }, label = f.label)
        BuiltinKeys.INTENT_LEVEL -> {
            // 分层选项完全跟随模板（行业可换，不写死）：模板 tiers + tierLabels 动态渲染
            val meta = vm.templateMeta.collectAsState().value
            val tierToEnum = mapOf(
                "S" to IntentLevel.S, "A" to IntentLevel.A, "B" to IntentLevel.B,
                "C" to IntentLevel.C, "D" to IntentLevel.D, "V" to IntentLevel.V, "U" to IntentLevel.U
            )
            val levels = meta.tiers.mapNotNull { t -> tierToEnum[t] }
            // 自适应换行：窄屏/多层级自动折行，不固定每行数量
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                levels.forEach { lvl ->
                    val on = form.intentLevel == lvl
                    val lvlColor = intentColor(lvl)
                    androidx.compose.runtime.key(lvl) {
                        Text(
                            text = meta.tierLabel(lvl.name),
                            color = if (on) lvlColor else TextSecondary,
                            modifier = Modifier
                                .background(if (on) lvlColor.copy(alpha = 0.16f) else BgElev2)
                                .border(1.dp, if (on) lvlColor else Divider)
                                .clickable { vm.update { s -> s.copy(intentLevel = lvl) } }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        BuiltinKeys.NEXT_FOLLOW_AT -> {
            // 日期选择器 + 快捷调整：不再手动填日期（反人性），一键/选择快速设定
            var showDatePicker by remember { mutableStateOf(false) }
            val nextAt = form.nextFollowAt
            val nextLabel = if (nextAt == null) "未设置" else Formatter.day(nextAt)
            val shortcuts = mapOf(
                "今天" to 0L, "明天" to 1L, "一周后" to 7L, "两周后" to 14L, "一个月后" to 30L
            )
            val dayMs = 24L * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            fun pickDays(days: Long) {
                val t = java.util.Calendar.getInstance().apply {
                    timeInMillis = now
                    add(java.util.Calendar.DAY_OF_YEAR, days.toInt())
                }.apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
                vm.update { f -> f.copy(nextFollowAt = t) }
            }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                shortcuts.forEach { (name, d) ->
                    val on = nextAt != null && runCatching { java.util.Calendar.getInstance().apply { timeInMillis = nextAt!!; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis == runCatching { java.util.Calendar.getInstance().apply { timeInMillis = now; add(java.util.Calendar.DAY_OF_YEAR, d.toInt()); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis }.getOrNull() }.getOrDefault(false)
                    Text(
                        text = name,
                        color = if (on) Accent else TextSecondary,
                        modifier = Modifier
                            .background(if (on) Accent.copy(alpha = 0.16f) else BgElev2)
                            .border(1.dp, if (on) Accent else Divider)
                            .clickable { pickDays(d) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("下次跟进", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Text(nextLabel, color = if (nextAt != null && nextAt < now) Warning else TextPrimary,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                GeekGhostButton("选择日期", color = Accent, onClick = { showDatePicker = true })
                if (nextAt != null) {
                    GeekGhostButton("清除", color = TextMuted, onClick = { vm.update { f -> f.copy(nextFollowAt = null) } })
                }
            }
            if (showDatePicker) {
                val dpState = androidx.compose.material3.rememberDatePickerState(
                    initialSelectedDateMillis = nextAt?.let { Formatter.dayToEpoch(Formatter.day(it)) } ?: now,
                    initialDisplayedMonthMillis = nextAt ?: now
                )
                androidx.compose.material3.DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            dpState.selectedDateMillis?.let { ms ->
                                vm.update { f -> f.copy(nextFollowAt = ms) }
                            }
                            showDatePicker = false
                        }) { Text("确定") }
                    },
                    dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
                ) {
                    androidx.compose.material3.DatePicker(
                        state = dpState,
                        showModeToggle = false
                    )
                }
            }
        }
        BuiltinKeys.NOTE -> GeekTextField(
            form.note, { vm.update { s -> s.copy(note = it) } },
            label = f.label, placeholder = "第一次沟通重点、家人意见…", singleLine = false, minLines = 3
        )
    }
}

/** 扩展字段：线上模板/本地自定义字段，按类型动态渲染（text/number/tel/date/select/multiselect/textarea） */
@Composable
private fun ExtField(f: FieldDef, value: String, onChange: (String) -> Unit) {
    when (f.type) {
        "select" -> {
            Text(f.label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                f.options.forEach { opt ->
                    val on = value == opt
                    Text(
                        text = if (opt == "U") "/" else opt,
                        color = if (on) Accent else TextSecondary,
                        modifier = Modifier
                            .background(if (on) Accent.copy(alpha = 0.16f) else BgElev2)
                            .border(1.dp, if (on) Accent else Divider)
                            .clickable { onChange(if (on) "" else opt) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
        "multiselect" -> {
            Text(f.label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            val selected = value.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                f.options.forEach { opt ->
                    val on = opt in selected
                    Text(
                        text = if (opt == "U") "/" else opt,
                        color = if (on) Accent else TextSecondary,
                        modifier = Modifier
                            .background(if (on) Accent.copy(alpha = 0.16f) else BgElev2)
                            .border(1.dp, if (on) Accent else Divider)
                            .clickable {
                                val next = if (on) selected - opt else selected + opt
                                onChange(next.sorted().joinToString(","))
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
        "textarea" -> GeekTextField(
            value, onChange,
            label = f.label, placeholder = "填写 ${f.label}…", singleLine = false, minLines = 3
        )
        "date" -> GeekTextField(value, onChange, label = "${f.label} (yyyy-MM-dd)", placeholder = "2025-08-30")
        else -> GeekTextField(value, onChange, label = f.label)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(4.dp))
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Accent, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Divider)
        )
    }
}
