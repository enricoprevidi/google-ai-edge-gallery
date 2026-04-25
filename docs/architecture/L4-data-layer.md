# L4 Architecture — Data Layer

> **C4 Model Level 4 — Data Layer:** Classes responsible for data modeling, persistence, and repository operations.

---

## Overview

The data layer is split into two sub-layers:

1. **Domain models** (`data/`) — pure Kotlin data classes and enumerations describing business entities.
2. **Persistence** — Jetpack DataStore backed by Protocol Buffer serializers.

```
data/
├── Model.kt            ← LLM model descriptor
├── Tasks.kt            ← Task registry
├── Categories.kt       ← Task category definitions
├── Config.kt           ← App-wide configuration object
├── ConfigValue.kt      ← Typed configuration value wrapper
├── Consts.kt           ← String/numeric constants
├── Types.kt            ← Shared enums (DownloadStatus, TaskType…)
├── AppBarAction.kt     ← Action items for the top app bar
├── DataStoreRepository.kt  ← Single source of truth for persisted data
├── DownloadRepository.kt   ← Model download management
├── ModelAllowlist.kt   ← Allowlist of models the app supports
└── SkillAllowlist.kt   ← Allowlist of skills the app ships with
```

---

## Class Diagrams

### Core Data Models

```mermaid
classDiagram
    class Model {
        +name: String
        +description: String
        +modelId: String
        +url: String
        +sizeInBytes: Long
        +peakMemoryBytes: Long
        +llmModelConfig: LlmModelConfig
        +supportedTasks: List~TaskType~
        +state: ModelState
        +downloadedPath: String?
    }

    class LlmModelConfig {
        +compatibleAccelerators: List~String~
        +defaultMaxTokens: Int
        +defaultTopK: Int
        +defaultTopP: Float
        +defaultTemperature: Float
        +supportImage: Boolean
        +supportAudio: Boolean
        +supportTinyGarden: Boolean
        +supportMobileActions: Boolean
        +supportThinking: Boolean
    }

    class ModelState {
        <<enum>>
        NOT_DOWNLOADED
        DOWNLOADING
        DOWNLOADED
        FAILED
    }

    class ImportedModel {
        +fileName: String
        +fileSize: Long
        +llmConfig: LlmConfig
    }

    Model "1" --> "1" LlmModelConfig : has
    Model "1" --> "1" ModelState : currentState
    ImportedModel "1" --> "1" LlmConfig : has
```

### Task and Category Models

```mermaid
classDiagram
    class Task {
        +type: TaskType
        +name: String
        +description: String
        +category: Category
        +icon: ImageVector
        +models: List~Model~
    }

    class TaskType {
        <<enum>>
        LLM_CHAT
        LLM_PROMPT_LAB
        LLM_ASK_IMAGE
        LLM_AUDIO_SCRIBE
        LLM_BENCHMARK
        AGENT_CHAT
        MOBILE_ACTIONS
        TINY_GARDEN
    }

    class Category {
        +name: String
        +description: String
        +tasks: List~Task~
    }

    class AppBarAction {
        +icon: ImageVector
        +contentDescription: String
        +onClick: () -> Unit
    }

    Task "1" --> "1" TaskType : typed by
    Task "1" --> "1" Category : belongs to
    Category "1" --> "*" Task : contains
```

### Configuration Models

```mermaid
classDiagram
    class Config {
        +key: String
        +displayName: String
        +defaultValue: ConfigValue
        +range: ClosedRange~Number~?
        +step: Number?
    }

    class ConfigValue {
        <<sealed>>
    }
    class IntValue {
        +value: Int
    }
    class FloatValue {
        +value: Float
    }
    class BooleanValue {
        +value: Boolean
    }
    class StringValue {
        +value: String
    }

    Config "1" --> "1" ConfigValue : holds
    ConfigValue <|-- IntValue
    ConfigValue <|-- FloatValue
    ConfigValue <|-- BooleanValue
    ConfigValue <|-- StringValue
```

