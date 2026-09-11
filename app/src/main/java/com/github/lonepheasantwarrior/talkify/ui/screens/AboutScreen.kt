@file:Suppress("AssignedValueIsNeverRead")

package com.github.lonepheasantwarrior.talkify.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppActionTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.AppPageTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.update.UpdateChecker
import com.github.lonepheasantwarrior.talkify.ui.components.UpdateDialog
import com.github.lonepheasantwarrior.talkify.ui.components.rememberTelemetryScrollObserver
import com.github.lonepheasantwarrior.talkify.ui.theme.SharedKeyBrandMark
import com.github.lonepheasantwarrior.talkify.ui.theme.SharedKeyBrandTitle
import com.github.lonepheasantwarrior.talkify.ui.theme.sharedBrandBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DonateChannel {
    WECHAT,
    ALIPAY
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    versionName: String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var showDonateSheet by remember { mutableStateOf(false) }
    var showDonateInstruction by remember { mutableStateOf<DonateChannel?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    val githubUrl = stringResource(R.string.about_github_url)
    val privacyPolicyUrl = stringResource(R.string.about_privacy_policy_url)

    val updateChecker = remember { UpdateChecker() }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }

    val sheetState = rememberModalBottomSheetState()

    fun donateChannelKey(channel: DonateChannel): String = when (channel) {
        DonateChannel.WECHAT -> AppActionTracker.CHANNEL_WECHAT
        DonateChannel.ALIPAY -> AppActionTracker.CHANNEL_ALIPAY
    }

    fun saveAndReportQr(channel: DonateChannel) {
        scope.launch {
            val success = saveQrCodeToGallery(context, channel)
            AppActionTracker.donateQrSaved(donateChannelKey(channel), success, AppActionTracker.URL_ABOUT)
            if (success) {
                showDonateInstruction = channel
            } else {
                Toast.makeText(context, R.string.donate_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestSaveQrCode(channel: DonateChannel) {
        AppActionTracker.donateChannelClick(donateChannelKey(channel), AppActionTracker.URL_ABOUT)
        saveAndReportQr(channel)
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
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        val scrollState = rememberScrollState()
        rememberTelemetryScrollObserver(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // adaptive 图标不能直接 painterResource，经 PackageManager 取渲染后的位图
            val appIconBitmap = remember {
                context.packageManager
                    .getApplicationIcon(context.packageName)
                    .toBitmap()
                    .asImageBitmap()
            }
            Image(
                bitmap = appIconBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.large)
                    .sharedBrandBounds(
                        SharedKeyBrandMark,
                        sharedTransitionScope,
                        animatedVisibilityScope
                    )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.sharedBrandBounds(
                    SharedKeyBrandTitle,
                    sharedTransitionScope,
                    animatedVisibilityScope
                )
            )

            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.about_version_format, versionName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

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
                        val startedAt = SystemClock.elapsedRealtime()
                        val result = withContext(Dispatchers.IO) {
                            updateChecker.checkForUpdates(versionName)
                        }
                        AppActionTracker.updateCheck(
                            AppActionTracker.TRIGGER_MANUAL,
                            result,
                            (SystemClock.elapsedRealtime() - startedAt).toInt()
                        )
                        if (result is UpdateCheckResult.UpdateAvailable) {
                            AppPageTracker.open(AppPageTracker.PATH_UPDATE, "Update")
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
                        AppActionTracker.externalLinkOpen(AppActionTracker.TARGET_GITHUB, AppActionTracker.URL_ABOUT)
                        uriHandler.openUri(githubUrl)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.large
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

            val qqGroupNumber = stringResource(R.string.about_qq_group_number)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        AppActionTracker.qqGroupCopied(AppActionTracker.URL_ABOUT)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("QQ群号", qqGroupNumber)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, R.string.about_qq_group_copied, Toast.LENGTH_SHORT).show()
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.about_qq_group),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = qqGroupNumber,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.about_qq_group_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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

            // 关于隐私
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        AppPageTracker.open(AppPageTracker.PATH_PRIVACY, "Privacy")
                        showPrivacyDialog = true
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.about_privacy),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.about_privacy_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = MaterialTheme.shapes.large
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
                        onClick = {
                            AppPageTracker.open(AppPageTracker.PATH_DONATE, "Donate")
                            showDonateSheet = true
                        },
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
            onDismissRequest = { showDonateSheet = false },
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
                    onRemindLater = { showUpdateResult = null },
                    source = AppActionTracker.TRIGGER_MANUAL
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

    // 匿名信息收集声明弹窗
    if (showPrivacyDialog) {
        val privacyItems = listOf(
            stringResource(R.string.about_privacy_item_device),
            stringResource(R.string.about_privacy_item_network),
            stringResource(R.string.about_privacy_item_usage),
            stringResource(R.string.about_privacy_item_tts),
            stringResource(R.string.about_privacy_item_crash)
        )

        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Column {
                    Text(
                        text = stringResource(R.string.about_privacy_dialog_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_privacy_dialog_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    privacyItems.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 强调不收集隐私数据
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.about_privacy_disclaimer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text(stringResource(R.string.about_privacy_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppActionTracker.externalLinkOpen(
                        AppActionTracker.TARGET_PRIVACY_POLICY,
                        AppActionTracker.URL_ABOUT
                    )
                    uriHandler.openUri(privacyPolicyUrl)
                }) {
                    Text(stringResource(R.string.about_privacy_view_full))
                }
            },
            shape = RoundedCornerShape(24.dp)
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
