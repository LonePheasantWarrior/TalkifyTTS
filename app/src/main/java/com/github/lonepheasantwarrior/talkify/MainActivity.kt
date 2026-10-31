package com.github.lonepheasantwarrior.talkify

import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.ScreenTracker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.telemetry.recorder.UmamiRecorder
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.components.TelemetryCaptureHost
import com.github.lonepheasantwarrior.talkify.ui.screens.AboutScreen
import com.github.lonepheasantwarrior.talkify.ui.screens.DialogueModeScreen
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"

        private const val ROUTE_MAIN = "main"
        private const val ROUTE_ABOUT = "about"
        private const val ROUTE_DIALOGUE_MODE = "dialogueMode"
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsLogger.i(TAG) { "MainActivity.onCreate: 应用启动" }

        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        enableEdgeToEdge()
        setContent {
            TalkifyTheme {
                val versionName = remember {
                    // minSdk 30：PackageInfoFlags 重载仅 API 33+ 存在，低版本走旧 int 重载
                    @Suppress("DEPRECATION")
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    } else {
                        packageManager.getPackageInfo(packageName, 0)
                    }
                    info.versionName ?: "1.0.0"
                }
                val navController = rememberNavController()

                ObserveRouteForTelemetry(navController)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TelemetryCaptureHost(modifier = Modifier.fillMaxSize()) {
                        SharedTransitionLayout {
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
                                            // fade 与 slide 同长：此前 200ms 先消失，尾段 50ms
                                            // 位移在不可见中进行，收尾读起来像被截断
                                            fadeOut(animationSpec = tween(250))
                                },
                                // 返回手势期间 NavHost 会用 SeekableTransitionState 按手指进度
                                // 搓动这套预测转场；不传则走默认的 scaleOut(0.7)（整页向中心
                                // 缩放）。与 popExit/popEnter 保持同一规格，松手提交后续播无跳变
                                predictivePopEnterTransition = {
                                    fadeIn(animationSpec = tween(250))
                                },
                                predictivePopExitTransition = { _ ->
                                    slideOutHorizontally(animationSpec = tween(250)) { it / 4 } +
                                            fadeOut(animationSpec = tween(250))
                                }
                            ) {
                                composable(ROUTE_MAIN) {
                                    MainScreen(
                                        modifier = Modifier.fillMaxSize(),
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this,
                                        onAboutClick = {
                                            // 经领域仓储持久化"关于页已打开"，键名收敛在仓储实现内
                                            SharedPreferencesAppConfigRepository(this@MainActivity)
                                                .setAboutPageOpened(true)
                                            navController.navigate(ROUTE_ABOUT) { launchSingleTop = true } // 快速连点防堆叠（P3-16）
                                        },
                                        onDialogueModeClick = {
                                            navController.navigate(ROUTE_DIALOGUE_MODE) { launchSingleTop = true }
                                        }
                                    )
                                }
                                composable(ROUTE_ABOUT) {
                                    AboutScreen(
                                        onBackClick = { navController.popBackStack() },
                                        versionName = versionName,
                                        sharedTransitionScope = this@SharedTransitionLayout,
                                        animatedVisibilityScope = this
                                    )
                                }
                                composable(ROUTE_DIALOGUE_MODE) {
                                    DialogueModeScreen(
                                        onBackClick = { navController.popBackStack() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 把 NavController 的真实路由同步给 [ScreenTracker]（pageview 的唯一责任方）
     * 与录制子系统。页面计数去重由 ScreenTracker 统一处理：
     * 旋转/重组不重复，温启动恢复关于页时上报关于页而非主页。
     */
    @Composable
    private fun ObserveRouteForTelemetry(navController: NavController) {
        DisposableEffect(navController) {
            val current = telemetryRouteOf(navController.currentDestination?.route)
            current?.let {
                ScreenTracker.onScreenChanged(it.first, it.second)
                UmamiRecorder.onUrlChanged(it.first, it.second)
            }
            val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                val target = telemetryRouteOf(destination.route) ?: return@OnDestinationChangedListener
                ScreenTracker.onScreenChanged(target.first, target.second)
                UmamiRecorder.onUrlChanged(target.first, target.second)
            }
            navController.addOnDestinationChangedListener(listener)
            onDispose { navController.removeOnDestinationChangedListener(listener) }
        }
    }

    private fun telemetryRouteOf(route: String?): Pair<String, String?>? = when (route) {
        ROUTE_MAIN -> Pair("/", "Main")
        ROUTE_ABOUT -> Pair("/about", "About")
        ROUTE_DIALOGUE_MODE -> Pair("/dialogueMode", "DialogueMode")
        else -> null
    }
}
