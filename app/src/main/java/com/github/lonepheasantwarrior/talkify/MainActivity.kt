package com.github.lonepheasantwarrior.talkify

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.TalkifyTelemetry
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.UmamiRecorder
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.components.TelemetryCaptureHost
import com.github.lonepheasantwarrior.talkify.ui.screens.AboutScreen
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"

        private const val ROUTE_MAIN = "main"
        private const val ROUTE_ABOUT = "about"
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsLogger.i(TAG) { "MainActivity.onCreate: 应用启动" }

        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        enableEdgeToEdge()
        setContent {
            TalkifyTheme {
                val versionName = remember { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" }
                val navController = rememberNavController()

                ObserveRouteForTelemetry(navController)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TelemetryCaptureHost(modifier = Modifier.fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = ROUTE_MAIN,
                            enterTransition = {
                                slideInHorizontally(animationSpec = tween(250)) { it / 4 } +
                                        fadeIn(animationSpec = tween(250))
                            },
                            exitTransition = { fadeOut(animationSpec = tween(200)) },
                            popEnterTransition = { fadeIn(animationSpec = tween(250)) },
                            popExitTransition = {
                                slideOutHorizontally(animationSpec = tween(250)) { it / 4 } +
                                        fadeOut(animationSpec = tween(200))
                            }
                        ) {
                            composable(ROUTE_MAIN) {
                                MainScreen(
                                    modifier = Modifier.fillMaxSize(),
                                    onAboutClick = {
                                        getSharedPreferences("talkify_app_config", MODE_PRIVATE)
                                            .edit()
                                            .putBoolean("has_opened_about_page", true)
                                            .apply()
                                        navController.navigate(ROUTE_ABOUT)
                                    }
                                )
                            }
                            composable(ROUTE_ABOUT) {
                                AboutScreen(
                                    onBackClick = { navController.popBackStack() },
                                    versionName = versionName
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 观察导航路由变化，对齐 script.js 的 SPA 行为：
     * 路由指向新页面时补发 pageview（驱动 Pages 面板），并通知 recorder
     * 产出 url-change 事件与重拍回放快照
     */
    @Composable
    private fun ObserveRouteForTelemetry(navController: NavController) {
        DisposableEffect(navController) {
            // 以附着时的当前路由初始化，避免恢复/重组时对同一页面重复上报
            var lastUrl = telemetryRouteOf(navController.currentDestination?.route)?.first
            val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                val target = telemetryRouteOf(destination.route) ?: return@OnDestinationChangedListener
                if (target.first == lastUrl) return@OnDestinationChangedListener
                lastUrl = target.first
                TalkifyTelemetry.trackPageView(target.first, target.second)
                UmamiRecorder.onUrlChanged(target.first, target.second)
            }
            navController.addOnDestinationChangedListener(listener)
            onDispose { navController.removeOnDestinationChangedListener(listener) }
        }
    }

    private fun telemetryRouteOf(route: String?): Pair<String, String?>? = when (route) {
        ROUTE_MAIN -> Pair("/", "Main")
        ROUTE_ABOUT -> Pair("/about", "About")
        else -> null
    }
}
