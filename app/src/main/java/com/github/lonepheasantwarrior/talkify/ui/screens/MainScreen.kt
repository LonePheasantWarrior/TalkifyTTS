package com.github.lonepheasantwarrior.talkify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.repository.AlibabaCloudConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.repository.AlibabaCloudVoiceRepository
import com.github.lonepheasantwarrior.talkify.ui.components.ConfigDrawer
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
        AlibabaCloudVoiceRepository(context)
    }

    val configRepository: EngineConfigRepository = remember {
        AlibabaCloudConfigRepository(context)
    }

    val defaultEngine = TtsEngine(
        id = "ali_bailian_tongyi",
        name = "通义千问3语音合成",
        provider = "阿里云百炼"
    )

    val availableEngines = listOf(defaultEngine)

    var currentEngine by remember { mutableStateOf(defaultEngine) }
    var availableVoices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf<VoiceInfo?>(null) }
    var inputText by remember { mutableStateOf("你好，这是语音合成的测试文本。") }
    var isPlaying by remember { mutableStateOf(false) }
    var isDrawerOpen by remember { mutableStateOf(false) }

    val savedConfig = remember(currentEngine) {
        configRepository.getConfig(currentEngine)
    }

    androidx.compose.runtime.LaunchedEffect(currentEngine) {
        val voices = voiceRepository.getVoicesForEngine(currentEngine)
        availableVoices = voices
        selectedVoice = availableVoices.find { it.voiceId == savedConfig.voiceId } ?: voices.firstOrNull()
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Talkify",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Text(
                            text = "AI 语音合成",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isDrawerOpen = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                },
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
                onEngineSelected = { engine -> currentEngine = engine },
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

    ConfigDrawer(
        isDrawerOpen = isDrawerOpen,
        onDrawerClose = { isDrawerOpen = false },
        currentEngine = currentEngine,
        configRepository = configRepository,
        voiceRepository = voiceRepository
    )
}
