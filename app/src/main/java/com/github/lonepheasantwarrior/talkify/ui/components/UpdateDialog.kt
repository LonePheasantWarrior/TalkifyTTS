package com.github.lonepheasantwarrior.talkify.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateInfo

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onRemindLater: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasReleaseNotes = updateInfo.releaseNotes.length > 0
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onRemindLater,
        modifier = modifier,
        icon = {
            Icon(
                imageVector = Icons.Filled.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_available_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = stringResource(R.string.update_version_format, updateInfo.versionName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                    textAlign = TextAlign.Start
                )

                if (hasReleaseNotes) {
                    Text(
                        text = stringResource(R.string.update_whats_new),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 移除固定高度限制，改用滚动容器自适应
                    MarkdownText(
                        content = updateInfo.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (updateInfo.downloadUrl != null) {
                    Text(
                        text = stringResource(R.string.update_published_at, updateInfo.publishedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    openDownloadUrl(context, updateInfo)
                    onDismiss()
                }
            ) {
                Text(
                    text = stringResource(R.string.update_now),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onRemindLater
            ) {
                Text(
                    text = stringResource(R.string.update_remind_later),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}

private fun openDownloadUrl(context: android.content.Context, updateInfo: UpdateInfo) {
    try {
        val downloadUrl = updateInfo.downloadUrl
        val releaseUrl = updateInfo.releaseUrl
        
        val url = when {
            !downloadUrl.isNullOrEmpty() -> downloadUrl
            !releaseUrl.isNullOrEmpty() -> releaseUrl
            else -> return
        }
        
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}