package com.github.lonepheasantwarrior.talkify.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.TtsProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSelector(
    currentProvider: TtsProvider,
    availableProviders: List<TtsProvider>,
    onProviderSelected: (TtsProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    // 跳过"半展开"中间锚点（Hidden -> Expanded），避免内容高于一屏时
    // 抽屉在中间/顶部锚点间来回切换引起的抖动（与 ConfigBottomSheet 保持一致）
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.current_model),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            TextButton(
                onClick = { showBottomSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = currentProvider.provider,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = stringResource(R.string.provider_format, currentProvider.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    if (showBottomSheet) {
        val scrollState = rememberScrollState()
        // 抽屉内容超高（大字体机型）时可上拉到屏幕顶端；此时继续上拉/上甩会触发
        // 顶部锚点的回弹弹簧反复振荡（表现为抽屉不停上下抖动）。这里在嵌套滚动
        // 链路中拦截"抽屉已完全展开时"的向上甩动速度，阻止 fling 速度传导到抽屉
        // 的回弹动画上，从根本上切断"过冲 -> 弹回 -> 再次过冲"的无限循环。
        val nestedScrollConnection = remember(sheetState, scrollState) {
            object : NestedScrollConnection {
                // 内容已滚到顶部时，向上甩动只能作用于抽屉边界，直接消费掉
                override suspend fun onPreFling(available: Velocity): Velocity {
                    val isFlingingUp = available.y < 0
                    val isExpanded = sheetState.targetValue == SheetValue.Expanded
                    val contentAtTop = scrollState.value <= 0
                    return if (isFlingingUp && isExpanded && contentAtTop) {
                        available
                    } else {
                        Velocity.Zero
                    }
                }

                // 内容 fling 后剩余的向上速度，同样在抽屉已展开时消费掉，
                // 避免其流入抽屉的回弹弹簧
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    val isFlingingUp = available.y < 0
                    val isExpanded = sheetState.targetValue == SheetValue.Expanded
                    return if (isFlingingUp && isExpanded) {
                        available
                    } else {
                        Velocity.Zero
                    }
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(nestedScrollConnection)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_provider),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 竖向排列的供应商选择列表
                availableProviders.forEach { provider ->
                    val isSelected = provider.id == currentProvider.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                onProviderSelected(provider)
                                showBottomSheet = false
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onProviderSelected(provider)
                                    showBottomSheet = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = provider.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = provider.provider,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { showBottomSheet = false },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 16.dp)
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
