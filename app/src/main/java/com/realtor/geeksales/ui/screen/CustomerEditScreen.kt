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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.realtor.geeksales.ui.theme.TextSecondary
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
        BuiltinKeys.TAGS -> GeekTextField(form.tags, { vm.update { s -> s.copy(tags = it) } }, label = "${f.label} (逗号分隔)", placeholder = "学区房, 地铁, 急售")
        BuiltinKeys.EMAIL -> GeekTextField(form.email, { vm.update { s -> s.copy(email = it) } }, label = f.label, placeholder = "name@example.com")
        BuiltinKeys.IM -> GeekTextField(form.im, { vm.update { s -> s.copy(im = it) } }, label = f.label, placeholder = "微信/QQ")
        BuiltinKeys.COMPANY -> GeekTextField(form.company, { vm.update { s -> s.copy(company = it) } }, label = f.label, placeholder = "公司/门店")
        BuiltinKeys.JOB_TITLE -> GeekTextField(form.jobTitle, { vm.update { s -> s.copy(jobTitle = it) } }, label = f.label, placeholder = "职业")
        BuiltinKeys.BIRTHDAY -> GeekTextField(form.birthday, { vm.update { s -> s.copy(birthday = it) } }, label = f.label, placeholder = "1990-01-01")
        BuiltinKeys.NICKNAME -> GeekTextField(form.nickname, { vm.update { s -> s.copy(nickname = it) } }, label = f.label, placeholder = "别称")
        BuiltinKeys.ADDRESS -> GeekTextField(form.address, { vm.update { s -> s.copy(address = it) } }, label = f.label, placeholder = "常用地址")
        BuiltinKeys.WEBSITE -> GeekTextField(form.website, { vm.update { s -> s.copy(website = it) } }, label = f.label, placeholder = "https://…")
        BuiltinKeys.AREA_PREF -> GeekTextField(form.areaPref, { vm.update { s -> s.copy(areaPref = it) } }, label = f.label, placeholder = "朝阳国贸 | 通州副中心")
        BuiltinKeys.BUDGET_MIN -> GeekTextField(form.budgetMin, { vm.update { s -> s.copy(budgetMin = it) } }, label = "${f.label}(万)", placeholder = "400")
        BuiltinKeys.BUDGET_MAX -> GeekTextField(form.budgetMax, { vm.update { s -> s.copy(budgetMax = it) } }, label = "${f.label}(万)", placeholder = "600")
        BuiltinKeys.HOUSE_TYPE -> GeekTextField(form.houseType, { vm.update { s -> s.copy(houseType = it) } }, label = f.label, placeholder = "三居/叠拼/平层")
        BuiltinKeys.TARGET_PROJECT -> GeekTextField(form.targetProject, { vm.update { s -> s.copy(targetProject = it) } }, label = f.label)
        BuiltinKeys.INTENT_LEVEL -> {
            val labels = mapOf(
                IntentLevel.A to "A 强烈", IntentLevel.B to "B 一般", IntentLevel.C to "C 弱",
                IntentLevel.D to "D 无效", IntentLevel.U to "U 未评"
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(IntentLevel.A, IntentLevel.B, IntentLevel.C, IntentLevel.D, IntentLevel.U).forEach { lvl ->
                    val on = form.intentLevel == lvl
                    val lvlColor = intentColor(lvl)
                    androidx.compose.runtime.key(lvl) {
                        Text(
                            text = labels[lvl] ?: lvl.name,
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
            var nextStr by remember {
                mutableStateOf(if (form.nextFollowAt == null) "" else Formatter.day(form.nextFollowAt))
            }
            LaunchedEffect(form.nextFollowAt) {
                nextStr = if (form.nextFollowAt == null) "" else Formatter.day(form.nextFollowAt)
            }
            GeekTextField(nextStr, { s ->
                nextStr = s
                val t = runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(s.trim())?.time
                }.getOrNull()
                vm.update { f -> f.copy(nextFollowAt = t) }
            }, label = "${f.label} (yyyy-MM-dd)", placeholder = "2025-08-30")
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.options.forEach { opt ->
                    val on = value == opt
                    Text(
                        text = opt,
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.options.forEach { opt ->
                    val on = opt in selected
                    Text(
                        text = opt,
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
