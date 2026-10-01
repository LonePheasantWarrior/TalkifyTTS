package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.github.lonepheasantwarrior.talkify.R

/**
 * 电池优化权限请求弹窗组件
 *
 * 用于引导用户关闭电池优化，确保后台服务稳定运行
 * 遵循 Material 3 Expressive 设计规范
 *
 * @param onConfirm 确认回调（跳转系统设置）
 * @param onSkip    显式"以后再说"回调（用户明确选择跳过，调用方持久化）
 * @param onDismiss 弹窗外点击/返回键关闭回调（不视为显式跳过）
 * @param modifier 修饰符
 */
@Composable
fun BatteryOptimizationDialog(
    onConfirm: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Filled.BatteryAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.battery_optimization_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.battery_optimization_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(
                    text = stringResource(R.string.battery_optimization_allow),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkip
            ) {
                Text(
                    text = stringResource(R.string.action_later),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}
