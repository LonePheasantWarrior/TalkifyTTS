package com.github.lonepheasantwarrior.talkify

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.NetworkConnectivityChecker
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"
        private val activityScope = CoroutineScope(Dispatchers.Main)
    }

    private var pendingDialog: AlertDialog? = null

    private val settingsLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        TtsLogger.d(TAG) { "settingsLauncher: 用户从系统设置返回，重新检查网络状态" }
        checkNetworkPermissionOnStartup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsLogger.i(TAG) { "MainActivity.onCreate: 应用启动" }

        enableEdgeToEdge()
        setContent {
            TalkifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }

        checkNetworkPermissionOnStartup()
    }

    override fun onResume() {
        super.onResume()
        TtsLogger.d(TAG) { "MainActivity.onResume: 检查是否需要重新显示网络检查弹窗" }
        val hasDialog = pendingDialog?.isShowing == true
        if (!hasDialog) {
            checkNetworkPermissionOnStartup()
        }
    }

    override fun onPause() {
        super.onPause()
        TtsLogger.d(TAG) { "MainActivity.onPause" }
    }

    private fun checkNetworkPermissionOnStartup() {
        TtsLogger.d(TAG) { "checkNetworkPermissionOnStartup: 开始检查网络权限..." }

        activityScope.launch {
            val canAccess = withContext(Dispatchers.IO) {
                NetworkConnectivityChecker.canAccessInternet(this@MainActivity)
            }
            TtsLogger.d(TAG) { "checkNetworkPermissionOnStartup: canAccessInternet = $canAccess" }

            if (!canAccess) {
                val reason = NetworkConnectivityChecker.getNetworkUnavailableReason(this@MainActivity)
                TtsLogger.w(TAG) { "checkNetworkPermissionOnStartup: 网络不可用，原因为: $reason" }
                showPermissionDeniedDialog()
            } else {
                TtsLogger.i(TAG) { "checkNetworkPermissionOnStartup: 网络可用，继续启动" }
                dismissDialog()
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        TtsLogger.d(TAG) { "showPermissionDeniedDialog: 显示网络检查弹窗" }

        if (pendingDialog?.isShowing == true) {
            TtsLogger.d(TAG) { "showPermissionDeniedDialog: 弹窗已在显示中，跳过" }
            return
        }

        val title = getString(R.string.network_blocked_title)
        val message = getString(R.string.network_blocked_message)
        val positiveButton = getString(R.string.network_open_settings)
        val negativeButton = getString(R.string.permission_exit)

        pendingDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButton) { _, _ ->
                TtsLogger.d(TAG) { "showPermissionDeniedDialog: 用户点击打开系统设置" }
                openAppSettings()
            }
            .setNegativeButton(negativeButton) { _, _ ->
                TtsLogger.w(TAG) { "showPermissionDeniedDialog: 用户选择退出应用" }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun dismissDialog() {
        pendingDialog?.dismiss()
        pendingDialog = null
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            settingsLauncher.launch(intent)
        } catch (e: Exception) {
            TtsLogger.e(TAG) { "openAppSettings: 打开设置失败: ${e.message}" }
            finish()
        }
    }
}
