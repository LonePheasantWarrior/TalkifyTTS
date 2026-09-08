# Talkify UI 动现代化第一阶段：Expressive 弹簧动效 + 共享元素过渡

## Context

用户已批准 UI 现代化第一阶段：接入 M3 Expressive 动效 + 主界面↔关于页共享元素过渡，并以模型下载进度条作补充。

**关键事实（已逐一从源码/Gradle 缓存核实）**：
- material3 解析为 **1.4.0**（BOM 2026.08.00）。`MotionScheme` 是 **internal**，公开 `MaterialTheme()` 无 motionScheme 参数；LoadingIndicator/FloatingToolbar/ButtonGroup/Squiggly/MaterialShapes **均无公开组件**。因此"接入 Expressive"落地为：从 material3 源码提取官方 Expressive 弹簧参数（`ExpressiveMotionTokens.kt`），在项目内自建 `TalkifyMotion` 规格，替换自定义动画中的 tween。
- 官方 Expressive 参数：spatial：default(0.8f, 380f) / fast(0.6f, 800f) / slow(0.8f, 200f)；effects：default(1f, 1600f) / fast(1f, 3800f) / slow(1f, 800f)。位置/尺寸/旋转型用 spatial，颜色/透明度/数值型用 effects。
- animation 1.12.0 的 SharedTransition API 需 `@OptIn(ExperimentalSharedTransitionApi::class)`；navigation-compose 2.10.0 的 `composable{}` receiver 是 `AnimatedContentScope`（AnimatedVisibilityScope 子类型）。
- 下载进度数据已在 UI 层可达：`DownloadProgress(displayName, modelId?, progress 0-100, isCompleted)`，`MainViewModel.downloadProgress: StateFlow<DownloadProgress?>`（MainScreen.kt:201 已收集，仅用于完成 Snackbar）。
- 用户 UI 克制原则：只在有明确场景处加动效；EqualizerBars 品牌动效与 NavHost 转场保持不动。

## 改动清单（9 个文件，1 新建）

### 1. 新建 `ui/theme/Motion.kt`
- `object TalkifyMotion`：6 个 Float spec + 泛型工厂 `spatialDefaultOf<T>(threshold)` / `spatialFastOf<T>(threshold)` / `effectsDefaultOf<T>(threshold)`（threshold 用于 IntSize/IntOffset 的 VisibilityThreshold）。
- 共享元素 key 常量：`SharedKeyBrandMark` / `SharedKeyBrandTitle`。
- `@Composable fun Modifier.sharedBrandBounds(key, sts: SharedTransitionScope?, avs: AnimatedVisibilityScope?)`：两 scope 均非空时 `with(sts) { sharedBounds(rememberSharedContentState(key), avs) }`，否则原样返回（预览/无共享零影响）。文件级 `@OptIn(ExperimentalSharedTransitionApi::class)`。

### 2. `MainActivity.kt`（导航接线，NavHost 在 63-95）
- NavHost 外包 `SharedTransitionLayout`（TelemetryCaptureHost 内部）。
- `composable(ROUTE_MAIN)` 调 MainScreen 时传 `sharedTransitionScope = this@SharedTransitionLayout, animatedVisibilityScope = this`；ROUTE_ABOUT 同理。
- `onCreate` 加 `@OptIn(ExperimentalSharedTransitionApi::class)`。现有 4 个转场 tween（66-75）保持不变。

### 3. `MainScreen.kt`
- 签名（104-108）追加两个可空 scope 参数；`@OptIn` 追加。
- 标题区（279-302）：`EqualizerBars`（283）传 `modifier = Modifier.sharedBrandBounds(SharedKeyBrandMark, ...)`；`Text(app_name)`（292）加 `sharedBrandBounds(SharedKeyBrandTitle, ...)`。副标题不共享。
- 两个 banner（326-344）：`slideInVertically`/`slideOutVertically` 显式 `animationSpec = TalkifyMotion.spatialDefaultOf(IntOffset.VisibilityThreshold)`。
- `ConfigBottomSheet` 调用（503-519）追加 `downloadProgress = downloadProgress`。

