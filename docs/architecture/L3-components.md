# C4 Architecture — Level 3: Components

> **C4 Level 3** zooms into each container and shows the major internal components, their responsibilities, and how they collaborate. This is the most useful level for developers navigating the codebase.

---

## Container: UI Container

```
UI Container (Jetpack Compose)
│
├── Navigation Component
│   └── NavigationGraph            ← NavHost; defines all routes and screen arguments
│
├── Home Component
│   ├── HomeScreen                 ← Task grid; entry point for all features
│   └── HomeViewModel              ← Loads tasks, model states, settings
│
├── LLM Chat Component
│   ├── LlmChatScreen              ← Multi-turn chat; streams tokens; shows skill results
│   └── LlmChatViewModel           ← Manages chat history, sends messages, handles tool calls
│
├── Prompt Lab Component
│   ├── LlmSingleTurnScreen        ← Single-turn with parameter sliders
│   └── LlmSingleTurnViewModel     ← Controls inference params, measures performance
│
├── Model Manager Component
│   ├── ModelManagerScreen         ← Browse, download, import, delete models
│   └── ModelManagerViewModel      ← Orchestrates downloads, OAuth sign-in, import
│
├── Benchmark Component
│   ├── BenchmarkScreen            ← Configure and display benchmark runs
│   └── BenchmarkViewModel         ← Executes runs, saves/loads historical results
│
├── Settings Component
│   └── SettingsViewModel          ← Global theme, feature flags, ToS; shared across all screens
│
├── Theme Component
│   ├── GalleryTheme               ← MaterialTheme wrapper (light/dark/dynamic color)
│   ├── Color.kt                   ← Color tokens
│   └── Typography.kt              ← Text style definitions
│
└── Common UI Components
    ├── GalleryAppTopBar           ← Reusable top bar with action items
    ├── MarkdownText               ← CommonMark renderer for model responses
    ├── DownloadProgressBar        ← Animated download progress with cancel
    ├── ModelInfoCard              ← Model size, RAM, task badges
    ├── InferenceMetricsRow        ← tokens/sec, latency display
    ├── ImageAttachmentRow         ← Horizontal scrollable image thumbnails
    ├── ConfirmDialog              ← Generic confirmation modal
    └── LoadingIndicator           ← Spinner for async states
```

### Component Interactions (UI Container)

```
User Action
    │
    ▼
[Screen Composable]  ──collectAsStateWithLifecycle──►  [ViewModel.uiState]
    │                                                        │
    │ user event (click, type)                               │ reads from
    ▼                                                        ▼
[ViewModel.onXxx()]  ─────────────────────────────►  [DataStoreRepository]
    │                                                  [DownloadRepository]
    │ suspend call / Flow                               [LlmModelHelper]
    ▼
[UiState update] ──► Screen recomposition
```

---

## Container: Domain / Data Container

```
Domain / Data Container
│
├── Model Allowlist Component
│   ├── ModelAllowlist             ← Parses model_allowlist.json; produces Model list
│   └── AllowlistedModel           ← Raw JSON-mapped model descriptor
│
├── Skill Allowlist Component
│   ├── SkillAllowlist             ← Loads skills from assets/skills/ directory
│   └── AllowlistedSkill           ← Maps to proto Skill message
│
├── Data Models Component
│   ├── Model.kt                   ← LLM model descriptor + runtime state
│   ├── LlmModelConfig             ← Inference hyperparameter defaults + capability flags
│   ├── Task.kt                    ← Task descriptor (type, name, icon, compatible models)
│   ├── Category.kt                ← Task grouping (Chat, Creativity, Utilities, etc.)
│   ├── Config.kt                  ← User-adjustable inference parameter descriptors
│   ├── ConfigValue.kt             ← Sealed class: IntValue | FloatValue | BooleanValue | StringValue
│   ├── Types.kt                   ← Shared enums: TaskType, DownloadStatus, AcceleratorType
│   ├── Consts.kt                  ← String/numeric constants (DataStore file names, keys)
│   └── AppBarAction.kt            ← Action item descriptor for GalleryAppTopBar
│
├── DataStore Repository Component
│   ├── DataStoreRepository        ← Single source of truth for all persisted state
│   ├── SettingsSerializer         ← Proto ↔ Settings
│   ├── UserDataSerializer         ← Proto ↔ UserData
│   ├── SkillsSerializer           ← Proto ↔ Skills
│   ├── BenchmarkResultsSerializer ← Proto ↔ BenchmarkResults
│   └── CutoutsSerializer          ← Proto ↔ cutout data
│
└── Download Repository Component
    ├── DownloadRepository         ← Enqueues/cancels/monitors downloads via WorkManager
    └── DownloadWorker             ← CoroutineWorker: fetches file, reports progress
```

### Allowlist Loading Flow

```
App startup
    │
    ▼
GalleryApplication.onCreate()
    │
    ├── ModelAllowlist.fromAssets(context)
    │       reads model_allowlist.json from APK assets
    │       produces List<Model> with state = NOT_DOWNLOADED
    │
    └── SkillAllowlist.fromAssets(context)
            scans assets/skills/ directory
            reads each SKILL.md header
            produces List<AllowlistedSkill>
```

