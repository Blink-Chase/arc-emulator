# Arc Emulator

An open-source Libretro emulator for Android focused on clean user experience, high compatibility, and deep customization.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%20%2F%20Compose-purple.svg)](https://kotlinlang.org/)

**Arc Emulator** is a modern Android emulation platform powered by Libretro. Designed with a focus on simplicity and performance, it provides a seamless bridge between classic hardware and modern mobile devices.

> [!NOTE]
> **Pre-Release Beta:** Arc is currently in active development. As a pre-release version, you may encounter bugs or rough edges. Your feedback is incredibly important as we continue to improve the experience.

---

## 📸 Visual Showcase

| Home Screen | Library Grid | Controller Mapping |
| :--- | :--- | :--- |
| ![Home](docs/screenshots/home.png) | ![Library](docs/screenshots/library.png) | ![Mapping](docs/screenshots/mapping.png) |

---

## ✨ Key Features

*   **Modern Jetpack Compose UI:** A responsive, "Aero-inspired" interface with full Light/Dark/System theme options.
*   **Physical Controller Support:** Full Bluetooth and USB gamepad compatibility with a step-by-step Mapping Wizard.
*   **Pro Touch Engine:** Individually resizable and transparent buttons, high-performance Canvas joysticks, and 8-way directional hints.
*   **Automatic Metadata & Art:** Background scraping for Box Art, game descriptions, and ratings via Libretro and ScreenScraper.
*   **Safe Navigation:** High-reliability state preservation (No "Amnesia") and non-blocking engine parking for instant screen transitions.
*   **Save States & Rewind:** Instant progress management with dedicated slots and visual previews.

---

## 🎮 Supported Platforms

| Platform | Status | Preferred Libretro Core |
| :--- | :--- | :--- |
| **SNES** | ✅ Working | `snes9x_libretro_android` |
| **GBA / GB / GBC** | ✅ Working | `mgba_libretro_android` |
| **PS1** | ✅ Working | `pcsx_rearmed_libretro_android` |
| **N64** | ✅ Working | `mupen64plus_next_libretro_android` |
| **Genesis / Mega Drive** | ✅ Working | `genesis_plus_gx_libretro_android` |
| **GameCube** | ❌ Not Working (Planned) | `dolphin_libretro_android` |
| **Wii** | ❌ Not Working (Planned) | `dolphin_libretro_android` |

> [!NOTE]
> **Active Development:** Platform support and Libretro core integrations are actively expanding. Additional cores and optimizations are planned for future updates.

### Core Downloads & Import
If a core is not bundled, you can download them from the [Libretro Nightly Builds](https://buildbot.libretro.com/nightly/android/latest/).
*   **Mobile & Tablet Users:** Look for the **arm64-v8a** version for optimal performance.
*   **Import via App:** Use the **Setup Guide** or navigate to **Settings > System > Import Cores** to select and install your `.so` core files.
*   **Manual Install:** Alternatively, place the `.so` files into your `Documents/Arc/cores/` directory and use the **Rescan Cores** button in the app.

---

## 🚀 Getting Started

### 1. Installation
Download the latest `Arc-Emulator-vX.X.X.apk` from the [Releases](../../releases) page and install it on your Android device.

### 2. Initial Setup
Upon first launch, the **Guided Setup** will walk you through:
*   Granting the app permission to access your ROM folders.
*   Scanning your collection to build your library.
*   Checking for necessary BIOS files for systems like PS1.

### 3. Folder Structure
Arc organizes your data in a visible folder in your **Documents** directory:
*   **`Documents/Arc/system/`**: Place your BIOS files here (e.g., `scph5501.bin`).
*   **`Documents/Arc/saves/`**: Your game saves and state files.
*   **`Documents/Arc/Covers/`**: Local box art storage.
*   **`Documents/Arc/templates/`**: Your custom touch layout templates.
*   **`Documents/Arc/cores/`**: Manually add extra `.so` cores here if they aren't bundled.

---

## 🕹️ Advanced Customization

### Touch Layout Templates
Don't want to re-align your buttons for every game?
1.  Enter **Edit Mode** during gameplay.
2.  Arrange your buttons, then select **Save as Template** from the menu.
3.  In any other game, select **Apply Template** to instantly load your favorite layout.

### Per-Button Precision
Every button in Arc is independent. In Edit Mode, tap a button to adjust its **Scale** (for larger fingers) and **Alpha** (for a cleaner view) using precise sliders.

---

## 🛠️ Troubleshooting & Tips

### Missing BIOS Files
Systems like PS1 and GBA perform best with original BIOS files. Use the **BIOS Manager** in Settings to check your status; it will show a ✅ if the file is correctly placed in `Arc/system/`.

### Controller Stick Drift
If your physical controller has stick drift, navigate to **Settings > Input Device** and adjust the **Analog Deadzone** slider to increase the "null zone" of your thumbsticks.

### Black Screen on Resume
If the game screen remains black after returning from a menu:
1.  Open the **In-Game Menu** and select **Resume**.
2.  If the picture still doesn't appear, select **Reset Game**.
3.  If the issue persists, restart the app and load the game again.
*Please report it if you consistently encounter this issue! Providing details about the core and ROM helps us improve the rendering engine.*

---

## 🤝 Community & Feedback

Found a bug or have a feature request?
*   **Issues:** Report them on the [GitHub Issues](../../issues) page.
*   **Feedback:** We truly value your feedback to help improve Arc.
*   **Bug Reports:** If a game crashes, please include details about your device, the emulator core, and the ROM type. The **Diagnostics** tool in the **About** screen provides internal state information that is very helpful for troubleshooting.

---

## 📜 License

Copyright (C) 2026 Blink-Chase. Licensed under the [GNU General Public License v3.0](LICENSE).
