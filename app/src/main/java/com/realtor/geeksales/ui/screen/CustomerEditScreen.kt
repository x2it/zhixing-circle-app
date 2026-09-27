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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.ui.components.GeekGhostButton
import com.realtor.geeksales.ui.components.GeekPrimaryButton
import com.realtor.geeksales.ui.components.GeekTextField
import com.realtor.geeksales.ui.components.GeekTopBar
import com.realtor.geeksales.ui.components.IntentLevelChip
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.TextSecondary
import com.realtor.geeksales.util.Formatter
import com.realtor.geeksales.viewmodel.CustomerEditViewModel

@Composable
fun CustomerEditScreen(
    id: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: CustomerEditViewModel = hiltViewModel()
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val savedOk by vm.savedOk.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.initFor(id) }
    LaunchedEffect(savedOk) { if (savedOk) onSaved() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(
            title = if (form.id == 0L) "新建客户" else "编辑客户",
            subtitle = "必填：姓名 / 手机号",
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
            Section("基础信息")
            GeekTextField(form.name, { vm.update { f -> f.copy(name = it) } }, label = "姓名 *", placeholder = "例：张三")
            GeekTextField(form.phone, { vm.update { f -> f.copy(phone = it) } }, label = "手机号 *", placeholder = "13800138000", isError = form.phone.isNotBlank() && !Formatter.isValidCnPhone(form.phone))
            GeekTextField(form.phone2, { vm.update { f -> f.copy(phone2 = it) } }, label = "备用电话", placeholder = "座机/家人电话")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekTextField(form.gender, { vm.update { f -> f.copy(gender = it) } }, label = "性别", modifier = Modifier.weight(1f), placeholder = "男/女")
                GeekTextField(form.age, { vm.update { f -> f.copy(age = it) } }, label = "年龄", modifier = Modifier.weight(1f), placeholder = "30")
            }
            GeekTextField(form.wechat, { vm.update { f -> f.copy(wechat = it) } }, label = "微信")
            GeekTextField(form.source, { vm.update { f -> f.copy(source = it) } }, label = "来源", placeholder = "端口-安居客/朋友转介绍/到访…")
            GeekTextField(form.tags, { vm.update { f -> f.copy(tags = it) } }, label = "标签 (逗号分隔)", placeholder = "学区房, 地铁, 急售")

            Section("联系信息")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekTextField(form.email, { vm.update { f -> f.copy(email = it) } }, label = "邮箱", modifier = Modifier.weight(1f), placeholder = "name@example.com")
                GeekTextField(form.im, { vm.update { f -> f.copy(im = it) } }, label = "即时消息", modifier = Modifier.weight(1f), placeholder = "微信/QQ")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekTextField(form.company, { vm.update { f -> f.copy(company = it) } }, label = "公司", modifier = Modifier.weight(1f), placeholder = "公司/门店")
                GeekTextField(form.jobTitle, { vm.update { f -> f.copy(jobTitle = it) } }, label = "职位", modifier = Modifier.weight(1f), placeholder = "职业")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekTextField(form.birthday, { vm.update { f -> f.copy(birthday = it) } }, label = "生日", modifier = Modifier.weight(1f), placeholder = "1990-01-01")
                GeekTextField(form.nickname, { vm.update { f -> f.copy(nickname = it) } }, label = "昵称", modifier = Modifier.weight(1f), placeholder = "别称")
            }
            GeekTextField(form.address, { vm.update { f -> f.copy(address = it) } }, label = "地址", placeholder = "常用地址")
            GeekTextField(form.website, { vm.update { f -> f.copy(website = it) } }, label = "网站", placeholder = "https://…")

            Section("购房需求")
            GeekTextField(form.areaPref, { vm.update { f -> f.copy(areaPref = it) } }, label = "意向区域", placeholder = "朝阳国贸 | 通州副中心")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekTextField(form.budgetMin, { vm.update { f -> f.copy(budgetMin = it) } }, label = "预算下限(万)", modifier = Modifier.weight(1f), placeholder = "400")
                GeekTextField(form.budgetMax, { vm.update { f -> f.copy(budgetMax = it) } }, label = "预算上限(万)", modifier = Modifier.weight(1f), placeholder = "600")
            }
            GeekTextField(form.houseType, { vm.update { f -> f.copy(houseType = it) } }, label = "房型偏好", placeholder = "三居/叠拼/平层")
            GeekTextField(form.targetProject, { vm.update { f -> f.copy(targetProject = it) } }, label = "意向楼盘")

            Section("意向与跟进")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(IntentLevel.A, IntentLevel.B, IntentLevel.C, IntentLevel.D, IntentLevel.U).forEach { lvl ->
                    val on = form.intentLevel == lvl
                    val lvlColor = com.realtor.geeksales.ui.components.intentColor(lvl)
                    androidx.compose.runtime.key(lvl) {
                        // 单层边框色块：选中=等级色实感，未选=中性灰。不再嵌套 Chip 双层边框
                        Text(
                            text = when (lvl) {
                                IntentLevel.A -> "A 强烈"
                                IntentLevel.B -> "B 一般"
                                IntentLevel.C -> "C 弱"
                                IntentLevel.D -> "D 无效"
                                IntentLevel.U -> "U 未评"
                            },
                            color = if (on) lvlColor else com.realtor.geeksales.ui.theme.TextSecondary,
                            modifier = Modifier
                                .background(if (on) lvlColor.copy(alpha = 0.16f) else com.realtor.geeksales.ui.theme.BgElev2)
                                .border(1.dp, if (on) lvlColor else com.realtor.geeksales.ui.theme.Divider)
                                .clickable { vm.update { f -> f.copy(intentLevel = lvl) } }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
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
            }, label = "下次跟进 (yyyy-MM-dd)", placeholder = "2025-08-30")
            GeekTextField(form.note, { vm.update { f -> f.copy(note = it) } }, label = "备注", placeholder = "第一次沟通重点、家人意见…", singleLine = false, minLines = 3)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekPrimaryButton("保存并返回", { vm.save() }, Modifier.weight(1f))
                GeekGhostButton("取消", color = Danger, onClick = onBack)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(4.dp))
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = Accent, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(com.realtor.geeksales.ui.theme.Divider)
        )
    }
}
