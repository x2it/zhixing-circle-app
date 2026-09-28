package com.realtor.geeksales.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.realtor.geeksales.data.db.IntentLevel
import com.realtor.geeksales.ui.theme.Accent
import com.realtor.geeksales.ui.theme.Bg
import com.realtor.geeksales.ui.theme.BgElev
import com.realtor.geeksales.ui.theme.BgElev2
import com.realtor.geeksales.ui.theme.Danger
import com.realtor.geeksales.ui.theme.Divider
import com.realtor.geeksales.ui.theme.IntentA
import com.realtor.geeksales.ui.theme.IntentS
import com.realtor.geeksales.ui.theme.IntentV
import com.realtor.geeksales.ui.theme.IntentB
import com.realtor.geeksales.ui.theme.IntentC
import com.realtor.geeksales.ui.theme.IntentD
import com.realtor.geeksales.ui.theme.IntentU
import com.realtor.geeksales.ui.theme.Success
import com.realtor.geeksales.ui.theme.TextMuted
import com.realtor.geeksales.ui.theme.TextPrimary
import com.realtor.geeksales.ui.theme.TextSecondary
import com.realtor.geeksales.ui.theme.Warning

/** 扁平硬边卡片：无圆角、实心边框 */
@Composable
fun GeekCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(BgElev)
            .border(1.dp, Divider)
    ) { content() }
}

/** 主按钮：实心青蓝，硬边 */
@Composable
fun GeekPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Bg,
            disabledContainerColor = Divider,
            disabledContentColor = TextMuted
        ),
        shape = RoundedCornerShape(0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GeekGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Accent,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(40.dp)
            .border(1.dp, color),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GeekDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Bg),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GeekTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = if (label != null) {
            { Text(label, style = MaterialTheme.typography.labelMedium) }
        } else null,
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = TextMuted) }
        } else null,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 4,
        isError = isError,
        shape = RoundedCornerShape(0.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Divider,
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextSecondary,
            cursorColor = Accent,
            errorBorderColor = Danger,
            errorCursorColor = Danger,
            unfocusedTextColor = TextPrimary,
            focusedTextColor = TextPrimary,
            focusedContainerColor = BgElev2,
            unfocusedContainerColor = BgElev
        ),
        textStyle = MaterialTheme.typography.bodyLarge
    )
}

/** 极客芯片（标签）：硬边 + 等宽字 */
@Composable
fun GeekChip(text: String, color: Color = Accent, onClick: (() -> Unit)? = null) {
    val bg = color.copy(alpha = 0.12f)
    val m = Modifier
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .background(bg)
        .border(1.dp, color.copy(alpha = 0.5f))
        .padding(horizontal = 10.dp, vertical = 4.dp)
    Text(
        text = text,
        color = color,
        modifier = m,
        style = MaterialTheme.typography.labelMedium
    )
}

/**
 * 意向等级徽标（紧凑版）：单字母色块，用于列表/队列等密集场景。
 * 颜色即语义：S 金=A 绿=B 蓝=C 黄=D 红=V 紫=U 灰，完整含义在详情页查看。
 */
@Composable
fun IntentLevelBadge(level: IntentLevel) {
    val (c, label) = when (level) {
        IntentLevel.S -> IntentS to "S"
        IntentLevel.A -> IntentA to "A"
        IntentLevel.B -> IntentB to "B"
        IntentLevel.C -> IntentC to "C"
        IntentLevel.D -> IntentD to "D"
        IntentLevel.V -> IntentV to "V"
        IntentLevel.U -> IntentU to "/"
    }
    Text(
        text = label,
        color = c,
        modifier = Modifier
            .background(c.copy(alpha = 0.14f))
            .border(1.dp, c.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        maxLines = 1
    )
}

/** 意向等级芯片（完整版）：详情页/编辑页等需要完整语义的场景 */
@Composable
fun IntentLevelChip(level: IntentLevel) {
    val (c, label) = when (level) {
        IntentLevel.S -> IntentS to "S · 成交高价值"
        IntentLevel.A -> IntentA to "A · 高意向"
        IntentLevel.B -> IntentB to "B · 已接触"
        IntentLevel.C -> IntentC to "C · 信息完整"
        IntentLevel.D -> IntentD to "D · 线索"
        IntentLevel.V -> IntentV to "V · 已成交"
        IntentLevel.U -> IntentU to "/ · 未分类"
    }
    GeekChip(text = label, color = c)
}

/** 统计卡：终端风格，如 "[ 012 ]" */
@Composable
fun StatTile(label: String, value: String, hint: String? = null, accent: Color = Accent) {
    GeekCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = label,
                color = accent,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(6.dp))
            // 终端风：数字用 "[ N ]" 包裹，与整体 ASCII 风格呼应
            Text(
                text = "[ $value ]",
                color = accent,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.headlineLarge
            )
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = hint, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 空状态：ASCII 风格的空占位 */
@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "—",
            color = TextMuted,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge
        )
        Text(text = title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Text(
            text = hint,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 顶部 TopBar：硬边、扁平、右侧可加操作 */
@Composable
fun GeekTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    Column(modifier = modifier
        .fillMaxWidth()
        .background(Bg)
        .border(0.dp, Divider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                    Text("‹", color = Accent, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = TextMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            actions()
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Divider)
        )
    }
}

/** 水平 ASCII 分隔线：---------------------------------------- */
@Composable
fun AsciiDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Divider)
    )
}

/** 状态色的点标签 */
@Composable
fun StatusDotLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

fun intentColor(level: IntentLevel): Color = when (level) {
    IntentLevel.S -> IntentS
    IntentLevel.A -> IntentA
    IntentLevel.B -> IntentB
    IntentLevel.C -> IntentC
    IntentLevel.D -> IntentD
    IntentLevel.V -> IntentV
    IntentLevel.U -> IntentU
}

fun resultColor(r: com.realtor.geeksales.data.db.FollowResult): Color = when (r) {
    com.realtor.geeksales.data.db.FollowResult.CONNECTED -> Success
    com.realtor.geeksales.data.db.FollowResult.APPOINTMENT -> IntentA
    com.realtor.geeksales.data.db.FollowResult.NOT_REACHED -> Warning
    com.realtor.geeksales.data.db.FollowResult.NOT_INTERESTED -> IntentC
    com.realtor.geeksales.data.db.FollowResult.WRONG_NUMBER,
    com.realtor.geeksales.data.db.FollowResult.SHUTDOWN -> Danger
    com.realtor.geeksales.data.db.FollowResult.PENDING -> TextSecondary
}
