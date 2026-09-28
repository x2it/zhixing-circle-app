@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.realtor.geeksales.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.realtor.geeksales.ui.components.GlobalToast
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.TextSecondary
import com.realtor.geeksales.ui.theme.Warning

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.navigation.Routes
import com.realtor.geeksales.ui.components.AsciiDivider
import com.realtor.geeksales.ui.components.EmptyState
import com.realtor.geeksales.ui.components.GeekCard
import com.realtor.geeksales.ui.components.GeekGhostButton
import com.realtor.geeksales.ui.components.GeekPrimaryButton
import com.realtor.geeksales.ui.components.GeekTopBar
import com.realtor.geeksales.ui.components.IntentLevelChip
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.BgElev2
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.Divider
import com.realtor.geeksales.ui.theme.Success
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.Warning
import com.realtor.geeksales.ui.theme.IntentA
import com.realtor.geeksales.ui.theme.IntentB
import com.realtor.geeksales.ui.theme.IntentC
import com.realtor.geeksales.ui.theme.IntentD
import com.realtor.geeksales.ui.theme.IntentU
import com.realtor.geeksales.util.Formatter
import com.realtor.geeksales.viewmodel.DialQueueViewModel

@Composable
fun DialQueueScreen(
    onNav: (String) -> Unit,
    onBack: () -> Unit,
    vm: DialQueueViewModel = hiltViewModel()
) {
    val q by vm.queue.collectAsStateWithLifecycle()
    var showSmart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(title = "拨号队列", subtitle = "共 ${q.size} 人", onBack = onBack, actions = {
            if (q.isNotEmpty()) {
                GeekGhostButton("清空", onClick = { vm.clear() }, color = Danger)
            }
        })
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GeekCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("下一位待拨打", color = Accent, style = MaterialTheme.typography.labelMedium)
                        val head = q.firstOrNull()
                        if (head == null) {
                            Spacer(Modifier.height(6.dp))
                            Text("暂无待拨打客户", color = TextMuted)
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Text(head.name, color = TextPrimary, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Row {
                                Text(head.phone, color = Accent, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Spacer(Modifier.width(10.dp))
                                com.realtor.geeksales.ui.components.IntentLevelBadge(head.intentLevel)
                            }
                        }
                    }
                    GeekPrimaryButton("拨号", { vm.dialFirst(direct = false) }, enabled = q.isNotEmpty())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GeekGhostButton("智能队列", color = Success, onClick = { showSmart = true })
                GeekGhostButton("去客户列表", color = Accent, onClick = { onNav(Routes.CUSTOMER_LIST) })
                GeekGhostButton("导入客户", color = Warning, onClick = { onNav(Routes.IMPORT_EXPORT) })
            }
            // 智能队列：按分层/标签/跟进到期 批量入队（专业科学的批量匹配，不再逐个手动添加）
            if (showSmart) {
                val tierMeta = vm.templateMeta()
                SmartQueueDialog(
                    tiers = tierMeta.tiers,
                    tierLabels = tierMeta,
                    tags = (tierMeta.identityTags + tierMeta.attributeTags).distinct(),
                    onDismiss = { showSmart = false },
                    onConfirm = { lvl, tag, onlyOverdue, withinDays ->
                        vm.addSmart(
                            level = lvl,
                            tag = tag,
                            onlyOverdue = onlyOverdue,
                            dueWithinDays = withinDays
                        ) { added ->
                            GlobalToast.showSuccess(if (added > 0) "已加入队列 $added 人" else "符合条件且未入队的客户为 0（已全部在队或不存在）")
                        }
                        showSmart = false
                    }
                )
            }
        }
        AsciiDivider()
        if (q.isEmpty()) {
            EmptyState("队列为空", "在客户列表勾选或筛选后，点击『加入队列』即可批量导入待拨打清单。")
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                itemsIndexed(q, key = { _, c -> c.id }) { i, c ->
                    QItem(i + 1, c,
                        onDial = { vm.dialAt(i, direct = false) },
                        onDetail = { onNav(Routes.customerDetail(c.id)) },
                        onRemove = { vm.remove(c.id) }
                    )
                }
                item { Spacer(Modifier.height(100.dp)) }
            }
        }
    }
}

