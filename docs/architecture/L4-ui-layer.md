# L4 Architecture — UI Layer

> **C4 Model Level 4 — UI Layer:** Jetpack Compose screens, ViewModels, and the navigation graph.

---

## Overview

The UI layer follows the **MVVM (Model-View-ViewModel)** pattern using Jetpack Compose for the view layer and `StateFlow`/`collectAsStateWithLifecycle` for reactive state management.

```
ui/
├── navigation/     ← NavHost and Screen sealed class (route definitions)
├── home/           ← Task/model selection hub
├── llmchat/        ← Multi-turn LLM chat interface
├── llmsingleturn/  ← Prompt Lab (single-turn inference)
├── modelmanager/   ← Download, import, and manage models
├── benchmark/      ← Performance benchmarking
├── common/         ← Shared composables (buttons, loaders, markdown…)
├── icon/           ← Custom icon helpers
└── theme/          ← GalleryTheme, Color, Typography
```

---

## Navigation Graph

```mermaid
flowchart LR
    Home["HomeScreen\n(route: home)"]
    ModelManager["ModelManagerScreen\n(route: model_manager/{taskType})"]
    LlmChat["LlmChatScreen\n(route: llm_chat/{modelId})"]
    LlmSingleTurn["LlmSingleTurnScreen\n(route: llm_single_turn/{modelId})"]
    Benchmark["BenchmarkScreen\n(route: benchmark/{modelId})"]

    Home --> ModelManager
    Home --> Benchmark
    ModelManager --> LlmChat
    ModelManager --> LlmSingleTurn
```

### Screen Routes

| Screen Composable | Route String | Key Arguments |
|------------------|--------------|---------------|
| `HomeScreen` | `home` | — |
| `ModelManagerScreen` | `model_manager/{taskType}` | `taskType: String` |
| `LlmChatScreen` | `llm_chat/{modelId}` | `modelId: String` |
| `LlmSingleTurnScreen` | `llm_single_turn/{modelId}` | `modelId: String` |
| `BenchmarkScreen` | `benchmark/{modelId}` | `modelId: String` |

---

## Home Screen

```mermaid
classDiagram
    class HomeScreen {
        <<Composable>>
        +viewModel: HomeViewModel
        +onNavigateToTask(task, model)
        +onNavigateToModelManager(taskType)
    }
    class HomeViewModel {
        <<ViewModel>>
        -dataStoreRepository: DataStoreRepository
        -modelAllowlist: ModelAllowlist
        +uiState: StateFlow~HomeUiState~
        +onModelSelected(model: Model)
        +onTaskSelected(task: Task)
        +onSettingsChanged(settings: Settings)
    }
    class HomeUiState {
        +tasks: List~Task~
        +selectedModel: Model?
        +isLoading: Boolean
        +settings: Settings
    }

    HomeScreen --> HomeViewModel : observes
    HomeViewModel --> HomeUiState : emits
    HomeViewModel --> DataStoreRepository : reads
    HomeViewModel --> ModelAllowlist : reads
```

**HomeScreen responsibilities:**
- Display categorized task tiles (AI Chat, Prompt Lab, Ask Image, etc.)
- Show model download state badges on each task
- Entry point for navigating to Model Manager or directly into a task

---

## LLM Chat Screen