---

## Protocol Buffer Schemas

All persisted state uses Proto3 serialized via Jetpack DataStore.

### Settings Proto

```proto
message Settings {
  Theme theme = 1;                          // LIGHT / DARK / AUTO
  repeated string text_input_history = 3;   // recent prompts
  repeated ImportedModel imported_model = 4;// user-imported .task files
  bool is_tos_accepted = 5;
  bool has_run_tiny_garden = 6;
  bool has_seen_benchmark_comparison_help = 7;
  bool is_gemma_terms_accepted = 8;
  map<string, bool> feature_flags = 9;
  repeated string viewed_promo_id = 10;
}

message ImportedModel {
  string file_name = 1;
  int64 file_size = 2;
  LlmConfig llm_config = 3;
}

message LlmConfig {
  repeated string compatible_accelerators = 1;
  int32 default_max_tokens = 2;
  int32 default_topk = 3;
  float default_topp = 4;
  float default_temperature = 5;
  bool support_image = 6;
  bool support_audio = 7;
  bool support_tiny_garden = 8;
  bool support_mobile_actions = 9;
  bool support_thinking = 10;
}
```

### UserData Proto

```proto
message UserData {
  AccessTokenData access_token_data = 1; // OAuth tokens
  map<string, string> secrets = 2;       // skill API keys
}

message AccessTokenData {
  string access_token = 1;
  string refresh_token = 2;
  int64 expires_at_ms = 3;
}
```

### Skills Proto

```proto
message Skills {
  repeated Skill skill = 1;
}

message Skill {
  string name = 1;
  string description = 2;
  string instructions = 3;
  bool built_in = 4;
  string skill_url = 5;
  string import_dir_name = 6;
  bool selected = 7;
  bool require_secret = 8;
  string require_secret_description = 10;
  string homepage = 9;
}
```

### BenchmarkResults Proto

```proto
message BenchmarkResults {
  repeated BenchmarkResult result = 1;
}
message LlmBenchmarkBasicInfo {
  string model_name = 3;
  string accelerator = 4;
  int32 prefill_tokens = 5;
  int32 decode_tokens = 6;
  int32 number_of_runs = 7;
  string app_version = 8;
}
message LlmBenchmarkStats {
  ValueSeries prefill_speed = 1;          // tokens/sec
  ValueSeries decode_speed = 2;           // tokens/sec
  ValueSeries time_to_first_token = 3;    // seconds
  double first_init_time_ms = 4;
}
message ValueSeries {
  double min = 2;
  double max = 3;
  double avg = 4;
  double medium = 5;
  double pct25 = 7;
  double pct75 = 8;
}
```

---

## DataStoreRepository

```mermaid
classDiagram
    class DataStoreRepository {
        -settingsDataStore: DataStore~Settings~
        -userDataDataStore: DataStore~UserData~
        -skillsDataStore: DataStore~Skills~
        -benchmarkResultsDataStore: DataStore~BenchmarkResults~
        +settingsFlow: Flow~Settings~
        +userDataFlow: Flow~UserData~
        +skillsFlow: Flow~Skills~
        +benchmarkResultsFlow: Flow~BenchmarkResults~
        +updateSettings(transform)
        +updateUserData(transform)
        +updateSkills(transform)
        +saveBenchmarkResult(result)
    }

    class SettingsSerializer {
        +defaultValue: Settings
        +readFrom(input: InputStream): Settings
        +writeTo(t: Settings, output: OutputStream)
    }
    class UserDataSerializer {
        +defaultValue: UserData
        +readFrom(input: InputStream): UserData
        +writeTo(t: UserData, output: OutputStream)
    }
    class SkillsSerializer {
        +defaultValue: Skills
        +readFrom(input: InputStream): Skills
        +writeTo(t: Skills, output: OutputStream)
    }
    class BenchmarkResultsSerializer {
        +defaultValue: BenchmarkResults
        +readFrom(input: InputStream): BenchmarkResults
        +writeTo(t: BenchmarkResults, output: OutputStream)
    }

    DataStoreRepository --> SettingsSerializer : uses
    DataStoreRepository --> UserDataSerializer : uses
    DataStoreRepository --> SkillsSerializer : uses
    DataStoreRepository --> BenchmarkResultsSerializer : uses
```

