package com.github.lonepheasantwarrior.talkify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngineRegistry
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo.Qwen3TtsVoiceRepository
import com.github.lonepheasantwarrior.talkify.service.TalkifyTtsDemoService
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.ui.components.ConfigBottomSheet
import com.github.lonepheasantwarrior.talkify.ui.components.EngineSelector
import com.github.lonepheasantwarrior.talkify.ui.components.VoicePreview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    isCheckingNetwork: Boolean = false
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val voiceRepository: VoiceRepository = remember {
        Qwen3TtsVoiceRepository(context)
    }

    val configRepository: EngineConfigRepository = remember {
        Qwen3TtsConfigRepository(context)
    }

    val appConfigRepository: AppConfigRepository = remember {
        SharedPreferencesAppConfigRepository(context)
    }

    val availableEngines = TtsEngineRegistry.availableEngines
    val defaultEngine = TtsEngineRegistry.defaultEngine

    var currentEngine by remember {
        mutableStateOf(defaultEngine)
    }

    val demoService = remember(currentEngine.id) {
        TalkifyTtsDemoService(currentEngine.id)
    }

    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(appConfigRepository) {
        val savedEngineId = appConfigRepository.getSelectedEngineId()
        if (savedEngineId != null) {
            TtsEngineRegistry.getEngine(savedEngineId)?.let { engine ->
                currentEngine = engine
            }
        } else {
            appConfigRepository.saveSelectedEngineId(defaultEngine.id)
        }
    }

    DisposableEffect(currentEngine.id) {
        demoService.setStateListener { state ->
            when (state) {
                TalkifyTtsDemoService.STATE_IDLE -> {
                    isPlaying = false
                }
                TalkifyTtsDemoService.STATE_PLAYING -> {
                    isPlaying = true
                }
                TalkifyTtsDemoService.STATE_STOPPED -> {
                    isPlaying = false
                }
                TalkifyTtsDemoService.STATE_ERROR -> {
                    isPlaying = false
                    scope.launch {
                        snackbarHostState.showSnackbar("播放失败，请检查配置")
                    }
                }
            }
        }
        onDispose { }
    }

    var availableVoices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<VoiceInfo?>(null) }
    val defaultInputText = stringResource(R.string.default_text)
    var inputText by remember { mutableStateOf(defaultInputText) }
    var isConfigSheetOpen by remember { mutableStateOf(false) }

    var savedConfig by remember { mutableStateOf(configRepository.getConfig(currentEngine.id)) }

    LaunchedEffect(currentEngine) {
        savedConfig = configRepository.getConfig(currentEngine.id)
        val voices = voiceRepository.getVoicesForEngine(currentEngine)
        availableVoices = voices
        selectedVoice = availableVoices.find { it.voiceId == savedConfig.voiceId } ?: voices.firstOrNull()
    }

    DisposableEffect(Unit) {
        onDispose {
            demoService.release()
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isConfigSheetOpen = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.cd_settings_button)
                )
            }
        },
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Talkify",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {},
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isCheckingNetwork) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.checking_network),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    EngineSelector(
                        currentEngine = currentEngine,
                        availableEngines = availableEngines,
                        onEngineSelected = { engine ->
                            currentEngine = engine
                            appConfigRepository.saveSelectedEngineId(engine.id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    VoicePreview(
                        inputText = inputText,
                        onInputTextChange = { inputText = it },
                        availableVoices = availableVoices,
                        selectedVoice = selectedVoice,
                        onVoiceSelected = { voice -> selectedVoice = voice },
                        isPlaying = isPlaying,
                        onPlayClick = {
                            if (inputText.isBlank()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("请输入要合成的文本")
                                }
                                return@VoicePreview
                            }

                            val config = savedConfig.copy(
                                voiceId = selectedVoice?.voiceId ?: savedConfig.voiceId
                            )

                            if (config.apiKey.isBlank()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("请先配置 API Key")
                                }
                                isConfigSheetOpen = true
                                return@VoicePreview
                            }

                            val params = SynthesisParams(
                                pitch = 1.0f,
                                speechRate = 1.0f,
                                volume = 1.0f
                            )

                            demoService.speak(inputText, config, params)
                            isPlaying = true
                        },
                        onStopClick = {
                            demoService.stop()
                            isPlaying = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    ConfigBottomSheet(
        isOpen = isConfigSheetOpen,
        onDismiss = { isConfigSheetOpen = false },
        currentEngine = currentEngine,
        configRepository = configRepository,
        voiceRepository = voiceRepository,
        onConfigSaved = {
            savedConfig = configRepository.getConfig(currentEngine.id)
        }
    )
}
