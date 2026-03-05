@file:Suppress("AssignedValueIsNeverRead")

package com.github.lonepheasantwarrior.talkify.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult
import com.github.lonepheasantwarrior.talkify.infrastructure.app.update.UpdateChecker
import com.github.lonepheasantwarrior.talkify.ui.components.UpdateDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DonateChannel {
    WECHAT,
    ALIPAY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    versionName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var showDonateSheet by remember { mutableStateOf(false) }
    var showDonateInstruction by remember { mutableStateOf<DonateChannel?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingDonateChannel by remember { mutableStateOf<DonateChannel?>(null) }

    val githubUrl = stringResource(R.string.about_github_url)

    val updateChecker = remember { UpdateChecker() }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }

    val sheetState = rememberModalBottomSheetState()

    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { _ ->
    }

    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted && pendingDonateChannel != null) {
            scope.launch {
                val success = saveQrCodeToGallery(context, pendingDonateChannel!!)
                if (success) {
                    showDonateInstruction = pendingDonateChannel
                } else {
                    Toast.makeText(context, R.string.donate_save_failed, Toast.LENGTH_SHORT).show()
                }
                pendingDonateChannel = null
            }
        } else {
            pendingDonateChannel = null
        }
    }

    fun requestSaveQrCode(channel: DonateChannel) {
        scope.launch {
            val success = saveQrCodeToGallery(context, channel)
            if (success) {
                showDonateInstruction = channel
            } else {
                Toast.makeText(context, R.string.donate_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = stringResource(R.string.about_version_format, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    isCheckingUpdate = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            updateChecker.checkForUpdates(versionName)
                        }
                        isCheckingUpdate = false
                        showUpdateResult = result
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCheckingUpdate
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.check_for_updates))
            }

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        uriHandler.openUri(githubUrl)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.about_github),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = githubUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.about_donate),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_donate_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = { showDonateSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.about_donate))
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    if (showDonateSheet) {
        ModalBottomSheet(
            onDismissRequest = { },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.donate_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            requestSaveQrCode(DonateChannel.WECHAT)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(stringResource(R.string.donate_wechat))
                    }
                    OutlinedButton(
                        onClick = {
                            requestSaveQrCode(DonateChannel.ALIPAY)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(stringResource(R.string.donate_alipay))
                    }
                }
            }
        }
    }

    showDonateInstruction?.let { channel ->
        val title = when (channel) {
            DonateChannel.WECHAT -> stringResource(R.string.donate_wechat_title)
            DonateChannel.ALIPAY -> stringResource(R.string.donate_alipay_title)
        }
        val message = when (channel) {
            DonateChannel.WECHAT -> stringResource(R.string.donate_wechat_message)
            DonateChannel.ALIPAY -> stringResource(R.string.donate_alipay_message)
        }

        AlertDialog(
            onDismissRequest = { showDonateInstruction = null },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start
                )
            },
            confirmButton = {
                TextButton(onClick = { showDonateInstruction = null }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    showUpdateResult?.let { result ->
        when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                UpdateDialog(
                    updateInfo = result.updateInfo,
                    onDismiss = { showUpdateResult = null },
                    onRemindLater = { showUpdateResult = null }
                )
            }
            is UpdateCheckResult.NoUpdateAvailable -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResult = null },
                    title = {
                        Text(
                            text = stringResource(R.string.update_already_latest),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.update_no_release),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResult = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }
            is UpdateCheckResult.NetworkTimeout -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResult = null },
                    title = {
                        Text(
                            text = stringResource(R.string.update_check_failed),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.update_check_failed_network),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResult = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }
            is UpdateCheckResult.NetworkError -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResult = null },
                    title = {
                        Text(
                            text = stringResource(R.string.update_check_failed),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.update_check_failed_network),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResult = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }
            is UpdateCheckResult.ServerError -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResult = null },
                    title = {
                        Text(
                            text = stringResource(R.string.update_check_failed),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.update_check_failed_server),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResult = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }
            is UpdateCheckResult.ParseError -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResult = null },
                    title = {
                        Text(
                            text = stringResource(R.string.update_check_failed),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.update_no_release),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showUpdateResult = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.donate_photos_permission_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.donate_photos_permission_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.donate_photos_permission_grant))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.donate_photos_permission_deny))
                }
            }
        )
    }
}

private suspend fun saveQrCodeToGallery(context: Context, channel: DonateChannel): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val drawableId = when (channel) {
                DonateChannel.WECHAT -> R.drawable.wechat_qr
                DonateChannel.ALIPAY -> R.drawable.alipay_qr
            }

            val drawable = context.getDrawable(drawableId)
            val bitmap = drawable?.toBitmap() ?: return@withContext false

            val filename = when (channel) {
                DonateChannel.WECHAT -> "talkify_wechat_donate_qr.png"
                DonateChannel.ALIPAY -> "talkify_alipay_donate_qr.png"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Talkify")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            val outputStream = uri?.let { context.contentResolver.openOutputStream(it) }

            outputStream?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