---

## DownloadRepository

```mermaid
classDiagram
    class DownloadRepository {
        -context: Context
        -workManager: WorkManager
        +downloadModel(model: Model, accessToken: String?)
        +cancelDownload(model: Model)
        +deleteModel(model: Model)
        +getDownloadStatus(model: Model): Flow~DownloadStatus~
        +getDownloadedModels(): List~String~
    }

    class DownloadWorker {
        <<CoroutineWorker>>
        +doWork(): Result
        -downloadFile(url, path, token)
        -reportProgress(bytesDownloaded, totalBytes)
    }

    class DownloadStatus {
        <<sealed>>
    }
    class Idle
    class InProgress {
        +progress: Float
        +bytesDownloaded: Long
        +totalBytes: Long
    }
    class Succeeded
    class Failed {
        +reason: String
    }
    class Cancelled

    DownloadRepository --> DownloadWorker : enqueues via WorkManager
    DownloadStatus <|-- Idle
    DownloadStatus <|-- InProgress
    DownloadStatus <|-- Succeeded
    DownloadStatus <|-- Failed
    DownloadStatus <|-- Cancelled
```

---

## Allowlists

### ModelAllowlist

Loaded from `model_allowlist.json` (bundled) and the version-specific lists under `model_allowlists/`.

```mermaid
classDiagram
    class ModelAllowlist {
        +models: List~AllowlistedModel~
        +fromJson(json: String): ModelAllowlist
    }
    class AllowlistedModel {
        +name: String
        +modelId: String
        +description: String
        +url: String
        +sizeInBytes: Long
        +peakMemoryBytes: Long
        +llmModelConfig: LlmModelConfig
        +supportedTasks: List~String~
        +maxTokens: Int
    }

    ModelAllowlist "1" --> "*" AllowlistedModel : contains
```

### SkillAllowlist

Loaded from `assets/skills/` directory on startup; each entry corresponds to a bundled skill.

```mermaid
classDiagram
    class SkillAllowlist {
        +skills: List~AllowlistedSkill~
    }
    class AllowlistedSkill {
        +name: String
        +description: String
        +instructions: String
        +builtIn: Boolean
        +requireSecret: Boolean
        +requireSecretDescription: String
        +homepage: String
    }

    SkillAllowlist "1" --> "*" AllowlistedSkill : contains
```

---

## DataStore File Locations (on-device)

| DataStore | File | Serializer |
|-----------|------|-----------|
| Settings | `files/datastore/settings.pb` | `SettingsSerializer` |
| UserData | `files/datastore/user_data.pb` | `UserDataSerializer` |
| Skills | `files/datastore/skills.pb` | `SkillsSerializer` |
| Benchmark Results | `files/datastore/benchmark_results.pb` | `BenchmarkResultsSerializer` |
| Cutouts | `files/datastore/cutouts.pb` | `CutoutsSerializer` |

---

## Supported Models (v1.0.12)

| Model | Size | Peak RAM | Tasks |
|-------|------|----------|-------|
| Gemma-3n-E2B-it-int4 | 3.1 GB | 5.9 GB | chat, prompt-lab, ask-image |
| Gemma-3n-E4B-it-int4 | 4.4 GB | 7.0 GB | chat, prompt-lab, ask-image |
| Gemma3-1B-IT q4 | 554.7 MB | 2.1 GB | chat, prompt-lab |
| Qwen2.5-1.5B-Instruct q8 | 1.6 GB | 2.7 GB | chat, prompt-lab |
