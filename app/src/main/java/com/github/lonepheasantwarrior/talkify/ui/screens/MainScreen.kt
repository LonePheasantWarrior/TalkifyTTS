package com.github.lonepheasantwarrior.talkify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.github.lonepheasantwarrior.talkify.ui.components.ConfigBottomSheet
import com.github.lonepheasantwarrior.talkify.ui.components.EngineSelector
import com.github.lonepheasantwarrior.talkify.ui.components.VoicePreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

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
    var availableVoices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<VoiceInfo?>(null) }
    val defaultInputText = stringResource(R.string.default_text)
    var inputText by remember { mutableStateOf(defaultInputText) }
    var isPlaying by remember { mutableStateOf(false) }
    var isConfigSheetOpen by remember { mutableStateOf(false) }

    val savedConfig = remember(currentEngine) {
        configRepository.getConfig(currentEngine)
    }

    LaunchedEffect(currentEngine) {
        val voices = voiceRepository.getVoicesForEngine(currentEngine)
        availableVoices = voices
        selectedVoice = availableVoices.find { it.voiceId == savedConfig.voiceId } ?: voices.firstOrNull()
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
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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
                onPlayClick = { isPlaying = true },
                onStopClick = { isPlaying = false },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    ConfigBottomSheet(
        isOpen = isConfigSheetOpen,
        onDismiss = { isConfigSheetOpen = false },
        currentEngine = currentEngine,
        configRepository = configRepository,
        voiceRepository = voiceRepository
    )
}
