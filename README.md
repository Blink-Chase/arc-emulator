# Arc Emulator

An open-source Libretro emulator for Android focused on clean user experience, high compatibility and deep customisation.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%20%2F%20Compose-purple.svg)](https://kotlinlang.org/)

**Arc Emulator** is an open-source Android emulation app powered by Libretro, designed from the ground up for a seamless user experience. It combines high compatibility with extensive customization—featuring modern touch controls, save states, and broad platform support (SNES, Genesis, PS1, N64, and beyond) wrapped in a clean, intuitive interface.

---

## Key Features

* **Modern Jetpack Compose UI:** A responsive, polished material design interface with full Light/Dark/System theme options.
* **Broad Platform Support:** Play titles across classic home consoles and handhelds via Libretro cores.
* **Customizable On-Screen Controls:** Fully editable touch controls with multiple layout styles, custom size, opacity, and positioning options.
* **Save States & Rewind:** Save and load your exact game progress instantly with up to 5 dedicated slots per title.
* **Cheats & Fast-Forward:** Built-in support for Game Genie and Pro Action Replay codes, plus instant fast-forward toggles to speed through cutscenes.
* **Game Library Management:** Dedicated ROM library with automatic game indexing, customizable sorting (Name / Date Added), and Favorites.

---

## Supported Platforms

| Platform | Status | Libretro Core |
| :--- | :--- | :--- |
| **SNES** | ✅ Working | `snes9x_libretro_android` |
| **Genesis / Mega Drive** | ✅ Working | `genesis_plus_gx_libretro_android` |
| **PS1** | ✅ Working | `pcsx_rearmed_libretro_android` |
| **GBA** | ✅ Working | `mgba_libretro_android` |
| **GB / GBC** | ✅ Working | `mgba_libretro_android` |
| **N64** | ✅ Working | `mupen64plus_next_libretro_android` |
| **GameCube** | ❌ Planned | `dolphin_libretro_android` |
| **Wii** | ❌ Planned | `dolphin_libretro_android` |

> [!NOTE]
> **Active Development:** Platform support and Libretro core integrations are actively expanding. Additional cores, optimizations, and features are currently planned for future updates.

---

## Installation & Setup

### Option 1: Pre-built Release (Recommended)
1. Download the latest `Arc-Emulator-vX.X.X.apk` from the [Releases](../../releases) page.
2. Install the APK on your Android device (ensure "Install from Unknown Sources" is enabled in system settings).
3. Place your game ROMs in your device storage (e.g., `/storage/emulated/0/Retro ROMs/` or custom directory).
4. Launch Arc Emulator and grant storage permissions to scan your game folder.

### Core Storage Directory
* Libretro cores and runtime dependencies are loaded from:
  `/storage/emulated/0/Android/data/com.arc.emulator/files/cores/`

---

## Building From Source

### Prerequisites
* **Android Studio** Ladybug or newer
* **JDK 17** or higher
* **Android NDK** (for C/C++ native Libretro glue compilation)

### Build Commands

Clone the repository and build the debug APK:

```bash
git clone [https://github.com/Blink-Chase/Arc-Emulator.git](https://github.com/Blink-Chase/Arc-Emulator.git)
cd Arc-Emulator
./gradlew assembleDebug
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Project Structure

```text
Arc-Emulator/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/arc/emulator/   # Kotlin source code & Jetpack Compose UI
│   │   │   ├── cpp/                    # Native C/C++ JNI Libretro bridge
│   │   │   └── res/                    # Drawables, layouts, & application strings
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## ArcCore Web Platform (Planned)

An optional companion web hub designed to enhance your setup experience:

* **Custom Control Layouts:** Download user-created touch layouts optimized for specific game genres.
* **Cheat Databases:** Pre-configured cheat collections ready for one-click import.
* **Core Downloader:** Up-to-date, pre-compiled Libretro cores optimized for Android devices.

---

## License

Copyright (C) 2026 Blink-Chase.

Licensed under the [GNU General Public License v3.0](LICENSE). You are free to redistribute, modify, and build upon this project under the terms of the GPL-3.0 license.