package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelInfo
import com.github.lonepheasantwarrior.talkify.domain.model.LocalModelType
import com.github.lonepheasantwarrior.talkify.infrastructure.provider.local.LocalModelManager

/**
 * 模型管理对话框
 *
 * 展示已部署模型的详细信息（大小、语言、音色数量、磁盘占用），
 * 并提供卸载模型功能。
 *
 * @param modelInfo 要管理的模型信息
 * @param onDismiss 关闭对话框回调
 * @param onUninstall 卸载模型回调
 * @param modifier 修饰符
 */
@Composable
fun ModelManagementDialog(
    modelInfo: LocalModelInfo,
    onDismiss: () -> Unit,
    onUninstall: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showUninstallConfirm by remember { mutableStateOf(false) }

    val diskUsage = remember(modelInfo.id) {
        LocalModelManager.formatDiskUsage(LocalModelManager.getModelDiskUsage(modelInfo.id))
    }

    if (showUninstallConfirm) {
        ModelUninstallDialog(
            modelInfo = modelInfo,
            onConfirm = {
                showUninstallConfirm = false
                onUninstall(modelInfo.id)
            },
            onDismiss = { showUninstallConfirm = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = modelInfo.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 模型描述
                Text(
                    text = modelInfo.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(12.dp))

                // 详细信息行
                DetailRow(
                    label = stringResource(R.string.model_type_label),
                    value = when (modelInfo.modelType) {
                        LocalModelType.VITS -> "VITS"
                        LocalModelType.KOKORO -> "Kokoro"
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))

                DetailRow(
                    label = stringResource(R.string.model_size_label),
                    value = modelInfo.downloadSizeDisplay
                )
                Spacer(modifier = Modifier.height(8.dp))

                DetailRow(
                    label = stringResource(R.string.model_disk_usage_label),
                    value = diskUsage
                )
                Spacer(modifier = Modifier.height(8.dp))

                DetailRow(
                    label = stringResource(R.string.model_language_label),
                    value = modelInfo.supportedLanguages.joinToString(", ") { it.uppercase() }
                )
                Spacer(modifier = Modifier.height(8.dp))

                DetailRow(
                    label = stringResource(R.string.model_voice_count_label),
                    value = "${modelInfo.voiceList.size}"
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { showUninstallConfirm = true }
            ) {
                Text(
                    text = stringResource(R.string.model_uninstall),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}

/**
 * 卸载确认对话框
 */
@Composable
private fun ModelUninstallDialog(
    modelInfo: LocalModelInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = stringResource(R.string.model_uninstall_confirm_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = stringResource(R.string.model_uninstall_confirm, modelInfo.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(
                    text = stringResource(R.string.confirm),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}

/**
 * 详情行组件：标签 + 值
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