---

## Container: Runtime Container

```
Runtime Container
│
├── LLM Model Helper Component
│   ├── LlmModelHelper             ← Primary facade: load, unload, generate, benchmark
│   └── ModelHelperExt             ← Prompt formatting, token estimation, accelerator resolution
│
├── AI Core Component
│   ├── AiCoreManager              ← Detects AI Core availability; delegates to AI Core SDK
│   └── AiCoreSession              ← Wraps AI Core inference session lifecycle
│
└── Inference Session Component
    └── LlmInference               ← MediaPipe LLM Inference API wrapper (from litertlm)
                                     • loadModel(path, options)
                                     • generateResponseAsync(prompt, listener)
                                     • close()
```

### Model Lifecycle Component Interactions

```
ModelManagerViewModel.onModelSelected(model)
          │
          ▼
LlmModelHelper.loadModel(model, accelerator)
          │
          ├── AiCoreManager.isAvailable()
          │       → if true AND model supports it: use AI Core path
          │       → else: standard LiteRT path
          │
          ├── Build LlmInference.Options
          │       modelPath: String (from Model Storage)
          │       accelerator: CPU | GPU | NPU
          │       maxTokens, topK, topP, temperature
          │
          └── LlmInference.createFromOptions(context, options)
                  ← blocks thread; GPU memory allocated (~2–8 GB)
                  ← inference session ready

LlmChatViewModel.sendMessage(text, images)
          │
          ▼
LlmModelHelper.generateResponse(prompt, images, config)
          │
          ├── ModelHelperExt.buildChatPrompt(history, systemPrompt)
          ├── (if images) encode to base64 tokens
          │
          └── LlmInference.generateResponseAsync(
                    prompt,
                    partialResultListener = { token, done ->
                        emit(token)       // Flow<String>
                        if (done) close stream
                    }
              )
```

---

## Container: Skills Container

```
Skills Container
│
├── Agent Chat Component
│   ├── AgentChatTask              ← Defines task metadata + system prompt template
│   └── AgentChatViewModel         ← Orchestrates tool call detection + dispatch
│
├── Mobile Actions Component
│   ├── MobileActionsTask          ← System prompt with device state context
│   ├── MobileActionsTools         ← @Tool-annotated functions (LLM-callable interface)
│   ├── MobileActionsViewModel     ← Executes actions on Android OS
│   └── Actions.kt                 ← ActionType enum + Action sealed class hierarchy
│
├── Tiny Garden Component
│   ├── TinyGardenTask             ← Configures the mini-game session
│   └── TinyGardenViewModel        ← Bridges AI responses to game WebView state
│
└── Example Custom Task Component
    └── ExampleCustomTask          ← Reference implementation for third-party developers
```

### Skill Tool Call Dispatch

```
LlmChatViewModel receives streamed token containing tool call JSON
          │
          ▼
AgentChatViewModel.detectToolCall(tokenBuffer)
          │
          ├── tool == "run_js"
          │       │
          │       └── SkillRunner.executeJsSkill(skillName, params)
          │               │
          │               ├── load skill index.html into WebView
          │               └── WebView.evaluateJavascript(
          │                       "window['ai_edge_gallery_get_result'](...)",
          │                       callback → parseSkillResult(json)
          │                   )
          │
          ├── tool == "run_intent"
          │       │
          │       └── MobileActionsViewModel.performAction(actionType, params)
          │               │
          │               └── build Android Intent → startActivity()
          │
          └── result injected back into LLM context as assistant turn
```

### Mobile Actions — Action Hierarchy

```
Action (sealed)
├── TakePhotoAction       → CameraX capture → return base64 image
├── SendEmailAction       → Intent.ACTION_SENDTO
├── OpenSettingsAction    → Intent.ACTION_SETTINGS
├── SetAlarmAction        → Intent.ACTION_SET_ALARM
├── SearchWebAction       → Intent.ACTION_WEB_SEARCH
└── [extensible: add new Action subclass + @Tool function]
```

---

## Container: WebView Container

```
WebView Container
│
├── WebView Pool Component
│   └── WebViewPool                ← Maintains a pool of reusable WebView instances
│                                    (avoids cold-start overhead per skill call)
│
├── Skill Execution Component
│   ├── index.html                 ← Entry HTML; registers JS window function
│   └── index.js                  ← Optional: separated JS logic
│
└── Persistent UI Component
    └── webview.html / dashboard.html  ← Persistent UI panels (mood-tracker, text-spinner)
                                          displayed inline in chat
```

### WebView Security Boundaries

```
Android App Process
│
├── Main WebView (Persistent Skill UI)
│   ├── Origin: file:///android_asset/skills/<name>/assets/
│   ├── localStorage: isolated per skill origin
│   └── Network: allowed (for skill API calls)
│
└── Execution WebView (JS Tool Call)
    ├── Origin: file:///android_asset/skills/<name>/scripts/
    ├── JavaScript bridge: window['ai_edge_gallery_get_result'] only
    └── No access to: main app memory, other skills' storage
```

