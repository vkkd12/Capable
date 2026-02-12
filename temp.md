┌─────────────────────────────────────────────────────┐
│                    MAIN THREAD                       │
│  • Jetpack Compose UI rendering                     │
│  • Bounding box overlay drawing                     │
│  • TTS announcements (Android TTS runs on main)     │
│  • Button click handlers                            │
│  • State updates (FPS, hazard count)                │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│              CameraX ANALYZER THREAD                 │
│  (Single thread managed by CameraX)                 │
│  • FrameAnalyzer.analyze() called here              │
│  • Frame skipping logic (targetFps)                 │
│  • Bitmap conversion from camera frame              │
│  • Calls frameProcessor.processFrame()              │
│     ├── YOLOv8 inference (320x320) ← BLOCKING      │
│     ├── ByteTrack update                            │
│     ├── NMS post-processing                         │
│     └── HazardDetectionProcessor.processDetections()│
│          ├── Priority calculation                   │
│          ├── Announcement logic                     │
│          └── TTS queue submission                   │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│           BACKGROUND THREAD (Executors)              │
│  (Single thread from Executors.newSingleThreadExec)  │
│  • Depth Anything V2 inference (every 3rd frame)    │
│  • SegFormer B0 inference (every 5th frame)         │
│  • Runs in parallel with CameraX thread             │
│  • Results stored in shared variables               │
│     ├── lastDepthMap: FloatArray                    │
│     └── lastSegMap: IntArray                        │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│             COROUTINE SCOPES                         │
│  • OCR scanning loop (LaunchedEffect)               │
│  • SOS trigger (scope.launch)                       │
│  • Camera binding/unbinding (LaunchedEffect)        │
└─────────────────────────────────────────────────────┘



Data Flow Between Threads
CameraX Thread                Background Thread
     │                              │
     │  bitmap + detections         │
     │─────────────────────────────>│  (submitted via executor)
     │                              │
     │                              ├── Depth inference
     │                              ├── SegFormer inference
     │                              │
     │  reads lastDepthMap          │
     │<─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│  (shared variable, no lock)
     │  reads lastSegMap            │
     │<─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│
     │
     │  state updates (fps, count)
     │─────────────────────────────> Main Thread (recomposition)


Potential Issues
Potential Issues
Issue	Location	Risk
No synchronization on lastDepthMap/lastSegMap	HazardDetectionProcessor	Read/write race between CameraX and background thread — could read partially written array
TTS called from CameraX thread	TTSManager.speak()	Android TTS should be called from main thread
Bitmap shared between threads	currentBitmap for OCR	Could be recycled while OCR reads it



Suggested Fix — Add Thread Safety
// Use @Volatile + atomic reference for shared maps
@Volatile private var lastDepthMap: FloatArray? = null
@Volatile private var lastSegMap: IntArray? = null

// Post TTS to main thread
Handler(Looper.getMainLooper()).post {
    ttsManager.speak(message, priority)
}

// Copy bitmap for OCR
currentBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)