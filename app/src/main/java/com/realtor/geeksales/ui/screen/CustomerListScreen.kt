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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var onlyOverdue by remember { mutableStateOf(false) }
    var onlyQueued by remember { mutableStateOf(false) }
    var onlyCalledToday by remember { mutableStateOf(false) }
    var onlyNotCalled by remember { mutableStateOf(false) }
    val list by vm.customers.collectAsStateWithLifecycle()
    val count by vm.totalCount.collectAsStateWithLifecycle()

    LaunchedEffect(q, level, onlyOverdue, onlyQueued, onlyCalledToday, onlyNotCalled) {
        delay(300)
        vm.setFilter(CustomerFilter(q, level, onlyOverdue, onlyQueued, onlyCalledToday, onlyNotCalled))
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        GeekTopBar(title = "客户名单", subtitle = "共 ${count} 条  搜索：${q.ifBlank { "—" }}", actions = {
            GeekGhostButton("+ 新建", onClick = { onNav(Routes.customerEdit(0)) }, color = Accent)
        })
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GeekTextField(q, { q = it }, placeholder = "搜：姓名 / 电话 / 楼盘 / 区域")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LevelFilterChip("A", IntentLevel.A, level) { level = if (level == it) null else it }
                LevelFilterChip("B", IntentLevel.B, level) { level = if (level == it) null else it }
                LevelFilterChip("C", IntentLevel.C, level) { level = if (level == it) null else it }
                LevelFilterChip("D", IntentLevel.D, level) { level = if (level == it) null else it }
                LevelFilterChip("U", IntentLevel.U, level) { level = if (level == it) null else it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("待跟进过期", onlyOverdue, Warning) { onlyOverdue = !onlyOverdue }
                ToggleChip("仅拨号队列", onlyQueued, Success) { onlyQueued = !onlyQueued }
                ToggleChip("今日已拨打", onlyCalledToday, Accent) { onlyCalledToday = !onlyCalledToday }
                ToggleChip("未拨打", onlyNotCalled, TextMuted) { onlyNotCalled = !onlyNotCalled }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GeekGhostButton("导出筛选", color = Accent, onClick = { onNav(Routes.IMPORT_EXPORT) })
                Spacer(Modifier.weight(1f))
                GeekGhostButton("加入队列", color = Accent, onClick = { vm.addAllFilteredToQueue() })
            }
        }
        AsciiDivider()
        if (list.isEmpty()) {
            EmptyState("空列表", "试试从导入导出上传 Excel 模板，或新建客户。")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { c ->
                    CustomerRow(c,
                        onDial = { vm.dial(c, direct = false) },
                        onToggleQueue = { vm.toggleQueue(c) },
                        onOpen = { onNav(Routes.customerDetail(c.id)) },
                        onEdit = { onNav(Routes.customerEdit(c.id)) },
                        onDelete = { vm.delete(c.id) }
                    )
                }
                item { Spacer(Modifier.height(100.dp)) }
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

@Composable
private fun ToggleChip(text: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val bg = if (on) color.copy(alpha = 0.2f) else BgElev2
    val border = if (on) color else Divider
    Row(Modifier.background(bg).border(1.dp, border).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(if (on) "✓ " else "○ ", color = color, style = MaterialTheme.typography.labelMedium)
        Text(text, color = if (on) color else TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CustomerRow(
    c: Customer,
    onDial: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clickable { onOpen() }
            .background(BgElev)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                    if (!c.areaPref.isNullOrBlank()) { Text("区域 ${c.areaPref}", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false)); Spacer(Modifier.width(10.dp)) }
                    val b = buildString { if (c.budgetMinWan != null) append(c.budgetMinWan); if (c.budgetMinWan != null || c.budgetMaxWan != null) append("~"); if (c.budgetMaxWan != null) append(c.budgetMaxWan); if (isNotBlank()) append("万") }
                    if (b.isNotBlank()) { Text("预算 $b", color = TextSecondary, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.width(10.dp)) }
                    if (!c.targetProject.isNullOrBlank()) Text("楼盘 ${c.targetProject}", color = TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("拨打 ${c.dialCount}  ", color = TextMuted, style = MaterialTheme.typography.labelMedium)
                    Text("下次 ${Formatter.day(c.nextFollowAt)}", color = if (c.nextFollowAt != null && c.nextFollowAt < System.currentTimeMillis()) Warning else TextMuted, style = MaterialTheme.typography.labelMedium)
                }
            }
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
        AsciiDivider()
    }
}