# Development Guide

> End-to-end guide for setting up the development environment, building the app, and contributing new features or skills.

---

## Prerequisites

| Tool | Minimum Version | Install |
|------|----------------|---------|
| Android Studio | Hedgehog (2023.1.1) or later | [developer.android.com/studio](https://developer.android.com/studio) |
| JDK | 11 | Bundled with Android Studio |
| Android SDK | API 35 | Via Android Studio SDK Manager |
| Android NDK | Latest stable | Via Android Studio SDK Manager |
| Git | Any recent | [git-scm.com](https://git-scm.com) |

---

## Step 1 — Clone the Repository

```bash
git clone https://github.com/google-ai-edge/gallery.git
cd gallery
```

---

## Step 2 — Configure Hugging Face OAuth

The app uses Hugging Face OAuth to allow users to download gated models (e.g., Gemma). You must configure this before building.

### 2.1 Create a Hugging Face OAuth App

1. Go to [huggingface.co/settings/applications](https://huggingface.co/settings/applications).
2. Click **New OAuth App**.
3. Fill in the fields:
   - **Application name:** e.g., `AI Edge Gallery Dev`
   - **Homepage URL:** any URL (e.g., `https://localhost`)
   - **Redirect URI:** `com.google.ai.edge.gallery://oauth/callback`
4. Click **Create Application**.
5. Copy the **Client ID** shown on the next page.

### 2.2 Update ProjectConfig.kt

Open:
```
Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt
```

Replace the placeholder values:

```kotlin
object ProjectConfig {
    const val CLIENT_ID = "YOUR_HUGGING_FACE_CLIENT_ID"   // ← paste here
    const val REDIRECT_URI = "com.google.ai.edge.gallery://oauth/callback"
}
```

### 2.3 Update app/build.gradle.kts

Open:
```
Android/src/app/build.gradle.kts
```

Find and update `manifestPlaceholders`:

```kotlin
defaultConfig {
    // ...
    manifestPlaceholders["appAuthRedirectScheme"] = "com.google.ai.edge.gallery"
}
```

> The scheme must match the prefix of the redirect URI you configured in step 2.1.

---

## Step 3 — Open in Android Studio

1. Launch Android Studio.
2. Select **Open** (not "New Project").
3. Navigate to `gallery/Android/src/` and click **OK**.
4. Wait for Gradle sync to complete (first sync downloads ~500 MB of dependencies).

If Gradle sync fails:
- Check your internet connection.
- Ensure JDK 11 is configured: **File → Project Structure → SDK Location → JDK Location**.
- Try **File → Invalidate Caches / Restart**.

---

## Step 4 — Build and Run

### Via Android Studio

1. Connect a physical Android 12+ device via USB (recommended — emulators lack GPU acceleration for LLMs).
2. Enable **USB Debugging** on the device: **Settings → Developer Options → USB Debugging**.
3. Select your device in the Android Studio device picker.
4. Click the **Run** button (▶) or press `Shift+F10`.

### Via Command Line

```bash
cd Android/src/

# Debug build and install
./gradlew installDebug

# Release build (requires signing config)
./gradlew assembleRelease
```

> **Note:** The first build takes 5–15 minutes to compile Kotlin, generate Proto classes, and process resources.

---

## Step 5 — Project Structure

```
Android/src/
├── build.gradle.kts              ← Project-level Gradle config
├── settings.gradle.kts           ← Module inclusion and plugin management
├── gradle.properties             ← JVM args, AndroidX flags
├── gradlew / gradlew.bat         ← Gradle wrapper scripts
├── gradle/
│   ├── libs.versions.toml        ← Version catalog (all dependency versions)
│   └── wrapper/
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts          ← App-level Gradle config
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/skills/        ← Built-in skill HTML/JS files
        ├── java/com/google/ai/edge/gallery/
        │   ├── GalleryApplication.kt
        │   ├── MainActivity.kt
        │   ├── common/
        │   ├── customtasks/      ← AgentChat, MobileActions, TinyGarden
        │   ├── data/             ← Models, repositories, configs
        │   ├── di/               ← Hilt dependency injection modules
        │   ├── runtime/          ← LLM inference engine wrapper
        │   ├── ui/               ← Compose screens and ViewModels
        │   └── worker/           ← WorkManager download workers
        ├── proto/
        │   ├── benchmark.proto
        │   ├── settings.proto
        │   └── skill.proto
        └── res/                  ← Drawables, strings, themes
```

---

## Key Gradle Dependencies

All versions are managed via `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.8.2"
kotlin = "2.2.0"
compose-bom = "2026.02.00"
litertlm = "0.10.0"
hilt = "2.57.2"
firebase-bom = "33.16.0"
camerax = "1.4.2"
protobuf = "4.28.3"

[libraries]
litertlm = { module = "com.google.ai.edge.litertlm:litertlm", version.ref = "litertlm" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
```

---

## Protocol Buffer Generation

Proto files under `src/main/proto/` are compiled automatically during the build via the Protobuf Gradle plugin:

```kotlin
// app/build.gradle.kts
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.28.3" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
        }
    }
}
```

Generated Java classes appear in `build/generated/source/proto/`.

---

## Adding a New Task

Tasks are the top-level features (AI Chat, Prompt Lab, etc.). To add one:

### 1. Define the TaskType

In `data/Types.kt`, add a new entry to the `TaskType` enum:

```kotlin
enum class TaskType {
    LLM_CHAT,
    LLM_PROMPT_LAB,
    // ...
    MY_NEW_TASK   // ← add here
}
```

### 2. Register the Task

In `data/Tasks.kt`, create a `Task` instance and add it to the task registry:

```kotlin
val myNewTask = Task(
    type = TaskType.MY_NEW_TASK,
    name = "My New Task",
    description = "What this task does",
    category = Category.EXPERIMENTAL,
    icon = Icons.Default.Star,
    models = emptyList()  // populated from allowlist at runtime
)
```

### 3. Create a Screen and ViewModel

Follow the pattern of existing tasks:
- `ui/mynewfeature/MyNewFeatureScreen.kt` — Composable screen
- `ui/mynewfeature/MyNewFeatureViewModel.kt` — ViewModel with `uiState: StateFlow`

### 4. Add to Navigation Graph

In `ui/navigation/NavigationGraph.kt`:

```kotlin
composable(Screen.MyNewFeature.route) { backStackEntry ->
    val viewModel: MyNewFeatureViewModel = hiltViewModel()
    MyNewFeatureScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
}
```

### 5. Add to Home Screen

Add a tile for the new task in `ui/home/HomeScreen.kt`.

---

## Adding a New Agent Skill

See the full walkthrough in [L4 Skills System — Adding a New Skill](architecture/L4-skills-system.md#adding-a-new-skill).

Short version:
1. Create `skills/built-in/my-skill/SKILL.md` and `scripts/index.html`.
2. Copy to `Android/src/app/src/main/assets/skills/my-skill/`.
3. Register in `SkillAllowlist`.

---

## Adding a New Mobile Action

Mobile Actions (in `customtasks/mobileactions/`) let the agent control device features.

### 1. Define the Action

In `Actions.kt`, add to the `ActionType` enum and create an `Action` subclass:

```kotlin
enum class ActionType { TAKE_PHOTO, SEND_EMAIL, /* ... */ MY_ACTION }

class MyAction : Action(
    type = ActionType.MY_ACTION,
    icon = Icons.Default.MyIcon,
    functionName = "my_action",
    functionDescription = "Description for the LLM"
)
```

### 2. Add Tool Definition

In `MobileActionsTools.kt`:

```kotlin
@Tool
suspend fun my_action(
    @ToolParam("param1") param1: String,
    onFunctionCalled: (ActionType, Map<String, Any>) -> Unit
) {
    onFunctionCalled(ActionType.MY_ACTION, mapOf("param1" to param1))
}
```

### 3. Implement Logic

In `MobileActionsViewModel.kt`, handle the new action in `performAction()`:

```kotlin
ActionType.MY_ACTION -> {
    val param1 = params["param1"] as String
    // Android-specific logic here
}
```

---

## Testing

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires connected device)
./gradlew connectedAndroidTest

# Specific test class
./gradlew test --tests "com.google.ai.edge.gallery.data.ModelAllowlistTest"
```

---

## Build Variants

| Variant | Signing | Firebase | Description |
|---------|---------|---------|-------------|
| `debug` | Debug key | Debug config | Development builds |
| `release` | Requires keystore | Production config | Production/Play Store |

---

## Useful Gradle Tasks

```bash
# Clean build
./gradlew clean

# Run lint checks
./gradlew lint

# Generate dependency report
./gradlew app:dependencies

# Assemble APK without installing
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk

# Show all available tasks
./gradlew tasks
```

---

## Updating the Model Allowlist

The allowlist at `model_allowlist.json` (and `model_allowlists/`) controls which models the app displays.

Each entry follows this schema:

```json
{
  "name": "Model Display Name",
  "modelId": "huggingface-org/model-repo",
  "description": "Brief model description",
  "url": "https://huggingface.co/...",
  "sizeInBytes": 554700000,
  "peakMemoryBytes": 2100000000,
  "llmModelConfig": {
    "compatibleAccelerators": ["GPU", "CPU"],
    "defaultMaxTokens": 1024,
    "defaultTopk": 40,
    "defaultTopp": 0.95,
    "defaultTemperature": 1.0,
    "supportImage": false,
    "supportAudio": false,
    "supportTinyGarden": false,
    "supportMobileActions": false,
    "supportThinking": false
  },
  "supportedTasks": ["llm_chat", "llm_prompt_lab"]
}
```

Version-specific lists go in `model_allowlists/{version}.json` and are used to show only models compatible with that app version.

---

## Troubleshooting

| Problem | Solution |
|---------|---------|
| Gradle sync fails | Check internet; invalidate caches; verify JDK 11 |
| `LlmInference` crashes | Ensure model `.task` file is not corrupted; check free RAM |
| OAuth redirect fails | Verify `appAuthRedirectScheme` in `build.gradle.kts` matches `redirectUri` in `ProjectConfig.kt` |
| WebView JS not executing | Check that `index.html` registers `window['ai_edge_gallery_get_result']` before any async calls |
| Proto compile errors | Run `./gradlew generateProto` manually; check proto syntax |
| App crashes on launch | Run `adb logcat -s GalleryApp` to see Hilt injection errors |
