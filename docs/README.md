# Google AI Edge Gallery — Documentation

> **Version:** 1.0.12 | **License:** Apache 2.0 | **Platform:** Android 12+ (iOS 17+)

A premier destination for running the world's most powerful open-source Large Language Models (LLMs) directly on mobile devices — fully offline, private, and lightning-fast.

---

## Documentation Index

### Architecture — C4 Model

The architecture is documented across all four C4 levels, from the broadest system view down to individual classes.

```
L1 — System Context
 └─ L2 — Containers
      └─ L3 — Components
           └─ L4 — Code (Overview + Data + UI + Runtime + Skills)
```

#### Level 1 — System Context

Who uses the system and what external systems it depends on.

| File | Description |
|------|-------------|
| [L1 — System Context](architecture/L1-system-context.md) | End User actor, Hugging Face Hub, Firebase, Google Play Store, Skill External APIs, Android OS. Key privacy and resource constraints. |

#### Level 2 — Containers

The deployable/runnable units inside the application boundary.

| File | Description |
|------|-------------|
| [L2 — Containers](architecture/L2-containers.md) | 8 containers: UI, Domain/Data, Runtime, Skills, WebView, Proto DataStore, Model Storage, WorkManager. Technology stack and inter-container communication table. |

#### Level 3 — Components

Major internal components within each container.

| File | Description |
|------|-------------|
| [L3 — Components](architecture/L3-components.md) | Components for every container (Home, LlmChat, ModelManager, AgentChat, MobileActions, DownloadWorker, DataStore stores, WebView pool). Interaction sequences and Hilt DI wiring. |

#### Level 4 — Code

Class-level detail for each sub-system.

| File | Description |
|------|-------------|
| [L4 — Overview](architecture/L4-overview.md) | Full code-level map: application bootstrap, layer diagram, cross-cutting concerns, data flow diagrams, complete file → class mapping table |
| [L4 — Data Layer](architecture/L4-data-layer.md) | `Model`, `Task`, `Config`, `DataStoreRepository`, `DownloadRepository`, Proto schemas, allowlists, supported models table |
| [L4 — UI Layer](architecture/L4-ui-layer.md) | Navigation graph, class diagrams for every screen + ViewModel, theme system, shared composables, MVVM state pattern |
| [L4 — Runtime Layer](architecture/L4-runtime-layer.md) | `LlmModelHelper`, `AiCoreManager`, `DownloadWorker`, accelerator selection, model loading/unloading lifecycle, LiteRT integration |
| [L4 — Skills System](architecture/L4-skills-system.md) | Skill types, `SkillRunner`, JS contract, intent contract, skill execution sequence diagram, built-in and featured skills reference, guide to adding a new skill |

### Guides

| File | Description |
|------|-------------|
| [Getting Started](getting-started.md) | Step-by-step user guide for the app |
| [Development Guide](development-guide.md) | Developer setup, build, and contribution workflow |

---

## Quick Summary

| Attribute | Value |
|-----------|-------|
| Language | Kotlin / Java (Android), Swift (iOS) |
| UI Framework | Jetpack Compose |
| AI Runtime | LiteRT (Google AI Edge / MediaPipe LLM Inference) |
| Build System | Gradle 8.8.2 (Kotlin DSL) |
| Min Android SDK | 31 (Android 12) |
| Target Android SDK | 35 (Android 15) |
| Dependency Injection | Hilt 2.57.2 |
| Data Persistence | DataStore + Protocol Buffers 3 |
| Model Source | Hugging Face / LiteRT Community |

---

## High-Level System Overview

```
┌─────────────────────────────────────────────────────┐
│                 Android Application                  │
│  ┌──────────┐  ┌──────────┐  ┌─────────────────┐   │
│  │  UI Layer│  │Data Layer│  │  Runtime Layer   │   │
│  │(Compose) │  │(DataStore│  │ (LiteRT/LLM)     │   │
│  │          │◄─►Proto)    │◄─►                  │   │
│  └──────────┘  └──────────┘  └─────────────────┘   │
│         ▲              ▲               ▲            │
│         └──────────────┼───────────────┘            │
│                        │                            │
│              ┌─────────▼─────────┐                  │
│              │   Skills System   │                  │
│              │ (JS / Intent /    │                  │
│              │  Native)          │                  │
│              └───────────────────┘                  │
└─────────────────────────────────────────────────────┘
         │                          │
         ▼                          ▼
  Hugging Face API           On-device Storage
  (model download)           (models + settings)
```
