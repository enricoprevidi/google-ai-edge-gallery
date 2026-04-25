# C4 Architecture — Level 2: Containers

> **C4 Level 2** zooms into the system boundary and shows the deployable/runnable units (containers), their technology choices, and how they communicate. In a mobile app context, "containers" include the app process, embedded runtimes, storage engines, and web contexts.

---

## Container Diagram

```
╔══════════════════════════════════════════════════════════════════════════════╗
║  Google AI Edge Gallery — Android Application (System Boundary)             ║
║                                                                              ║
║  ┌────────────────────────────────────────────────────────────────────────┐  ║
║  │  Android App Process  (Kotlin/JVM, Hilt DI)                           │  ║
║  │                                                                        │  ║
║  │   ┌──────────────────┐    ┌─────────────────┐    ┌─────────────────┐  │  ║
║  │   │   UI Container   │    │  Domain/Data    │    │  Runtime        │  │  ║
║  │   │                  │    │  Container      │    │  Container      │  │  ║
║  │   │  Jetpack Compose │◄──►│                 │◄──►│                 │  │  ║
║  │   │  Screens +       │    │  Repositories   │    │  LiteRT LLM     │  │  ║
║  │   │  ViewModels      │    │  Data Models    │    │  Inference      │  │  ║
║  │   │  Navigation      │    │  Config/Consts  │    │  Engine         │  │  ║
║  │   └──────────────────┘    └────────┬────────┘    └────────┬────────┘  │  ║
║  │                                    │                      │           │  ║
║  │   ┌──────────────────┐             │                      │           │  ║
║  │   │  Skills          │             │                      │           │  ║
║  │   │  Container       │             │                      │           │  ║
║  │   │                  │             │                      │           │  ║
║  │   │  AgentChat       │             │                      │           │  ║
║  │   │  MobileActions   │             │                      │           │  ║
║  │   │  TinyGarden      │             │                      │           │  ║
║  │   └────────┬─────────┘             │                      │           │  ║
║  │            │                       │                      │           │  ║
║  └────────────┼───────────────────────┼──────────────────────┼───────────┘  ║
║               │                       │                      │              ║
║  ┌────────────▼──────┐  ┌─────────────▼────────┐  ┌─────────▼──────────┐  ║
║  │  WebView          │  │  Proto DataStore      │  │  Model Storage     │  ║
║  │  Container        │  │  Container            │  │  Container         │  ║
║  │                   │  │                       │  │                    │  ║
║  │  Chromium WebView │  │  Jetpack DataStore    │  │  Android File      │  ║
║  │  JS Engine        │  │  + Protocol Buffers   │  │  System (.task)    │  ║
║  │  Skill HTML/JS    │  │  settings.pb          │  │  /data/user/0/…/   │  ║
║  │                   │  │  user_data.pb         │  │  files/models/     │  ║
║  │                   │  │  skills.pb            │  │                    │  ║
║  │                   │  │  benchmark_results.pb │  │                    │  ║
║  └───────────────────┘  └───────────────────────┘  └────────────────────┘  ║
║                                                                              ║
║  ┌─────────────────────────────────────────────────────────────────────────┐ ║
║  │  WorkManager Container                                                  │ ║
║  │  (Background Process — survives app kill)                              │ ║
║  │  DownloadWorker  [CoroutineWorker + ForegroundService]                 │ ║
║  └─────────────────────────────────────────────────────────────────────────┘ ║
╚══════════════════════════════════════════════════════════════════════════════╝
         │                    │                    │
         ▼                    ▼                    ▼
  ┌─────────────┐   ┌──────────────────┐   ┌─────────────────┐
  │ Hugging     │   │ Firebase         │   │ Skill External  │
  │ Face Hub    │   │ Analytics / FCM  │   │ APIs            │
  │ (HTTPS)     │   │ (HTTPS SDK)      │   │ (HTTPS / skill) │
  └─────────────┘   └──────────────────┘   └─────────────────┘
```

---

## Container Descriptions

### 1. UI Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | Kotlin + Jetpack Compose |
| **Pattern** | MVVM — Composable screens + `ViewModel` + `StateFlow` |
| **Responsibility** | Render all user-facing screens; handle user input; display model output |
| **Screens** | Home, LlmChat, LlmSingleTurn (Prompt Lab), ModelManager, Benchmark |
| **Navigation** | Jetpack Navigation Compose; single `NavHostController` |
| **State** | Unidirectional data flow: ViewModel emits `StateFlow<UiState>`, Composables collect |
| **Communicates with** | Domain/Data Container (reads state, triggers actions), Runtime Container (send/receive inference) |

### 2. Domain / Data Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | Kotlin (pure JVM — no Android framework dependency) |
| **Responsibility** | Business rules, data models, repository interfaces, model/skill allowlists |
| **Key classes** | `Model`, `Task`, `Category`, `Config`, `DataStoreRepository`, `DownloadRepository` |
| **Communicates with** | UI Container (exposes `Flow`s), Runtime Container (provides model paths), Proto DataStore (reads/writes), WorkManager Container (enqueues downloads) |

