@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.realtor.geeksales.data.db.Customer
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.navigation.Routes
import com.realtor.geeksales.ui.components.AsciiDivider
import com.realtor.geeksales.ui.components.EmptyState
import com.realtor.geeksales.ui.components.GeekCard
import com.realtor.geeksales.ui.components.GeekChip
import com.realtor.geeksales.ui.components.GeekGhostButton
import com.realtor.geeksales.ui.components.GlobalToast
import com.realtor.geeksales.ui.components.GeekPrimaryButton
import com.realtor.geeksales.ui.components.GeekTextField
import com.realtor.geeksales.ui.components.GeekTopBar
import com.realtor.geeksales.ui.components.IntentLevelBadge
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
import com.realtor.geeksales.util.Formatter
import com.realtor.geeksales.viewmodel.CustomerFilter
import com.realtor.geeksales.viewmodel.CustomerListViewModel
import kotlinx.coroutines.delay

@Composable
fun CustomerListScreen(
    onNav: (String) -> Unit,
    vm: CustomerListViewModel = hiltViewModel()
) {
    var q by remember { mutableStateOf("") }
    var level by remember { mutableStateOf<IntentLevel?>(null) }
    var tagName by remember { mutableStateOf<String?>(null) }
    var onlyOverdue by remember { mutableStateOf(false) }
    var onlyQueued by remember { mutableStateOf(false) }
    var onlyCalledToday by remember { mutableStateOf(false) }
    var onlyNotCalled by remember { mutableStateOf(false) }
    // 批量模式（扁平化批量分层/标签/入队）
    var batchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showLevelPicker by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    val list by vm.customers.collectAsStateWithLifecycle()
    val count by vm.totalCount.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val tierMeta = vm.templateMeta.collectAsStateWithLifecycle().value

    LaunchedEffect(q, level, tagName, onlyOverdue, onlyQueued, onlyCalledToday, onlyNotCalled) {
        delay(300)
        vm.setFilter(CustomerFilter(q, level, tagName, onlyOverdue, onlyQueued, onlyCalledToday, onlyNotCalled))
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(title = "客户名单", subtitle = if (batchMode) "已选 ${selectedIds.size} 人" else "共 ${count} 条  搜索：${q.ifBlank { "—" }}", actions = {
            if (batchMode) {
                GeekGhostButton("完成", onClick = { batchMode = false; selectedIds = emptySet() }, color = Accent)
            } else {
                GeekGhostButton("批量", onClick = { batchMode = true }, color = Accent)
                GeekGhostButton("+ 新建", onClick = { onNav(Routes.customerEdit(0)) }, color = Accent)
            }
        })
        if (batchMode) {
            // 批量操作栏：全选 / 分层 / 标签 / 入队
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GeekGhostButton(if (selectedIds.isNotEmpty() && selectedIds.size == list.size) "取消全选" else "全选", color = Accent,
                        onClick = {
                            if (selectedIds.size == list.size) selectedIds = emptySet()
                            else selectedIds = list.map { it.id }.toSet()
                        })
                    Spacer(Modifier.weight(1f))
                    GeekGhostButton("批量分层", color = Accent, enabled = selectedIds.isNotEmpty(),
                        onClick = { showLevelPicker = true })
                    GeekGhostButton("批量标签", color = Accent, enabled = selectedIds.isNotEmpty(),
                        onClick = { showTagPicker = true })
                    GeekGhostButton("加入队列", color = Success, enabled = selectedIds.isNotEmpty(),
                        onClick = { vm.batchAddToQueue(selectedIds.toList()); GlobalToast.showSuccess("已加入队列 ${selectedIds.size} 人") })
                }
                Text("点击客户行勾选/取消；可配合上方筛选先圈定范围再批量操作。", color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
            AsciiDivider()
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GeekTextField(q, { q = it }, placeholder = "搜：姓名 / 电话 / 备注 / 标签")
            // 分层筛选：完全跟随模板（行业可换，不写死）
            val tierToEnum = mapOf(
                "S" to IntentLevel.S, "A" to IntentLevel.A, "B" to IntentLevel.B,
                "C" to IntentLevel.C, "D" to IntentLevel.D, "V" to IntentLevel.V, "U" to IntentLevel.U
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                tierMeta.tiers.forEach { t ->
                    val en = tierToEnum[t] ?: return@forEach
                    LevelFilterChip(tierMeta.tierBadge(t), en, level) { level = if (level == it) null else it }
                }
                if (level != null || tagName != null) {
                    Text(
                        "清除筛选",
                        color = Warning,
                        modifier = Modifier
                            .clickable { level = null; tagName = null }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            // 标签筛选（独立于分层的维度）
            if (allTags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text("#", color = TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                    allTags.forEach { t ->
                        val on = tagName == t.name
                        Text(
                            text = t.name,
                            color = if (on) Accent else TextSecondary,
                            modifier = Modifier
                                .background(if (on) Accent.copy(alpha = 0.16f) else BgElev2)
                                .border(1.dp, if (on) Accent else Divider)
                                .clickable { tagName = if (on) null else t.name }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }
            // 状态筛选：插槽式定义（id/文案/色/状态-切换映射），UI 通用渲染为两列等高 chip；
            // 新增/删减筛选维度只需改 statusFilterSlots，布局与样式自动适配
            val statusFilterSlots = listOf(
                StatusFilterSlot("overdue", "待跟进", Warning, onlyOverdue) { onlyOverdue = !onlyOverdue },
                StatusFilterSlot("queued", "拨号队列", Success, onlyQueued) { onlyQueued = !onlyQueued },
                StatusFilterSlot("calledToday", "今日已拨", Accent, onlyCalledToday) { onlyCalledToday = !onlyCalledToday },
                StatusFilterSlot("notCalled", "未拨打", TextMuted, onlyNotCalled) { onlyNotCalled = !onlyNotCalled }
            )
            statusFilterSlots.chunked(2).forEach { rowSlots ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowSlots.forEach { slot ->
                        ToggleChip(
                            text = slot.label,
                            on = slot.on,
                            color = slot.color,
                            modifier = Modifier.weight(1f),
                            onClick = slot.onToggle
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GeekGhostButton("导出筛选", color = Accent, onClick = { onNav(Routes.IMPORT_EXPORT) })
                Spacer(Modifier.weight(1f))
                GeekGhostButton("加入队列", color = Accent, onClick = { vm.addAllFilteredToQueue() })
            }
        }
        // 批量分层选择
        if (showLevelPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showLevelPicker = false },
                title = { Text("批量设置分层（${selectedIds.size} 人）") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tierMeta.tiers.forEach { t ->
                            val en = mapOf(
                                "S" to IntentLevel.S, "A" to IntentLevel.A, "B" to IntentLevel.B,
                                "C" to IntentLevel.C, "D" to IntentLevel.D, "V" to IntentLevel.V, "U" to IntentLevel.U
                            )[t]
                            val lvlColor = if (en != null) com.realtor.geeksales.ui.components.intentColor(en) else Accent
                            Row(
                                Modifier.fillMaxWidth().background(if (level == en) lvlColor.copy(alpha = 0.16f) else BgElev2)
                                    .border(1.dp, if (level == en) lvlColor else Divider)
                                    .clickable {
                                        if (en != null) {
                                            vm.batchSetLevel(selectedIds.toList(), en)
                                            GlobalToast.showSuccess("已设置 ${tierMeta.tierLabel(t)}")
                                            showLevelPicker = false
                                            batchMode = false; selectedIds = emptySet()
                                        }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(tierMeta.tierBadge(t), color = lvlColor, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                Column {
                                    Text(tierMeta.tierLabel(t), color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                    Text("建议：${tierMeta.tierCadence(t)}", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLevelPicker = false }) { Text("取消") } }
            )
        }
        // 批量标签选择
        if (showTagPicker) {
            val tags = (tierMeta.identityTags + tierMeta.attributeTags).distinct()
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showTagPicker = false },
                title = { Text("批量添加标签（${selectedIds.size} 人）") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tags.isEmpty()) {
                            Text("当前模板暂无标签，可先「数据 → 拉取线上模板」同步标签。", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                tags.forEach { t ->
                                    val on = tagName == t
                                    Text(
                                        text = t,
                                        color = if (on) Accent else TextSecondary,
                                        modifier = Modifier
                                            .background(if (on) Accent.copy(alpha = 0.16f) else BgElev2)
                                            .border(1.dp, if (on) Accent else Divider)
                                            .clickable { tagName = if (on) null else t }
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = tagName != null, onClick = {
                        val t = tagName ?: return@TextButton
                        vm.batchAddTags(selectedIds.toList(), listOf(t))
                        GlobalToast.showSuccess("已添加标签「$t」")
                        tagName = null
                        showTagPicker = false
                        batchMode = false; selectedIds = emptySet()
                    }) { Text("应用") }
                    TextButton(onClick = { showTagPicker = false }) { Text("取消") }
                }
            )
        }
        AsciiDivider()
        if (list.isEmpty()) {
            EmptyState("空列表", "试试从导入导出上传 Excel 模板，或新建客户。")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { c ->
                    CustomerRow(c,
                        batchMode = batchMode,
                        selected = c.id in selectedIds,
                        onToggleSelect = {
                            selectedIds = if (c.id in selectedIds) selectedIds - c.id else selectedIds + c.id
                        },
                        onDial = { vm.dial(c, direct = false) },
                        onToggleQueue = { vm.toggleQueue(c) },
                        onOpen = { onNav(Routes.customerDetail(c.id)) },
                        onEdit = { onNav(Routes.customerEdit(c.id)) },
                        onDelete = { vm.delete(c.id) }
                    )
                }
                item { Spacer(Modifier.height(if (batchMode) 140.dp else 100.dp)) }
            }
        }
    }
}

@Composable
private fun LevelFilterChip(code: String, lvl: IntentLevel, current: IntentLevel?, onClick: (IntentLevel) -> Unit) {
    val c = com.realtor.geeksales.ui.components.intentColor(lvl)
    val selected = current == lvl
    GeekChip(text = code, color = c, onClick = { onClick(lvl) })
}

private data class StatusFilterSlot(
    val id: String,
    val label: String,
    val color: Color,
    val on: Boolean,
    val onToggle: () -> Unit
)

@Composable
private fun ToggleChip(text: String, on: Boolean, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (on) color.copy(alpha = 0.2f) else BgElev2
    val border = if (on) color else Divider
    Row(
        modifier.background(bg).border(1.dp, border).clickable { onClick() }
            .heightIn(min = 32.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (on) "✓ " else "○ ", color = color, style = MaterialTheme.typography.labelMedium)
        Text(text, color = if (on) color else TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun CustomerRow(
    c: Customer,
    batchMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onDial: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clickable { if (batchMode) onToggleSelect() else onOpen() }
            .background(if (selected) Accent.copy(alpha = 0.07f) else BgElev)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (batchMode) {
                // 扁平化多选：行首选择圆点
                Box(
                    Modifier.size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (selected) Accent else com.realtor.geeksales.ui.theme.Divider)
                        .clickable { onToggleSelect() },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Text("✓", color = TextPrimary, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, color = TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    IntentLevelBadge(c.intentLevel)
                    if (c.queued && c.queueOrder != null) {
                        Spacer(Modifier.width(6.dp))
                        GeekChip("Q${c.queueOrder}", color = Success)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(c.phone, color = Accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!c.areaPref.isNullOrBlank()) { Text(c.areaPref, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false)); Spacer(Modifier.width(10.dp)) }
                    val b = buildString { if (c.budgetMinWan != null) append(c.budgetMinWan); if (c.budgetMinWan != null || c.budgetMaxWan != null) append("~"); if (c.budgetMaxWan != null) append(c.budgetMaxWan); if (isNotBlank()) append("万") }
                    if (b.isNotBlank()) { Text(b, color = TextSecondary, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.width(10.dp)) }
                    if (!c.targetProject.isNullOrBlank()) Text(c.targetProject, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("拨打 ${c.dialCount}  ", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    Text("下次 ${Formatter.day(c.nextFollowAt)}", color = if (c.nextFollowAt != null && c.nextFollowAt < System.currentTimeMillis()) Warning else TextMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (batchMode) {
                // 批量模式：右侧仅保留拨号（操作面板在顶部批量栏）
                GeekPrimaryButton("拨号", onDial)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    GeekPrimaryButton("拨号", onDial)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GeekGhostButton("编辑", color = Accent, onClick = onEdit)
                        GeekGhostButton(if (c.queued) "出队" else "入队", color = Success, onClick = onToggleQueue)
                        GeekGhostButton("删除", color = Danger, onClick = onDelete)
                    }
                }
            }
        }
        AsciiDivider()
    }
}