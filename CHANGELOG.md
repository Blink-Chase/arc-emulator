# Arc Emulator - Changelog

All significant changes to this project will be documented in this file.

---

## [1.5.0] - Input & Controller Overhaul - 2026-08-29

This major update focuses on expanding the app's core capabilities with a completely rebuilt input engine, high-performance navigation, and automated metadata scraping.

### 🎮 Input & Controller Improvements
- **Physical Controller Support**: Introduced a robust `InputManager` with hierarchical profile resolution (**Game > Platform > Global**).
- **Controller Mapping Tool**: New interactive setup guide for calibrating Bluetooth and USB controllers with real-time feedback.
- **Analog Deadzone Control**: Customizable deadzone slider in Settings to eliminate stick drift and fine-tune sensitivity.
- **Device Selection**: Toggle emulated device types per platform (e.g., standard Joypad vs. Analog DualShock).
- **Pro Touch Customizer**: Buttons can now be individually scaled and opacified via new precision sliders in Edit Mode.
- **Canvas-Based Joysticks**: High-performance joystick implementation with **Octagonal Gate Hints** and concentric rings.
- **Layout Templates**: Save and share custom button arrangements as `.template` (JSON) files across games.
- **Auto-Hide Logic**: "Hide Touch on Controller" automatically removes overlays when a gamepad is detected.
- **Zero-Latency Bridge**: Refactored JNI input delivery for near-instant physical gamepad response times.

### ✨ UI/UX & Branding
- **Glassmorphism 2.0**: Refined translucent card design with 0.5.dp borders and optimized transparency.
- **Visual Styles**: Introduced "Modern" and "Classic" UI themes via new system enums.
- **Unified Headers**: Standardized header styles across all screens for a consistent, professional feel.
- **Home Identity**: Toggle between "Arc Icon" and "Arc Text" branding on the Home Screen.
- **Adaptive Branding**: New "Retro" adaptive icon with monochrome support and pure black background.
- **Guided Tour**: Added a step-by-step onboarding experience for new users.

### ⚡ Navigation & Performance
- **Snappier Transitions**: Halved `NavHost` transition times from 300ms to **150ms**.
- **Non-Blocking Exit**: Re-engineered "Quit Game" logic to navigate instantly while native cleanup runs in the background.
- **Engine Safety Lock**: Added a "Busy Guard" to prevent native crashes when rapidly switching between complex cores.
- **"Touch Eater" Overlay**: Prevents Android system log spam (`ViewPostIme`) and resource starvation during transitions.
- **Persistent Game State**: Centralized active game state in `ArcApp` (within `MainActivity`) to resolve issues where the game path was lost during heavy navigation.

### 📂 Library & Metadata
- **Automated Box Art**: Integrated a background service **(Libretro Thumbnails & ScreenScraper)** to fetch high-quality game covers.
- **Library View Styles**: Toggle between **Detailed** list and **Poster** grid views.
- **Automatic Pruning**: Scanner now identifies and removes library entries for deleted or moved files.
- **Optimized Scanning**: Removed artificial delays; libraries of 100s of games now process in seconds.

### 🛠️ Native Engine & Stability
- **Visual Handshake Fix**: Forced re-binding logic that ensures the game picture appears immediately on resume, fixing the persistent black screen bug.
- **`nativeForceNextFrame()`**: Added JNI function to manually trigger a frame draw for UI synchronization.
- **Android 15/16 Ready**: Manifest support for 16KB page sizes (`pageSizeCompat`) and native library extraction.
- **Database v5**: Upgraded to support persistent controller profiles and advanced metadata.
- **Refined Audio Sync**: Dynamic speed adjustment to prevent crackling during FPS drops.
- **Native Deadlock Fix**: Resolved a rendering hang in `video.cpp` during app shutdown.
- **Deduplicating Logger**: Implemented a `Logger` utility in `Utils.kt` to prevent log floods.

---

## [1.4.3] - Performance & Speed - 2026-08-27

### ⚡ Performance
- **Faster Transitions**: Initial work on reducing UI lag and snappier screen switching.
- **Instant Exit**: Early implementation of asynchronous core cleanup.
- **Optimized Scanning**: Initial removal of scanner delays for faster library feedback.

### ⚙️ Stability
- **Self-Cleaning Library**: Initial implementation of library entry validation.
- **Native Stability**: Resolved critical exit deadlocks in the rendering pipeline.
- **Android Compatibility**: Technical flags to silence system warnings on newer hardware.

---

## [1.4.2] - Hotfix - 2026-08-25

### ✨ UI & Visuals
- **Translucent Styling**: Introduced semi-transparent game cards with subtle borders.
- **Scanning Indicators**: Added real-time "Scanning..." text and progress spinner.
- **Extension Toggle**: Option to hide `.sfc`, `.gba`, etc. in the library view.

### ⚙️ Stability
- **Library Stability**: Improved permission handling to prevent "File Not Found" errors after reboots.
- **Smarter Scanning**: Refined scanner to ignore hidden/system folders.

---

## [1.4.1] - Management & Safety - 2026-08-19

### ✨ Interface
- **Unified Headers**: Standardized headers across the application.
- **Style Dropdown**: Added touch control style selection in Settings.

### ⚙️ Management
- **BIOS Manager**: New screen to track missing system files with ✅/❌ indicators.
- **Data Safeguards**: Added warning dialogs to "Wipe Library" and "Reset Settings."

---

## [1.4.0] - Branding & Overhaul - 2026-08-18

### ✨ Branding
- **Arc Branding**: Complete overhaul with signature **Cyan & Teal** identity.
- **Unified Theme**: Updated Dark and Light modes for a consistent experience.
- **Consistent Styling**: Disabled dynamic coloring to prioritize brand identity.

### 🛠️ Technical
- **Dependency Updates**: Upgraded Compose, Navigation, and Coil to latest stable versions.

---

## [1.3.1] - Hotfix - 2026-08-17

- **N64 Stability**: Fixed critical issue with Parallel N64 core failing on arm64-v8a.
- **Resilient Loading**: Engine automatically attempts alternative cores if initialization fails.
- **Architecture Detection**: Improved detection of 32-bit vs 64-bit library mismatches.

---

## [1.3.0] - Initial Public Release - 2026-08-17

- Initial release of Arc Emulator.
- Support for SNES, GBA, GB, GBC, Genesis, N64, and PS1.
- Custom touch controls and save state support.
