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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.screens.AboutScreen
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"
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

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "main",
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
                        composable("main") {
                            MainScreen(
                                modifier = Modifier.fillMaxSize(),
                                onAboutClick = {
                                    getSharedPreferences("talkify_app_config", MODE_PRIVATE)
                                        .edit()
                                        .putBoolean("has_opened_about_page", true)
                                        .apply()
                                    navController.navigate("about")
                                }
                            )
                        }
                        composable("about") {
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
