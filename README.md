# Capable - Accessibility App for Visually Impaired Users

A real-time obstacle detection Android app that provides audio feedback to help visually impaired users navigate safely.

## Features

- **Real-time Object Detection**: Uses MediaPipe's EfficientDet-Lite0 model for fast object detection
- **Depth Estimation**: Optional MiDaS depth estimation for distance awareness
- **Audio Feedback**: Text-to-Speech announces detected obstacles with direction and distance
- **Smart Frame Processing**: Processes 10 FPS while camera runs at higher rates for smooth preview
- **Priority-based Warnings**: Immediate alerts for vehicles and close obstacles

## Technologies

Core technologies and libraries used:

- **Kotlin** — primary language
- **Jetpack Compose** — UI
- **CameraX** — camera capture & preview
- **MediaPipe (EfficientDet‑Lite0, TFLite)** — real-time object detection
- **TensorFlow Lite** — runtime for ML models (MiDaS optional depth)
- **MiDaS (midas_small.tflite)** — optional depth-estimation model
- **Android TextToSpeech (TTS)** — audio feedback with priority queue
- **Kotlin Coroutines** — background/frame processing
- **Android Jetpack** (Lifecycle, ViewModel, Navigation) — app architecture
- **Gradle (Kotlin DSL)** — build system; Gradle wrapper included
- **ProGuard / R8** — release shrinking/obfuscation (configured via `proguard-rules.pro`)

Model files and third-party libraries are used under their respective licenses. Place any downloaded models into `app/src/main/assets/` (see below).

---

## Setup Instructions

### 1. Download Required ML Models

The object detection model is already included. For depth estimation (optional), download:

#### Depth Estimation Model (Optional)
Download MiDaS Small and place in `app/src/main/assets/`:
```
https://github.com/isl-org/MiDaS/releases/download/v2_1/model-small.tflite
```
Rename to: `midas_small.tflite`

### 2. Build the Project

On macOS / Linux:

```bash
./gradlew assembleDebug
```

On Windows (PowerShell / CMD):

```powershell
\.\gradlew.bat assembleDebug
# or
gradlew.bat assembleDebug
```

Or open the project in Android Studio and run the app from there.

### 2. Build the Project

```bash
./gradlew assembleDebug
```

Or open in Android Studio and run.

## Architecture

```
app/src/main/java/com/example/capable/
├── MainActivity.kt           # Main entry point with permission handling
├── audio/
│   └── TTSManager.kt         # Text-to-Speech with priority queue
├── camera/
│   └── FrameAnalyzer.kt      # CameraX frame processing with smart skipping
├── detection/
│   ├── ObjectDetectorHelper.kt   # MediaPipe object detection wrapper
│   └── DepthEstimatorHelper.kt   # TensorFlow Lite depth estimation
└── ui/
    ├── CameraScreen.kt       # Main Compose UI with camera preview
    └── HazardDetectionProcessor.kt  # Combines detection + TTS announcements
```

## Performance

Target performance based on device tier:
- **Budget phones**: 5-10 FPS
- **Mid-range**: 10-15 FPS  
- **Flagship**: 15-30 FPS

The app processes every ~3 camera frames (targeting 10 FPS analysis) while maintaining smooth camera preview.

## Audio Feedback

The app announces obstacles with:
- **Object name**: "car", "person", "chair", etc.
- **Distance**: "very close", "nearby", "ahead", "far"
- **Direction**: "on your left", "ahead", "on your right"

Example: "Car very close on your left" (critical priority)

## Requirements

- Android 8.0 (API 26) or higher
- Camera permission
- ~100MB storage for models

## Build Output

APK located at: `app/build/outputs/apk/debug/app-debug.apk`

## License

MIT License
