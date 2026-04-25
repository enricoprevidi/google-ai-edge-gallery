# Getting Started — Step-by-Step User Guide

> Google AI Edge Gallery lets you run powerful AI models entirely on your device — no internet needed for inference, fully private.

---

## Requirements

| Requirement | Minimum |
|-------------|---------|
| Android version | Android 12 (API 31) |
| Free storage | 1 GB (app) + model size (0.5 – 4.5 GB per model) |
| RAM | 4 GB device RAM (8 GB recommended for larger models) |
| iOS version | iOS 17+ (separate app) |

---

## Step 1 — Install the App

### Option A: Google Play Store (Recommended)

1. Open the **Google Play Store** on your Android device.
2. Search for **"Google AI Edge Gallery"**.
3. Tap **Install**.

### Option B: Direct APK

1. Go to the [GitHub Releases page](https://github.com/google-ai-edge/gallery/releases).
2. Download the latest `.apk` file.
3. On your device, enable **Settings → Security → Install Unknown Apps** for your browser.
4. Open the downloaded `.apk` and tap **Install**.

### Option C: Build from Source

See the [Development Guide](development-guide.md) for build instructions.

---

## Step 2 — Accept Terms of Service

1. On first launch, the app displays the **Terms of Service**.
2. Read the terms carefully.
3. Tap **Accept** to proceed.

> You must accept the Terms of Service before using any feature. This is stored locally in the app's settings and is not sent to any server.

---

## Step 3 — Explore the Home Screen

After accepting the ToS, the **Home Screen** appears. It shows all available AI tasks grouped by category:

| Task | Description |
|------|-------------|
| **AI Chat** | Multi-turn conversation with the AI model |
| **Prompt Lab** | Single-turn prompts with full parameter control |
| **Ask Image** | Describe or analyze an image using multimodal models |
| **Audio Scribe** | Transcribe audio recordings |
| **Agent Skills** | AI agent with callable tools (skills) |
| **Mobile Actions** | AI controls device features (camera, settings, etc.) |
| **Benchmark** | Measure model performance on your device |
| **Tiny Garden** | Mini-game powered by on-device AI |

> **Tip:** Tasks marked with a lock icon require a compatible model to be downloaded first.

---

## Step 4 — Download a Model

AI models must be downloaded to your device before first use.

### 4.1 Open Model Manager

1. Tap any task tile on the Home Screen.
2. If no model is downloaded, you are taken directly to the **Model Manager**.
3. Alternatively, tap the **download icon** on any task tile.

### 4.2 Browse Available Models

The Model Manager lists all supported models with:
- **Model name and version**
- **File size** (download size)
- **Peak RAM usage** (required during inference)
- **Compatible tasks** (chat, image, audio, etc.)

**Available models (v1.0.12):**

| Model | Size | Peak RAM | Best For |
|-------|------|----------|----------|
| Gemma3-1B-IT q4 | 554 MB | 2.1 GB | Fast responses, low-end devices |
| Qwen2.5-1.5B-Instruct q8 | 1.6 GB | 2.7 GB | Balanced quality + speed |
| Gemma-3n-E2B-it-int4 | 3.1 GB | 5.9 GB | High quality, image support |
| Gemma-3n-E4B-it-int4 | 4.4 GB | 7.0 GB | Best quality, image support |

### 4.3 Sign In to Hugging Face (if required)

Some models (Gemma series) require accepting their license on Hugging Face:

1. Tap **Sign In with Hugging Face** in Model Manager.
2. A browser opens the Hugging Face OAuth page.
3. Log in (or create a free account at [huggingface.co](https://huggingface.co)).
4. Accept the model's license agreement on the model page.
5. Return to the app — it automatically detects the sign-in.

> Your Hugging Face token is stored securely on-device only. It is used solely to authenticate model downloads.

### 4.4 Start the Download

1. Tap the **Download** button next to your chosen model.
2. A progress bar shows download status.
3. A notification appears in the system tray — the download continues in the background.
4. When complete, the button changes to **Select**.

> Downloads can be paused/resumed automatically if the connection is interrupted. Tap **Cancel** to stop a download; the partial file is deleted.

### 4.5 Select the Model

1. After download completes, tap **Select** next to the model.
2. You are returned to the task, now ready to use.

---

## Step 5 — Use AI Chat

1. Tap **AI Chat** on the Home Screen.
2. Select a downloaded model from the Model Manager (if not already selected).
3. The **Chat Screen** opens.

### Sending a Message

1. Tap the text field at the bottom of the screen.
2. Type your message.
3. Tap the **Send** button (arrow icon).
4. The model's response streams in real-time — words appear as they are generated.

### Attaching an Image (Multimodal Models)

*Requires Gemma-3n models.*

1. Tap the **camera/gallery icon** next to the text field.
2. Choose **Camera** to take a photo, or **Gallery** to pick an existing image.
3. The image appears as a thumbnail in the input area.
4. Type your question about the image and send.

### Enabling Thinking Mode

Some models support "thinking" — they reason step by step before answering:

1. Tap the **settings gear** in the top-right corner of the Chat Screen.
2. Toggle **Thinking Mode** on.
3. When enabled, the model's reasoning chain appears in a collapsible block above the final answer.

### Clearing Chat History

- Tap the **overflow menu (⋮)** in the top-right corner.
- Select **Clear History**.

---

## Step 6 — Use Prompt Lab

Prompt Lab gives you full control over inference parameters for single-turn prompts.

1. Tap **Prompt Lab** on the Home Screen.
2. In the **System Prompt** field, define the model's persona or task context.
3. In the **User Prompt** field, type your query.
4. Adjust parameters:

| Parameter | Effect |
|-----------|--------|
| **Temperature** (0–2) | Higher = more creative/random; Lower = more deterministic |
| **Top-K** (1–100) | Number of candidate tokens sampled |
| **Top-P** (0–1) | Nucleus sampling threshold |
| **Max Tokens** | Maximum length of generated response |

5. Tap **Run** to generate.
6. Performance metrics (tokens/sec, latency) are shown below the response.

---

## Step 7 — Use Ask Image

*Requires a multimodal model (Gemma-3n series).*

1. Tap **Ask Image** on the Home Screen.
2. Tap **Take Photo** or **Choose from Gallery**.
3. The image is displayed at the top of the screen.
4. Type your question about the image (e.g., "What's in this photo?", "Describe the text visible here").
5. Tap **Send** — the model analyzes the image fully on-device.

---

## Step 8 — Use Agent Skills

Agent Skills let the AI call tools to extend its capabilities.

1. Tap **Agent Skills** (or use AI Chat with skills enabled).
2. The app loads all selected skills into the agent's system prompt.

### Managing Skills

1. Tap the **skills icon** in the chat toolbar.
2. A list of all available skills appears.
3. Toggle individual skills on or off.

### Using a Skill (Example: Wikipedia Query)

1. Ensure **Query Wikipedia** skill is enabled.
2. In the chat, ask: *"What is quantum computing? Use Wikipedia."*
3. The model decides to call the `run_js` tool for `query-wikipedia`.
4. The app executes the JavaScript, fetches the Wikipedia summary, and shows the result inline.

### Using the Interactive Map Skill

1. Enable **Interactive Map** skill.
2. Ask: *"Show me a map of the Eiffel Tower."*
3. An embedded Google Maps view appears in the chat.

### Using the QR Code Skill

1. Enable **QR Code** skill.
2. Ask: *"Generate a QR code for https://example.com"*
3. A QR code image appears directly in the chat.

### Featured Skills (require API keys)

| Skill | Requires | What It Does |
|-------|----------|-------------|
| Restaurant Roulette | Google Maps API key | Shows a spin wheel with nearby restaurants |
| Mood Music | Loudly API key | Generates music matching your mood |
| Virtual Piano | None | Opens a playable piano keyboard |

**Setting up an API key:**

1. Tap the **skills icon**.
2. Tap the skill that requires a key (e.g., Restaurant Roulette).
3. Tap **Enter API Key**.
4. Paste your key and tap **Save**.
5. The key is stored securely on-device and never sent to Google.

---

## Step 9 — Run a Benchmark

Benchmark measures how fast your device runs a model.

1. Tap **Benchmark** on the Home Screen.
2. Select a downloaded model.
3. Configure:
   - **Number of Runs**: More runs = more accurate statistics (recommend 5+)
   - **Prefill Tokens**: Length of input prompt (affects prefill speed)
   - **Decode Tokens**: Length of output (affects decode speed)
4. Tap **Run Benchmark**.
5. Results show:
   - **Prefill speed** (tokens/sec) — how fast the model processes your input
   - **Decode speed** (tokens/sec) — how fast the model generates output
   - **Time to First Token** (seconds) — latency before first response
   - **Initialization time** — model load time

> Results are saved and can be compared across runs. Tap **History** to view past benchmarks.

---

## Step 10 — Import a Custom Model

If you have a local `.task` model file:

1. Open **Model Manager**.
2. Scroll to the bottom and tap **Import Model**.
3. Select the `.task` file from your device storage.
4. Configure the model's capabilities:
   - Compatible accelerators (CPU / GPU)
   - Maximum tokens
   - Multimodal support (image, audio)
5. Tap **Import** — the model appears in your model list.

---

## Step 11 — Change App Settings

1. Tap the **settings icon (⚙)** on the Home Screen.
2. Available settings:

| Setting | Options |
|---------|---------|
| **Theme** | Light / Dark / System Default |
| **Hugging Face Account** | Sign in / Sign out |
| **Feature Flags** | Enable/disable experimental features |
| **Licences** | View open-source library licences |

---

## Frequently Asked Questions

**Q: Does the app need internet to run?**
A: No. Once a model is downloaded, all inference runs 100% on-device. Internet is only needed to download models.

**Q: Why does the app use so much RAM?**
A: LLMs require loading the entire model into memory. A 1 GB model file can require 2+ GB of RAM during inference due to KV-cache and activation buffers.

**Q: The model output stops mid-sentence. Why?**
A: The `Max Tokens` limit was reached. Increase it in Prompt Lab settings or ask the model to continue.

**Q: Can I use the app with multiple models at the same time?**
A: No. Only one model can be active at a time. Switching models unloads the current one from memory first.

**Q: My download keeps failing. What should I do?**
A: Check storage space and internet connectivity. For Gemma models, ensure you have accepted the license on Hugging Face and are signed in.

**Q: How do I report a bug?**
A: See [Bug_Reporting_Guide.md](../Bug_Reporting_Guide.md) in the project root.
