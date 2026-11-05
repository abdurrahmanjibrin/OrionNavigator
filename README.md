# 🧭 Orion Navigator

**AI-Powered Indoor Navigation Assistant for Visually Impaired Users**

Orion Navigator is an Android application designed to empower visually impaired (tunanetra) users with real-time AI-assisted navigation, obstacle detection, and voice-controlled interaction — all accessible through speech in Bahasa Indonesia.

> 🌐 **Website**: [orionguide.id](https://orionguide.id)

---

## ✨ Key Features

### 🎯 Real-Time Object Detection
- **On-device YOLOv8** model (`yolov8s_float16`) running via TensorFlow Lite
- Detects 80 COCO object classes with Indonesian language labels
- Bounding box overlay rendered on camera preview in real-time
- Voice announcements for objects detected with ≥70% confidence

### 🤖 AI Scene Description (Gemini 2.5 Flash)
- Multimodal scene understanding via Google Gemini REST API
- Natural language descriptions spoken aloud in Bahasa Indonesia
- Automatic fallback to `gemini-2.5-flash-lite` on rate limits

### 🗣️ Voice Command System
- **Offline wake word detection**: "Hello Orion" via on-device ONNX neural pipeline
- **Vosk offline speech recognition** for command processing
- **Gemini-powered intent classification** for natural language commands
- Supported intents: `DESCRIBE_SCENE`, `TOGGLE_SOUND`, `GO_BACK`, `HELP`

### 📡 BLE Indoor Navigation
- Bluetooth Low Energy beacon scanning for indoor positioning
- Turn-by-turn indoor navigation with voice guidance
- Known beacon filtering (hides unknown BLE devices)

### 👨‍👩‍👧 Companion Mode (Pendamping)
- **Live Location Sharing**: Real-time GPS tracking between parent and child devices
- **Safe Zones**: Geofenced areas with alerts
- **Role Selection**: Parent/companion and child/user modes

### 🧭 Sensor Integration
- Digital compass for directional guidance
- GPS-based outdoor navigation with direction activity

---

## 🏗️ Architecture

```
OrionNavigator/
├── OrionNavigator/          # Main Android app (Kotlin)
│   └── app/src/main/java/com/orion/app/
│       ├── ble/             # Bluetooth Low Energy beacon management
│       ├── camera/          # CameraX manager & detection overlay
│       ├── data/            # Data models and repositories
│       ├── gemini/          # Gemini AI REST API integration
│       ├── ml/              # YOLOv8 TFLite detector helper
│       ├── navigation/      # Indoor navigation logic
│       ├── sensor/          # Compass & sensor fusion
│       ├── service/         # Background services
│       ├── tts/             # Text-to-Speech manager (Indonesian)
│       ├── ui/              # Activities (12 screens)
│       ├── utils/           # Permission helpers & utilities
│       └── voice/           # Voice commands, wake word, Gemini intent processor
├── OrionWeb/                # Web companion resources
└── Mockup/                  # UI/UX design mockups
```

### ML Pipeline

```
┌─────────────────────────────────────────────────────────────────┐
│  FAST PATH: Real-Time Detection (On-Device)                     │
│  CameraX → YUV Frame → YOLOv8 TFLite → NMS → TTS + Overlay    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  SLOW PATH: Scene Understanding (Cloud)                         │
│  Camera Frame → Base64 JPEG → Gemini 2.5 Flash → TTS Response  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  WAKE WORD: Offline Detection (On-Device ONNX)                  │
│  16kHz Audio → MelSpectrogram → Embeddings → "Hello Orion" NN  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin |
| **UI Framework** | Android Views (XML layouts) |
| **Camera** | AndroidX CameraX 1.2.3 |
| **Object Detection** | YOLOv8s Float16 via TensorFlow Lite (4 CPU threads) |
| **Wake Word** | Custom 3-stage ONNX Runtime pipeline |
| **Speech Recognition** | Vosk (offline) |
| **AI/LLM** | Google Gemini 2.5 Flash REST API |
| **TTS** | Android TextToSpeech (Indonesian) |
| **Indoor Positioning** | Bluetooth Low Energy (BLE) beacons |
| **Location** | Google Play Services Location API |
| **Build System** | Gradle 8.5 / AGP |

---

## 📱 Screens

| Screen | Description |
|---|---|
| **SplashActivity** | App launch with branding |
| **ModeSelectionActivity** | Choose between Tunanetra or Pendamping mode |
| **TunanetraActivity** | Main visually impaired user interface with voice + detection |
| **CameraAIActivity** | Standalone AI camera with object detection + scene description |
| **DirectionActivity** | Turn-by-turn navigation with integrated camera detection |
| **BeaconActivity** | BLE beacon scanning and indoor positioning |
| **CompassTestActivity** | Digital compass for orientation |
| **RoleSelectionActivity** | Parent/child role for companion mode |
| **PendampingActivity** | Companion monitoring dashboard |
| **LiveLocationParentActivity** | Real-time location tracking (parent view) |
| **LiveLocationChildActivity** | Location sharing (child device) |
| **SafeZonesActivity** | Geofence creation and management |

---

## 🔄 Latest Update — October 16, 2026

### **Added: On-Device Wake Word Detection ("Hello Orion")**

Replaced the previous keyword-matching wake word system with a **fully offline, neural network-based wake word detector** using ONNX Runtime. This enables hands-free activation without any internet connection.

**What changed:**
- **New file**: `WakeWordDetector.kt` — High-performance 3-stage ONNX pipeline:
  1. `melspectrogram.onnx` — Converts 16kHz raw audio → Mel Spectrogram features
  2. `embedding_model.onnx` — Transforms 76-frame mel windows → 96-dim speech embeddings
  3. `hello_orion.onnx` — Classifies 16×96 embedding matrix → wake word probability
- **New assets**: 3 ONNX model files (~2.5 MB total)
- **Updated**: `VoiceCommandManager.kt` — Integrated `WakeWordDetector` for continuous background listening with configurable confidence threshold (default 80%) and debounce (1.5s)
- **Updated**: `build.gradle.kts` — Added ONNX Runtime dependency

**Previous update:**
- Fixed text overlap issues in `CameraAIActivity` UI
- Hidden unknown/unregistered BLE beacons from the beacon list to reduce noise

---

## 📂 Documentation

- [`CameraAISummary.md`](CameraAISummary.md) — Detailed technical breakdown of the Camera AI system architecture
- [`CustomObjectDetectionGuide.md`](CustomObjectDetectionGuide.md) — Step-by-step guide to train and deploy custom YOLOv8 models for Orion

---

## 🚀 Project Status

| Component | Status |
|---|---|
| Core Navigation (BLE + GPS) | ✅ Implemented |
| YOLOv8 Object Detection | ✅ Implemented |
| Gemini Scene Description | ✅ Implemented |
| Voice Commands (Vosk) | ✅ Implemented |
| Wake Word Detection (ONNX) | ✅ Implemented (New) |
| Companion Mode (Live Location) | ✅ Implemented |
| Safe Zones (Geofencing) | ✅ Implemented |
| Spatial/Distance Awareness | 🔲 Planned |
| Hardware Acceleration (GPU Delegate) | 🔲 Planned |
| Offline Gemini Fallback | 🔲 Planned |
| Custom Indoor Object Model | 🔲 Planned |

---

## 📄 License

This project is part of the Orion Guide initiative. All rights reserved.

---

<p align="center">
  Built with ❤️ for accessibility
</p>


### 🚀 Key Features & Architecture
- **Object Detection Engine**: Real-time camera detection pipeline.
- **Navigation Assistance**: Assistive spatial awareness and alert cues.
- **Kotlin Android Native**: Optimized mobile lifecycle performance.
