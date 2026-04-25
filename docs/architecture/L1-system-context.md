# C4 Architecture — Level 1: System Context

> **C4 Level 1** shows the big picture: who uses the system and what external systems it interacts with. This is the entry point for understanding the overall landscape.

---

## System Context Diagram

```
                         ┌──────────────────────────────────────────────┐
                         │            System Boundary                    │
                         │                                              │
  ┌──────────┐           │   ┌──────────────────────────────────┐      │
  │  End     │ installs/ │   │                                  │      │
  │  User    │ uses app  │   │    Google AI Edge Gallery        │      │
  │          ├───────────┼──►│    (Android / iOS mobile app)   │      │
  │(mobile   │           │   │                                  │      │
  │ device   │◄──────────┼───│  On-device LLM inference with   │      │
  │ owner)   │ AI output │   │  an extensible Agent Skills      │      │
  └──────────┘           │   │  system                         │      │
                         │   └──────────┬───────────────────────┘      │
                         │              │                               │
                         └──────────────┼───────────────────────────────┘
                                        │
              ┌─────────────────────────┼─────────────────────────┐
              │                         │                          │
              ▼                         ▼                          ▼
  ┌───────────────────┐   ┌─────────────────────────┐  ┌──────────────────┐
  │   Hugging Face    │   │  Google Play Store /     │  │  Firebase        │
  │   Hub             │   │  Apple App Store         │  │  (Google)        │
  │                   │   │                          │  │                  │
  │  - Model hosting  │   │  - App distribution      │  │  - Analytics     │
  │  - OAuth 2.0 auth │   │  - Version management    │  │  - FCM push msgs │
  │  - Download gate  │   │  - Update delivery       │  │  - Crash reports │
  └───────────────────┘   └─────────────────────────┘  └──────────────────┘

              ▼                         ▼
  ┌───────────────────┐   ┌─────────────────────────┐
  │  Skill External   │   │  Android OS / iOS        │
  │  APIs (optional)  │   │                          │
  │                   │   │  - Camera / Microphone   │
  │  - Google Maps    │   │  - File system           │
  │    Places API     │   │  - Intent system         │
  │  - Loudly API     │   │  - WorkManager           │
  │  - Wikipedia REST │   │  - Notification system   │
  │  - Any user-added │   │  - OAuth redirect        │
  └───────────────────┘   └─────────────────────────┘
```

---

## Actors

### Primary Actor — End User

| Attribute | Detail |
|-----------|--------|
| Who | Any person with an Android 12+ or iOS 17+ device |
| Goal | Run powerful AI models privately and offline |
| Interactions | Download models, chat, analyze images, use agent skills, benchmark |
| Prerequisites | Sufficient free storage (0.5–5 GB per model) and RAM (4–8 GB) |

---

## External Systems

### 1. Hugging Face Hub

| Attribute | Detail |
|-----------|--------|
| Role | Model repository and access control |
| Protocol | HTTPS REST API, OAuth 2.0 Authorization Code Flow |
| Data exchanged | OAuth access/refresh tokens, model file downloads (.task format) |
| When used | Only during model download — not during inference |
| Requirement | Required for gated models (e.g. Gemma). Open models download without auth. |

### 2. Google Play Store / Apple App Store

| Attribute | Detail |
|-----------|--------|
| Role | App distribution and update delivery |
| Protocol | Platform-native update mechanism |
| Data exchanged | App binary (APK/IPA), version metadata |
| When used | Initial install and app updates |

### 3. Firebase (Google)

| Attribute | Detail |
|-----------|--------|
| Role | Telemetry and remote messaging |
| Protocol | HTTPS (Firebase SDK) |
| Data exchanged | Anonymous usage events, FCM device tokens, push notification payloads |
| When used | Continuously while app is running (analytics), occasionally (FCM) |
| Privacy note | Only anonymized event data; no user content or model I/O is sent |

### 4. Skill External APIs (Optional)

These are invoked only when the user has installed and enabled specific featured skills:

| API | Used by Skill | Data Exchanged |
|-----|--------------|----------------|
| Wikipedia REST API | `query-wikipedia` | Topic name → page summary text |
| Google Maps Places API | `restaurant-roulette` | Location + cuisine → restaurant list |
| Loudly API | `mood-music` | Genre/mood params → audio stream URL |
| Any custom API | User-imported skills | Defined by the skill author |

> **Privacy:** All skill API calls are made from the user's device. No data is routed through Google servers.

### 5. Android OS / iOS

| Attribute | Detail |
|-----------|--------|
| Role | Operating system services and hardware access |
| Interactions | Camera, microphone, file system, Intents, WorkManager, notifications |
| When used | Throughout app lifecycle |

---

## Key Constraints

| Constraint | Implication |
|------------|-------------|
| **100% on-device inference** | No user data, prompts, or model outputs leave the device |
| **One model at a time** | Switching models requires unloading the current one first |
| **Storage-heavy** | Models range from 554 MB to 4.4 GB |
| **RAM-heavy** | Peak RAM during inference: 2.1 – 7.0 GB |
| **No backend server** | The app has no proprietary backend; all logic runs on-device |
| **Internet optional** | Required only for model downloads and skill API calls |

---

## Quality Attributes

| Attribute | Approach |
|-----------|---------|
| **Privacy** | All inference on-device; OAuth tokens stored in app-private DataStore |
| **Performance** | GPU/NPU acceleration via LiteRT; streaming token output |
| **Extensibility** | Skills system allows adding capabilities without changing core app |
| **Offline-first** | Full feature set available after initial model download |
| **Reliability** | WorkManager ensures downloads survive app kill / device reboot |
