package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.github.lonepheasantwarrior.talkify.R

/**
 * 网络阻塞弹窗组件
 *
 * 当应用无网络连接时显示，引导用户去系统设置恢复网络；
 * 正文按本地模型就绪情况自适应说明离线可用性，用户可点击"知道了"继续使用。
 */
@Composable
fun NetworkBlockedDialog(
    offlineCapable: Boolean,
    onOpenSettings: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = {}, // 不允许点击外部关闭，必须做出选择
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.network_blocked_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (offlineCapable) {
                        R.string.network_blocked_message_offline_ready
                    } else {
                        R.string.network_blocked_message
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        },
        confirmButton = {
            TextButton(
                onClick = onOpenSettings
            ) {
                Text(
                    text = stringResource(R.string.network_open_settings),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onAcknowledge
            ) {
                Text(
                    text = stringResource(R.string.network_blocked_acknowledge),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}