### 3. Runtime Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | Kotlin + LiteRT (Google AI Edge) + MediaPipe LLM Inference API |
| **Responsibility** | Load `.task` model files into memory; run LLM inference; stream tokens; run benchmarks |
| **Key classes** | `LlmModelHelper`, `AiCoreManager`, `ModelHelperExt` |
| **Accelerators** | CPU (universal), GPU (OpenCL), NPU (device-specific), AI Core (Pixel 8+) |
| **Communicates with** | Domain/Data Container (receives model config), Model Storage Container (reads `.task` files), UI Container (streams `Flow<String>` tokens) |

### 4. Skills Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | Kotlin (orchestration) + HTML/JavaScript (skill logic) + Android Intents (native skills) |
| **Responsibility** | Intercept LLM tool calls; route to JS WebView or Android Intent; return results |
| **Key modules** | `AgentChatTask/ViewModel`, `MobileActionsTask/ViewModel/Tools`, `TinyGarden` |
| **Communicates with** | UI Container (renders skill results), Runtime Container (sends tool-call results back to LLM), WebView Container (executes JS), Android OS (fires Intents) |

### 5. WebView Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | Android `WebView` (Chromium-based) + JavaScript V8 engine |
| **Responsibility** | Execute skill JavaScript logic in an isolated web context |
| **Isolation** | Each skill runs in its own WebView; `localStorage` is per-skill |
| **JS Contract** | `window['ai_edge_gallery_get_result'](data: string): Promise<string>` |
| **Communicates with** | Skills Container (receives params, returns results), External Skill APIs (via `fetch()` over HTTPS) |

### 6. Proto DataStore Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | Jetpack DataStore + Protocol Buffers 3 (Java Lite) |
| **Responsibility** | Persist all app state across sessions |
| **Stores** | `settings.pb` (theme, feature flags, imported models), `user_data.pb` (tokens, skill secrets), `skills.pb` (installed skills), `benchmark_results.pb` |
| **Access pattern** | `Flow`-based reactive reads; coroutine-based writes |
| **Communicates with** | Domain/Data Container (primary reader/writer) |

### 7. Model Storage Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | Android File System (app-private internal storage) |
| **Responsibility** | Store downloaded `.task` model binary files |
| **Location** | `/data/user/0/com.google.ai.edge.gallery/files/models/` |
| **File format** | `.task` — TensorFlow Lite task bundle |
| **Communicates with** | Runtime Container (file path passed for model loading), WorkManager Container (download destination) |

### 8. WorkManager Container

| Attribute | Detail |
|-----------|--------|
| **Technology** | AndroidX WorkManager + Kotlin coroutines |
| **Responsibility** | Manage large model file downloads in the background; survive app kill and device reboot |
| **Worker** | `DownloadWorker : CoroutineWorker` |
| **Foreground** | Uses `ForegroundService` type `dataSync` for downloads > a few seconds |
| **Communicates with** | Hugging Face Hub (HTTPS download), Model Storage Container (writes file), Domain/Data Container (reports progress via `WorkInfo`) |

---

## Inter-Container Communication

| From | To | Protocol / Mechanism |
|------|----|---------------------|
| UI Container | Domain/Data Container | Kotlin `StateFlow` / suspend functions |
| UI Container | Runtime Container | Kotlin `Flow<String>` (streaming tokens) |
| Domain/Data Container | Proto DataStore | Jetpack DataStore API (coroutines) |
| Domain/Data Container | WorkManager Container | `WorkManager.enqueueUniqueWork()` |
| Skills Container | WebView Container | `WebView.evaluateJavascript()` callback |
| WebView Container | External Skill APIs | HTTPS `fetch()` from JavaScript |
| WorkManager Container | Hugging Face Hub | HTTPS GET (Bearer token auth) |
| WorkManager Container | Model Storage Container | `java.io.File` write |
| Runtime Container | Model Storage Container | `File` path read |
| App Process | Firebase | Firebase Android SDK (HTTPS) |

---

## Technology Summary

| Container | Language | Key Library / Framework |
|-----------|----------|------------------------|
| UI | Kotlin | Jetpack Compose, Navigation Compose |
| Domain/Data | Kotlin | Kotlin coroutines, Kotlinx serialization |
| Runtime | Kotlin/C++ (via JNI) | LiteRT `litertlm`, MediaPipe |
| Skills | Kotlin + JS | Android WebView, AppAuth |
| WebView | HTML/JavaScript | V8 engine (Chromium) |
| Proto DataStore | Kotlin | `androidx.datastore`, Protobuf Java Lite |
| Model Storage | — | Android file system |
| WorkManager | Kotlin | `androidx.work`, `CoroutineWorker` |

---

## Deployment View

```
Android Device (end-user hardware)
│
├── APK (com.google.ai.edge.gallery)
│   ├── Kotlin/JVM bytecode (app logic)
│   ├── Bundled assets (skill HTML/JS files)
│   ├── Native .so libraries (LiteRT C++ runtime)
│   └── Proto schema classes (generated Java Lite)
│
├── App-private storage
│   ├── /files/models/*.task          ← downloaded model binaries
│   └── /files/datastore/*.pb         ← persistent settings / user data
│
└── System services used
    ├── WorkManager (background download)
    ├── WebView (skill JS execution)
    ├── Camera / Microphone (via CameraX)
    └── Firebase SDK (analytics / FCM)
```
