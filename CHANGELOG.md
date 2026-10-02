# Arc Emulator - Changelog

All significant changes to this project will be documented in this file.

---

## [1.6.0] - Core Expansion & Simpler Setup - 2026-10-02

This release adds five new platforms (GameCube, Wii, PS2, Nintendo DS and Sega Saturn) and rebuilds the first-run experience around a built-in core downloader, a smarter BIOS Manager, and a setup guide that suggests cores based on the games you actually own.

### 🕹️ New Platforms
- **GameCube & Wii (Experimental)**: Full Dolphin integration with controller style selection, correct Wii Remote / Nunchuk / Classic Controller detection, and sideways Wiimote profiles. The required `dolphin-emu/Sys` system folder (per-game compatibility database, codehandler and shaders) is downloaded automatically when the core installs, and fetched again on launch if it is ever missing.
- **PlayStation 2 (Experimental)**: PCSX2 core with a Vulkan renderer toggle and a forced `pcsx2_fastmem` fix so titles boot and load states safely.
- **Nintendo DS**: DeSmuME selected by default for out-of-the-box stability with melonDS as an alternative; reliable software framebuffer rendering, screen swapping, and accurate bottom-screen touch.
- **Sega Saturn**: Yabause by default with Beetle Saturn as the accuracy option, plus a complete 6-button touch layout (A/B/C, X/Y/Z, L, R, Start, Mode).

### ✨ Core Management & Setup
- **Core Downloader**: New in-app screen to browse, read about, and install libretro cores directly — no more tracking down and extracting files by hand.
- **BIOS Manager Rebuild**: Detects missing BIOS files and warns at load time instead of black-screening, and auto-generates melonDS `bios7.bin`, `bios9.bin` and `firmware.bin` placeholders.
- **Reordered Setup Guide**: Steps now run **Add Games → BIOS → Cores**, with contextual core suggestions based on the games detected in the library.
- **Guided Tour Expansion**: Grown to 6 steps covering the Core Downloader and the Settings relaunch instructions.
- **Experimental Core Notice**: PS2, GameCube and Wii display a warning dialog with a "Don't show again" preference.
- **Download Feedback**: Downloads surface real progress reporting instead of appearing to hang.

### 🎮 Controls
- **GameCube Touch Layout**: Redesigned portrait and landscape layouts with authentic ergonomics — circular A and B offset, wide X and Y pill buttons, bottom START, and the C-stick placed under the action buttons.
- **PlayStation 2 Buttons**: Added the missing SELECT and START buttons in landscape; tightened portrait D-Pad and action button spacing.
- **Nintendo DS Touch**: Bottom screen now responds accurately and instantly to taps without dragging; swap-screen label compacted to fit its box.
- **Wii Controller Profiles**: Profiles forwarded securely to the emulation thread via `setWiiControllerStyle()` rather than guessed at on the core side.
- **DS Debug Overlays**: DS touch debug and cursor overlay wired into Settings.

### ⚡ Rendering & Performance
- **Vulkan Bridge**: New `vulkan_bridge.cpp` with hardware-aware software fallback and automated context lifecycle management.
- **Library Scanning**: Optimized ROM scanning and scraper deduplication for improved indexing and metadata accuracy across platforms.
- **Audio Log Cleanup**: Reduced audio log spam so logcat stays readable during emulation sessions.

### 🛠️ Native Engine & Stability
- **Fault Guarding**: `InvokeGuardedCoreCall()` and a heartbeat watchdog prevent crashes and provide safe exit options for hung states.
- **Recoverable Hangs**: `g_coreWedged` refactored from a permanent one-way latch into a recoverable state, so sessions can be retried without restarting the app.
- **EGL Rotation Fix**: Surface rebuilds no longer signal `context_reset`, which had been corrupting Dolphin 3D geometry after rotating the device.
- **EGL Serialisation**: EGL setup and teardown serialised against `g_emuMutex` to eliminate surface lifecycle and rotation races.
- **Guarded Startup**: The first `surfaceChanged` report for a surface only establishes its size, fixing black screens caused by a premature EGL rebuild during core startup.
- **Crash Recovery Dialogs**: Smart recovery dialogs and refined error messaging for core load failures.
- **Correct `.so` Extraction**: Core zips now unpack the library named by the core id rather than whichever happens to be first in the archive.

### ⚙️ Input & Core Fixes
- **PS1 Input Restored**: Fixed DualShock B/Y colliding with the analog X/Y axis IDs, which left Cross and Square permanently unresponsive.
- **SwanStation Priority**: Listed ahead of PCSX ReARMed so PS1 games stop loading the wrong core, with port 0 announced as an analog subclass before `retro_load_game`.
- **Missing BIOS Warning**: PS1 games warn at load when no BIOS is present instead of black-screening.
- **N64 / PS2 / PS1 Boot Fixes**: Linked GLESv3, dropped the manual `JNI_OnLoad`, forced `pcsx2_fastmem` off, and set up EGL for surfaces that attach late so cores stop stalling.
- **Explicit OpenGLES Binding**: `setupEGL` binds the API explicitly so Qualcomm and Adreno drivers create the correct context.
- **Device Negotiation**: Digital buttons accepted on the analog device; pointer and mouse input restricted to DS and Dolphin.

### 📂 Library & Diagnostics
- **Debug Settings**: New Settings section with toggles for Touch Debug, High Level Performance Tests, and DS Touch/Cursor Debug, all disabled by default.
- **LogManager**: "Nuclear Logging" mode to filter system noise and export actionable debug logs, alongside a new `clean-logcat.ps1` script.
- **Build Maintenance**: Android Gradle Plugin upgraded to 9.4.1.

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
