# Remote API Server — Setup Manual

Serve any downloaded on-device LLM as an OpenAI-compatible HTTP endpoint over your local Wi-Fi, then use it from VS Code (Continue.dev), curl, or any OpenAI-compatible client on your laptop.

---

## Requirements

| Requirement | Detail |
|---|---|
| Android device | Same device running the AI Edge Gallery app |
| Downloaded model | At least one LLM fully downloaded in the app (green status) |
| Wi-Fi | Phone and laptop on the **same Wi-Fi network** (no AP isolation) |
| App version | Build that includes the Remote API Server feature |

---

## 1 — Start the server on your phone

1. Open the **AI Edge Gallery** app.
2. Tap the **hamburger menu** (☰) in the top-left corner to open the drawer.
3. Tap the **Remote API** tile (bottom-left, Wi-Fi icon).
4. In the **Remote API Server** screen:
   - **Model** — pick a fully-downloaded LLM from the dropdown (only downloaded models appear).
   - **Port** — default `8080`. Change if already in use (valid range: 1024–65535).
   - **Max output tokens** — advisory limit passed to the model (default 2048).
   - **Require API token** — recommended; toggle ON to protect the endpoint.
   - **API Token** — auto-generated UUID. Tap the **copy** icon to copy it to your clipboard. Tap the **rotate** icon to generate a new one.
5. Tap **Start**.

The status indicator turns green and shows:

```
Running
Base URL   http://192.168.x.x:8080/v1   [copy]
Model: <selected model name>
```

A persistent notification ("Remote API Server") also appears. You can stop the server any time from the notification or the **Stop** button.

> **Tip — find your phone's IP:**  
> Settings → About phone → Status → IP address, or just read it from the Base URL shown in the app.

---

## 2 — Verify connectivity from your laptop

Open a terminal on your laptop.

### Health check (no auth required)

```bash
curl http://<phone-ip>:8080/healthz
# → {"status":"ok"}
```

### List available models

```bash
curl http://<phone-ip>:8080/v1/models \
  -H "Authorization: Bearer <your-token>"
```

Expected response:

```json
{
  "object": "list",
  "data": [
    { "id": "Gemma-3n-E4B-it", "object": "model", "owned_by": "local" }
  ]
}
```

### Non-streaming chat

```bash
curl http://<phone-ip>:8080/v1/chat/completions \
  -H "Authorization: Bearer <your-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "<model-name>",
    "messages": [{"role": "user", "content": "Say hello in one sentence."}],
    "stream": false
  }'
```

### Streaming chat (Server-Sent Events)

```bash
curl -N http://<phone-ip>:8080/v1/chat/completions \
  -H "Authorization: Bearer <your-token>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "<model-name>",
    "messages": [{"role": "user", "content": "Say hello in one sentence."}],
    "stream": true
  }'
```

Tokens arrive as `data: {...}` lines, terminated by `data: [DONE]`.

---

## 3 — Configure VS Code with Continue.dev

### Install Continue

1. Open VS Code → Extensions (`Ctrl+Shift+X`).
2. Search **Continue** → Install (publisher: `Continue`).
3. Reload VS Code.

### Add the phone as a model provider

Open your Continue config file:

- **Windows**: `%USERPROFILE%\.continue\config.yaml`  
- **macOS / Linux**: `~/.continue/config.yaml`

Add an entry under `models`:

```yaml
models:
  - title: "Phone — Gemma-3n-E4B"
    provider: openai
    model: Gemma-3n-E4B-it          # must match exactly what the app shows
    apiBase: http://192.168.x.x:8080/v1
    apiKey: <paste-your-token-here>
    # Optional overrides:
    # contextLength: 8192
```

Save the file. The new model appears in the Continue model picker immediately — no VS Code restart needed.

### Select the model in Continue

- Click the model name in the Continue sidebar (bottom-left of the chat panel).
- Select **Phone — Gemma-3n-E4B** from the list.
- Type a message or trigger inline code completion (`Alt+\`).

---

## 4 — Use with other OpenAI-compatible clients

The server speaks the OpenAI Chat Completions API (`/v1/chat/completions`). Any client that accepts a custom `apiBase` / `base_url` should work:

| Client | Setting |
|---|---|
| OpenAI Python SDK | `openai.base_url = "http://<phone-ip>:8080/v1"` |
| LangChain `ChatOpenAI` | `openai_api_base="http://<phone-ip>:8080/v1"` |
| Cursor | Not supported (locked to cloud) |
| Jan | Add as a custom OpenAI-compatible endpoint |
| Obsidian Copilot plugin | Set "OpenAI Base URL" to the Base URL above |

---

## 5 — API reference

### `GET /healthz`

No authentication required. Returns `{"status":"ok"}` when the server is alive.

---

### `GET /v1/models`

Returns the currently loaded model.

**Headers:** `Authorization: Bearer <token>` (if "Require API token" is enabled)

---

### `POST /v1/chat/completions`

**Headers:**

```
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**

```json
{
  "model": "<model-name>",
  "messages": [
    { "role": "system",    "content": "You are a helpful assistant." },
    { "role": "user",      "content": "Write a quicksort in Python." },
    { "role": "assistant", "content": "(previous turn…)" },
    { "role": "user",      "content": "Now add type hints." }
  ],
  "stream": true,
  "temperature": 0.7,
  "top_p": 0.9
}
```

| Field | Type | Notes |
|---|---|---|
| `model` | string | Ignored (server uses whatever is loaded) |
| `messages` | array | Full conversation history; system messages become `systemInstruction` |
| `stream` | boolean | `true` → SSE chunks, `false` → single JSON response |
| `temperature`, `top_p` | number | Forwarded to the engine via model config |
| `max_tokens` | integer | Overrides the app-level setting for this request |

**Limitations (v1):**
- Image and audio content parts are silently ignored.
- Concurrent requests are serialized (one inference at a time).
- Multi-turn context is stateless — clients must send the full message history every request.

---

## 6 — Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `curl: (7) Failed to connect` | Wrong IP, wrong port, or AP isolation | Confirm IP in the app; check router "AP/Client isolation" setting and disable it |
| `401 Unauthorized` | Token missing or wrong | Copy the token from the app; use `Authorization: Bearer <token>` header |
| Status stays "Starting" | Model failed to load (OOM or backend failure) | Try a smaller model or restart the app and try again |
| Status shows "Error: No model selected." | Server tapped Start with no model chosen | Pick a model from the dropdown first |
| Status shows "Error: Model '…' is not downloaded." | Model removed or not fully downloaded | Re-download the model in the main model list |
| Server stops by itself | Android battery optimization killed the service | Go to **Settings → Battery → App battery usage → AI Edge Gallery → Unrestricted** |
| Tokens arrive very slowly | Thermal throttling or NPU/GPU contention | Plug in the phone; close other apps; reduce `max_tokens` |
| Continue.dev shows "connection refused" after switching Wi-Fi | Phone IP changed | Check the new IP in the app and update `config.yaml` |

---

## 7 — Security notes

- The server binds to `0.0.0.0` — every device on your local network can reach it.
- **Always enable "Require API token"** unless you are on a trusted private network.
- The token is stored in app SharedPreferences (private to the app, not backed up to the cloud).
- Rotate the token any time from the Remote API screen.
- Stop the server when you don't need it to avoid unnecessary exposure.