### 4. `AboutScreen.kt`
- 签名（86-89）追加同样两个参数；`@OptIn` 追加。
- 应用图标 `Image`（179-186）modifier 链尾加 `sharedBrandBounds(SharedKeyBrandMark, ...)`；`Text(app_name, headlineMedium)`（190-194）加 `sharedBrandBounds(SharedKeyBrandTitle, ...)`。

### 5. `ui/components/ConfigEditor.kt`
- 签名（79-88）追加 `downloadingModelProgress: DownloadProgress? = null`。
- `regularItems.forEach`（117-131）内 `ConfigItemEditor` 之后：当 `item.key == "model_id" && progress != null && !progress.isCompleted` 渲染 spring 动画 `LinearProgressIndicator(progress = { animated })` + 百分比文案（`animateFloatAsState` 用 `effectsDefault`，damping=1.0 平滑无回弹）。
- 箭头旋转（183-187）`tween(300)` → `TalkifyMotion.spatialFast`。
- `AnimatedVisibility`（236-241）：expand/shrink → `spatialDefaultOf(IntSize.VisibilityThreshold)`；fadeIn/fadeOut → `effectsDefaultOf()`。

### 6. `ui/components/ConfigBottomSheet.kt`
- 签名（71-80）追加 `downloadProgress: DownloadProgress? = null`，透传给 ConfigEditor（212-253 调用点）。
- **禁止**把 downloadProgress 加入 `configItems` 的 remember keys（157 行）——下载期间每次进度变化重建 configItems 会重置用户未保存的编辑；"[下载中...]"文字后缀（473-479）维持现状，进度条提供实时反馈。

### 7. `ui/components/ProviderSelector.kt`
- `animateColorAsState`（180-188）`tween(250)` → `TalkifyMotion.effectsDefaultOf()`。

### 8. `ui/components/VoicePreview.kt`
- 播放钮按压 scale（271-278）`spring(MediumBouncy/StiffnessMediumLow)` → `TalkifyMotion.spatialFast`（统一 Expressive 规格）。
- 4 处 `animateColorAsState`（208-225 语音 chip、279-296 播放钮）`tween(250)` → `effectsDefaultOf()`。
- `AnimatedContent`（319-333）`tween(150)`：scaleIn/scaleOut → `spatialFast`，fadeIn/fadeOut → `effectsDefaultOf()`。

### 9. `res/values/strings.xml`
- 新增 `<string name="model_download_progress">正在下载 %1$s · %2$d%%</string>`（displayName + 百分比）。

## 明确不做
- 不换 NavHost 转场（共享元素自带 spring boundsTransform，避免双重节奏叠加）。
- EqualizerBars 内部 infinite 动效不动。
- 不新增依赖（graphics-shapes 等 1.4.0 不可用，形状变形跳过）。
- 现有两处 `CircularProgressIndicator`（AboutScreen 235、ConfigBottomSheet 203）保留：1.4.0 无 expressive 替代组件，小尺寸 spinner 合理。

## 已知边界
- `@ExperimentalSharedTransitionApi`：仅编译期 OptIn，随 BOM 升级跟进。
- ModalBottomSheet/Dialog 是独立窗口，不参与共享元素。
- predictive back 系统预览不走 NavHost 转场，该路径共享元素动画缺席（不崩溃）。
- LargeTopAppBar 折叠中触发导航时 sharedBounds 取当前 bounds，需真机确认视觉。
- minSdk 30 无影响（纯 Compose 层实现）。

## 验证
1. `./gradlew :app:compileDebugKotlin` → `:app:assembleDebug` 编译通过。
2. `./gradlew lint`（基线 4 个既有错误，不得新增）。
3. 真机/模拟器人工清单（本环境无法运行 UI，需用户真机配合）：
   - 高级设置箭头 spring 回弹；面板展开/收起无跳变
   - 供应商行选中色、语音 chip 色、播放钮配色平滑（无 tween 线性感）
   - 播放钮按压缩放回弹；播放/停止图标 spring 切换
   - 两个 banner 进出场自然
   - 点主标题进关于页：EqualizerBars↔应用图标、Talkify↔Talkify sharedBounds 过渡；返回反向成立；副标题等其余内容 slide+fade 正常、无标题双影
   - 选择未下载模型保存触发下载：配置弹窗内进度条平滑推进、完成后消失 + Snackbar 弹出
   - EqualizerBars 品牌条与网络检查加载不变；返回手势无崩溃