```mermaid
classDiagram
    class LlmChatScreen {
        <<Composable>>
        +viewModel: LlmChatViewModel
        +modelId: String
        +onBack()
    }
    class LlmChatViewModel {
        <<ViewModel>>
        -llmModelHelper: LlmModelHelper
        -dataStoreRepository: DataStoreRepository
        +uiState: StateFlow~LlmChatUiState~
        +chatHistory: List~ChatMessage~
        +sendMessage(text: String, images: List~Bitmap~?)
        +stopGeneration()
        +clearHistory()
        +onThinkingModeToggled(enabled: Boolean)
    }
    class LlmChatUiState {
        +isGenerating: Boolean
        +currentModel: Model
        +thinkingModeEnabled: Boolean
        +error: String?
    }
    class ChatMessage {
        +role: Role
        +content: String
        +images: List~Bitmap~?
        +timestamp: Long
        +isThinking: Boolean
    }
    class Role {
        <<enum>>
        USER
        MODEL
        SYSTEM
    }

    LlmChatScreen --> LlmChatViewModel : observes
    LlmChatViewModel --> LlmChatUiState : emits
    LlmChatViewModel --> LlmModelHelper : delegates to
    ChatMessage "1" --> "1" Role : typed by
```

**LlmChatScreen responsibilities:**
- Multi-turn conversational interface with streaming token rendering
- Image attachment support (camera or gallery)
- Thinking Mode toggle (shows model's reasoning chain)
- Markdown rendering of model responses
- Agent Skill results display (WebView, images, plain text)

---

## LLM Single-Turn Screen (Prompt Lab)

```mermaid
classDiagram
    class LlmSingleTurnScreen {
        <<Composable>>
        +viewModel: LlmSingleTurnViewModel
        +modelId: String
        +onBack()
    }
    class LlmSingleTurnViewModel {
        <<ViewModel>>
        -llmModelHelper: LlmModelHelper
        -dataStoreRepository: DataStoreRepository
        +uiState: StateFlow~LlmSingleTurnUiState~
        +systemPrompt: String
        +userPrompt: String
        +sendPrompt()
        +onSystemPromptChanged(text: String)
        +onUserPromptChanged(text: String)
        +onTemperatureChanged(value: Float)
        +onTopKChanged(value: Int)
        +onTopPChanged(value: Float)
        +onMaxTokensChanged(value: Int)
    }
    class LlmSingleTurnUiState {
        +isGenerating: Boolean
        +response: String
        +streamedTokens: Int
        +inferenceTimeMs: Long
        +tokensPerSecond: Float
        +error: String?
    }

    LlmSingleTurnScreen --> LlmSingleTurnViewModel : observes
    LlmSingleTurnViewModel --> LlmSingleTurnUiState : emits
```

**LlmSingleTurnScreen responsibilities:**
- Editable system prompt + user prompt fields
- Inference parameter controls (temperature, top-k, top-p, max tokens)
- Real-time performance metrics display (tokens/sec, latency)

---

## Model Manager Screen

```mermaid
classDiagram
    class ModelManagerScreen {
        <<Composable>>
        +viewModel: ModelManagerViewModel
        +taskType: String
        +onModelSelected(model: Model)
        +onBack()
    }
    class ModelManagerViewModel {
        <<ViewModel>>
        -downloadRepository: DownloadRepository
        -dataStoreRepository: DataStoreRepository
        +uiState: StateFlow~ModelManagerUiState~
        +onDownloadModel(model: Model)
        +onCancelDownload(model: Model)
        +onDeleteModel(model: Model)
        +onImportModel(uri: Uri)
        +onSignIn()
        +onSignOut()
    }
    class ModelManagerUiState {
        +models: List~Model~
        +downloadStatuses: Map~String, DownloadStatus~
        +isSignedIn: Boolean
        +importedModels: List~ImportedModel~
    }

    ModelManagerScreen --> ModelManagerViewModel : observes
    ModelManagerViewModel --> ModelManagerUiState : emits
    ModelManagerViewModel --> DownloadRepository : uses
    ModelManagerViewModel --> DataStoreRepository : uses
```

**ModelManagerScreen responsibilities:**
- List allowlisted models with size and peak-memory info
- Show download progress bars
- Handle Hugging Face OAuth sign-in (gated models)
- Support importing local `.task` files

---

## Benchmark Screen

```mermaid
classDiagram
    class BenchmarkScreen {
        <<Composable>>
        +viewModel: BenchmarkViewModel
        +modelId: String
        +onBack()
    }
    class BenchmarkViewModel {
        <<ViewModel>>
        -llmModelHelper: LlmModelHelper
        -dataStoreRepository: DataStoreRepository
        +uiState: StateFlow~BenchmarkUiState~
        +onRunBenchmark(runs: Int, prefillTokens: Int, decodeTokens: Int)
        +onClearResults()
    }
    class BenchmarkUiState {
        +isRunning: Boolean
        +currentRun: Int
        +totalRuns: Int
        +results: List~LlmBenchmarkResult~
        +historicalResults: BenchmarkResults
        +error: String?
    }

    BenchmarkScreen --> BenchmarkViewModel : observes
    BenchmarkViewModel --> BenchmarkUiState : emits
    BenchmarkViewModel --> LlmModelHelper : uses
```

**BenchmarkScreen responsibilities:**
- Configurable benchmark runs (number of runs, prompt lengths)
- Displays per-run and aggregated statistics (prefill/decode speed, TTFT)
- Historical comparison charts across model versions

---

## Settings ViewModel (Global)

```mermaid
classDiagram
    class SettingsViewModel {
        <<ViewModel>>
        -dataStoreRepository: DataStoreRepository
        +settingsState: StateFlow~Settings~
        +onThemeChanged(theme: Theme)
        +onFeatureFlagChanged(key: String, value: Boolean)
        +onAcceptTos()
        +onAcceptGemmaTerms()
    }

    SettingsViewModel --> DataStoreRepository : uses
    GalleryApp --> SettingsViewModel : shared instance
```

`SettingsViewModel` is scoped to the Activity and shared across all screens for global settings like theme, feature flags, and terms acceptance.

---

## Theme System

```mermaid
classDiagram
    class GalleryTheme {
        <<Composable>>
        +darkTheme: Boolean
        +dynamicColor: Boolean
        +content: @Composable () -> Unit
    }
    class Color {
        +GalleryPrimary: Color
        +GallerySecondary: Color
        +GalleryBackground: Color
        +GallerySurface: Color
        +GalleryError: Color
    }
    class Typography {
        +displayLarge: TextStyle
        +headlineMedium: TextStyle
        +bodyLarge: TextStyle
        +labelSmall: TextStyle
    }

    GalleryTheme --> Color : uses
    GalleryTheme --> Typography : uses
```

- Supports Light, Dark, and System-Auto themes
- Dynamic color on Android 12+ (Material You)
- Stored in `Settings.theme` proto field; applied at `GalleryApp` level

---

## Common Shared Composables

| Composable | Purpose |
|-----------|---------|
| `LoadingIndicator` | Spinner for async operations |
| `ErrorBanner` | Dismissable error card |
| `MarkdownText` | Renders CommonMark markdown in chat |
| `ImageAttachmentRow` | Horizontal scrollable image thumbnails |
| `ConfirmDialog` | Generic confirm/cancel dialog |
| `DownloadProgressBar` | Model download progress with cancel |
| `ModelInfoCard` | Displays model metadata (size, RAM, tasks) |
| `InferenceMetricsRow` | Shows tokens/sec, latency stats |

---

## State Management Pattern

All screens follow this reactive pattern:

```kotlin
// In ViewModel:
private val _uiState = MutableStateFlow(InitialUiState())
val uiState: StateFlow<UiState> = _uiState.asStateFlow()

// In Composable:
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
```

Side effects (navigation, toasts) are communicated via `SharedFlow`:

```kotlin
// In ViewModel:
private val _events = MutableSharedFlow<UiEvent>()
val events: SharedFlow<UiEvent> = _events.asSharedFlow()

// In Composable:
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) {
            is UiEvent.NavigateTo -> navController.navigate(event.route)
            is UiEvent.ShowToast -> Toast.makeText(context, event.msg, Toast.LENGTH_SHORT).show()
        }
    }
}
```