---

## Container: Proto DataStore Container

```
Proto DataStore Container
│
├── Settings Store Component
│   ├── File: settings.pb
│   ├── Schema: Settings proto (theme, history, imported models, feature flags)
│   └── Serializer: SettingsSerializer
│
├── User Data Store Component
│   ├── File: user_data.pb
│   ├── Schema: UserData proto (OAuth tokens, skill API key secrets)
│   └── Serializer: UserDataSerializer
│
├── Skills Store Component
│   ├── File: skills.pb
│   ├── Schema: Skills proto (list of Skill messages)
│   └── Serializer: SkillsSerializer
│
├── Benchmark Results Store Component
│   ├── File: benchmark_results.pb
│   ├── Schema: BenchmarkResults proto
│   └── Serializer: BenchmarkResultsSerializer
│
└── Cutouts Store Component
    ├── File: cutouts.pb
    ├── Schema: display cutout descriptors
    └── Serializer: CutoutsSerializer
```

### DataStore Read/Write Pattern

```kotlin
// All reads: reactive Flow (collected by ViewModel)
dataStoreRepository.settingsFlow
    .map { settings -> settings.theme }
    .collectLatest { theme -> _uiState.update { it.copy(theme = theme) } }

// All writes: coroutine-safe transform
dataStoreRepository.updateSettings { current ->
    current.toBuilder().setTheme(Theme.THEME_DARK).build()
}
```

---

## Container: WorkManager Container

```
WorkManager Container
│
├── Download Worker Component
│   ├── DownloadWorker             ← CoroutineWorker; performs chunked HTTP download
│   ├── Input data:
│   │   ├── KEY_MODEL_URL          ← Remote download URL
│   │   ├── KEY_MODEL_NAME         ← Unique model identifier (used as work name)
│   │   ├── KEY_DEST_PATH          ← Local file path to write
│   │   └── KEY_ACCESS_TOKEN       ← Optional Bearer token (gated models)
│   └── Output / Progress data:
│       ├── KEY_BYTES_DOWNLOADED   ← Progress reporting
│       ├── KEY_TOTAL_BYTES        ← Total file size
│       └── KEY_ERROR              ← Error message on failure
│
└── Foreground Notification Component
    └── ForegroundInfo             ← Shows persistent notification during download
                                     with progress % and Cancel action
```

### Download State Machine

```
[Idle]
  │  DownloadRepository.downloadModel(model, token)
  ▼
[ENQUEUED]  ←── WorkManager queues request
  │  Worker starts executing
  ▼
[RUNNING]  ←── Progress updates stream via WorkInfo
  │
  ├── [SUCCEEDED]  → DownloadStatus.Succeeded → model ready to use
  ├── [FAILED]     → DownloadStatus.Failed(reason) → error shown in UI
  └── [CANCELLED]  → DownloadStatus.Cancelled → partial file deleted
```

---

## Dependency Injection (Hilt) — Component Wiring

```
@HiltAndroidApp
GalleryApplication
│
├── @Singleton
│   ├── DataStoreRepository        ← injected into all ViewModels
│   ├── DownloadRepository         ← injected into ModelManagerViewModel
│   ├── ModelAllowlist             ← injected into HomeViewModel, ModelManagerViewModel
│   └── SkillAllowlist             ← injected into AgentChatViewModel
│
├── @ViewModelScoped
│   ├── LlmModelHelper             ← one instance per ViewModel lifecycle
│   └── AiCoreManager              ← one instance per ViewModel lifecycle
│
└── @ActivityRetainedScoped
    └── SettingsViewModel          ← shared across all screens in the Activity
```

---

## Cross-Container Component Interactions Summary

| From Component | To Component | Interaction |
|---------------|-------------|------------|
| HomeViewModel | ModelAllowlist | Reads task-compatible model list |
| ModelManagerViewModel | DownloadRepository | Triggers/cancels model downloads |
| ModelManagerViewModel | DataStoreRepository | Reads/writes imported model config |
| LlmChatViewModel | LlmModelHelper | Sends prompts; collects token stream |
| LlmChatViewModel | AgentChatViewModel | Delegates tool call detection |
| AgentChatViewModel | SkillRunner (WebView) | Executes JS skill |
| AgentChatViewModel | MobileActionsViewModel | Executes native action |
| LlmModelHelper | AiCoreManager | Queries AI Core availability |
| LlmModelHelper | LlmInference (LiteRT) | Delegates actual inference |
| DownloadWorker | Model Storage | Writes `.task` file |
| DownloadWorker | Hugging Face Hub | HTTPS GET download |
| DataStoreRepository | Proto DataStore files | Proto serialized reads/writes |
| SkillRunner | WebView Pool | Acquires/releases WebView |
| WebView (skill) | External Skill APIs | HTTPS `fetch()` calls |