@Composable
private fun SmartQueueDialog(
    tiers: List<String>,
    tierLabels: com.realtor.geeksales.data.schema.TemplateMeta,
    tags: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (com.realtor.geeksales.data.db.IntentLevel?, String?, Boolean, Long) -> Unit
) {
    var selLevel by remember { mutableStateOf<com.realtor.geeksales.data.db.IntentLevel?>(null) }
    var selTag by remember { mutableStateOf<String?>(null) }
    var followScope by remember { mutableStateOf(0) } // 0=全部 1=仅过期 2=含近7天到期
    val tierToEnum = mapOf(
        "S" to com.realtor.geeksales.data.db.IntentLevel.S, "A" to com.realtor.geeksales.data.db.IntentLevel.A,
        "B" to com.realtor.geeksales.data.db.IntentLevel.B, "C" to com.realtor.geeksales.data.db.IntentLevel.C,
        "D" to com.realtor.geeksales.data.db.IntentLevel.D, "V" to com.realtor.geeksales.data.db.IntentLevel.V, "U" to com.realtor.geeksales.data.db.IntentLevel.U
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("智能队列 · 批量入队") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("按分层", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "全部",
                        color = if (selLevel == null) Accent else TextSecondary,
                        modifier = Modifier
                            .background(if (selLevel == null) Accent.copy(alpha = 0.16f) else BgElev2)
                            .border(1.dp, if (selLevel == null) Accent else Divider)
                            .clickable { selLevel = null }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                    tiers.forEach { t ->
                        val en = tierToEnum[t]
                        val lvlColor = if (en != null) com.realtor.geeksales.ui.components.intentColor(en) else Accent
                        val on = selLevel == en
                        Text(
                            text = "${tierLabels.tierBadge(t)} ${tierLabels.tierLabels[t] ?: t}",
                            color = if (on) lvlColor else TextSecondary,
                            modifier = Modifier
                                .background(if (on) lvlColor.copy(alpha = 0.16f) else BgElev2)
                                .border(1.dp, if (on) lvlColor else Divider)
                                .clickable { selLevel = if (on) null else en }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
                Text("跟进时间", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("全部" to 0, "仅已过期" to 1, "含近7天到期" to 2).forEach { (name, v) ->
                        val on = followScope == v
                        Text(
                            text = name,
                            color = if (on) Warning else TextSecondary,
                            modifier = Modifier
                                .background(if (on) Warning.copy(alpha = 0.16f) else BgElev2)
                                .border(1.dp, if (on) Warning else Divider)
                                .clickable { followScope = v }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (tags.isNotEmpty()) {
                    Text("按标签（可选）", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { t ->
                            val on = selTag == t
                            Text(
                                text = t,
                                color = if (on) Accent else TextSecondary,
                                modifier = Modifier
                                    .background(if (on) Accent.copy(alpha = 0.16f) else BgElev2)
                                    .border(1.dp, if (on) Accent else Divider)
                                    .clickable { selTag = if (on) null else t }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }
                Text("未入队者自动加入；已入队客户不会重复进队。", color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(selLevel, selTag, followScope == 1, if (followScope == 2) 7L else 0L)
            }) { Text("加入队列") }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun QItem(idx: Int, c: Customer, onDial: () -> Unit, onDetail: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(BgElev2)
            .border(1.dp, Divider)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号：普通数字，与名单页 Q# 编号一致（normalize 后两者同源）
        Text(
            idx.toString(),
            color = Accent,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(32.dp)
        )
        // 信息区：占据剩余空间
        Column(Modifier.weight(1f, fill = true)) {
            // 第一行：名字 + 等级短标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    c.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true)
                )
                Spacer(Modifier.width(6.dp))
                com.realtor.geeksales.ui.components.IntentLevelBadge(c.intentLevel)
            }
            Spacer(Modifier.height(4.dp))
            // 第二行：号码
            Text(
                c.phone,
                color = Accent,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            // 第三行：统计
            Text(
                "拨" + c.dialCount + " · 下次" + Formatter.day(c.nextFollowAt),
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // 按钮区：纵向排列，固定 80dp 宽，紧凑无间隙
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(80.dp)
        ) {
            GeekPrimaryButtonSmall("拨号", onDial)
            GeekGhostButtonSmall("详情", color = Success, onClick = onDetail)
            GeekGhostButtonSmall("移除", color = Danger, onClick = onRemove)
        }
    }
}

/** 小尺寸主按钮：拨号队列 */
@Composable
private fun GeekPrimaryButtonSmall(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = androidx.compose.ui.graphics.Color.White,
            disabledContainerColor = Divider,
            disabledContentColor = TextMuted
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            color = androidx.compose.ui.graphics.Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
    }
}

/** 小尺寸描边按钮：拨号队列 */
@Composable
private fun GeekGhostButtonSmall(
    text: String,
    onClick: () -> Unit,
    color: Color = Accent
) {
    // 改用 OutlinedButton，关闭 Material 最小高度约束，避免产生空白框
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = color
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp)
    ) {
        androidx.compose.material3.ProvideTextStyle(value = MaterialTheme.typography.labelMedium) {
            Text(
                text = text,
                color = color,
                maxLines = 1,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        }
    }
}



