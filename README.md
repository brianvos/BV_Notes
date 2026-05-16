# BV Notes v2 — Kotlin Android Note-Taking App
### GoodNotes/Notability-style · Minimalist · Productivity Suite

---

## Design Language

- **Palette**: Warm off-white paper (`#FDFAF6`), soft grey-brown sidebar (`#EDE8E1`), black text, brown accents (`#7A5C3C`)
- **Font**: System Serif/Sans-Serif (Optimized for readability)
- **Layout**: Responsive Design — Optimized for both Tablets (Landscape/Side-by-side) and Phones (Portrait/Stacked).
- **Transcripts**: Orientation-aware sidebar column (Landscape) or bottom row (Portrait).

---

## Feature Summary

 Feature | Details |
---|---|
 **Dual Canvas Modes** | Toggle between **Infinite Canvas** (whiteboard style) and **A4 Pages** (structured document style) |
 **Lasso Tool** | Circle handwriting or sketches to select and move them. Object-based selection. |
 **Select Tool** | Dedicated tool to tap, drag, and resize images, text boxes, and links. |
 **Object Eraser** | Intelligent eraser that removes entire strokes instantly. Protects paper templates. |
 **Pen & Highlighter** | Pressure-sensitive ink. Highlighter supports custom colors and remains transparent over text. |
 **Custom Colors** | Palette with 15 presets + **Full Color Wheel** with Hex and RGB manual input support. |
 **Inline Text Boxes** | Type directly on the paper with pixel-matched font sizes. Keyboard-safe "Safe View" on start. |
 **HD Lecture Recording** | Record 720p HD video lectures while taking notes. Subject-aware file naming (`LECTURE_Folder_Date`). |
 **Lecture Miniplayer** | YouTube-style floating player with Play/Pause, 10s Skip, and Scrubbing Seek Bar. |
 **Transcription** | Offline speech-to-text using **whisper.cpp**. Review and develop notes later. |
 **Page Management** | Add infinite pages. Snap-to-center navigation with "Flip" buttons and visual scrollbar. |
 **File Management** | Dedicated browser for PDFs, Photos, Audio, and Video. Secure deletion via MediaStore API. |
 **Export PDF** | Format-aware export: Multi-page A4 PDFs for "Pages" or Auto-bounding-box PDFs for "Canvas". |

---

## Project Structure

```
BVNotes2/
├── app/src/main/
│   ├── java/com/bv/notes/
│   │   ├── data/
│   │   │   ├── db/          Database.kt      Room DB (v4), Migrations, Converters
│   │   │   ├── model/       Models.kt        Note (Format/Page state), Folder, NoteElement
│   │   │   └── repository/  NoteRepository.kt
│   │   ├── ui/
│   │   │   ├── MainActivity.kt
│   │   │   ├── home/        HomeFragment.kt  Sidebar + Folder management
│   │   │   ├── editor/
│   │   │   │   ├── NoteCanvasView.kt        ★ Unified Render Engine (Focal Zoom, Lasso, Layers)
│   │   │   │   └── NoteEditorFragment.kt    Inline Editing & Media coordination
│   │   │   └── files/       FilesFragment.kt Media browser with secure delete
│   │   └── util/
│   │       ├── AudioRecorderHelper.kt       Mic + Transcription Management
│   │       ├── LectureManager.kt            HD Video Capture + Transcriber sync
│   │       ├── ColorWheelView.kt            Custom spectrum picker
│   │       ├── PdfExporter.kt               Format-aware PDF engine
│   │       └── ElementSerializer.kt         Gson JSON model
│   └── res/
│       ├── layout/          Responsive XML layouts (Orientation aware)
│       ├── drawable/        High-quality vector icons + tool states
│       └── values/          Modern Material3 color system & styles
```

---

## Canvas Architecture

Notes now support two distinct data layouts:

```kotlin
enum class NoteFormat { 
    CANVAS, // Boundless whiteboard
    PAGES   // Discrete A4 surfaces (1000x1414 units)
}
```

### Layout Properties
- **Focal Zoom**: Pinching zooms into the specific point between fingers rather than the top-left.
- **Center Lock**: Pages automatically stay centered horizontally during screen rotation or sidebar toggles.
- **Layering**: Rendering pipeline ensures Photos/Text are drawn first, followed by Ink, allowing you to annotate on top of images.

---

## Technical Details

 Feature | Implementation |
---|---|
 **Ink Rendering** | Viewport-based drawing with `Path.quadTo` for smooth bezier curves. |
 **Lasso Logic** | Uses `android.graphics.Region` hit-testing for complex path containment. |
 **Offline AI** | Powered by **whisper.cpp** via JNI for high-accuracy local transcription. |
 **Video Specs** | H.264 High Profile, 720p, 5Mbps Video / 192kbps AAC Audio. |
 **Storage** | MediaStore API for public folders (`Pictures/`, `Music/`, `Movies/`, `Download/`). |
 **Permissions** | Runtime requests for `CAMERA`, `RECORD_AUDIO`, and API-specific media permissions. |

---

## Build Instructions

### Prerequisites
- **Android Studio Hedgehog** (2023.1.1) or later
- **JDK 17** / **Android SDK 34**
- **Compatible Hardware**: Optimized for tablets (e.g., Samsung Galaxy Tab S7+) but fully functional on modern Android phones.

### Steps
1. **Open** `BVNotes2/` in Android Studio.
2. **Gradle Sync** to download dependencies.
3. **Run** on any Android device. The UI will automatically adapt to the screen size.

---

## Credits & Acknowledgements

Special thanks to the following projects and tools that made BV Notes possible:

- **[OpenAI Whisper](https://github.com/openai/whisper)**: For the groundbreaking automatic speech recognition model.
- **[whisper.cpp](https://github.com/ggerganov/whisper.cpp)**: For the high-performance C++ port enabling offline inference on mobile devices.
- **Claude (Anthropic)**: For assistance in architecting complex UI components and refining the codebase.
- **Gemini (Google)**: For expert guidance on feature implementation, debugging, and project maintenance.
