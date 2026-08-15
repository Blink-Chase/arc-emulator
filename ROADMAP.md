# Arc Emulator - Roadmap & Project Status

## Project Overview

**Arc Emulator Hub** is an Android emulator frontend:

- **Android App**: Jetpack Compose UI + C++ NDK libretro bridge
- **Package**: com.blinkchase.arc-emulator

## Version Plan

### Legacy Releases (Nova)

* **v1.0.0 (Released)** — Initial architecture: ROM Library, touch controls, save states, SNES/Genesis/GBA support.
* **v1.1.0 (Released)** — Theme & UI overhaul: Dark/Light/System theme toggles, About & Help screens.
* **v1.2.0 (Released)** — Performance & Core optimization: PS1 fixes, audio buffer optimizations, threaded audio, N64 game loading, and save state reliability.

---

### Arc Emulator Releases

* **v1.3.0 (Released)** — Smarter library scanning (auto-ignoring junk), and improved home screen with data detection.

**Planned Features:**

- Sort Games by name/date
- Filter Games by platform
- Better search UX
- Fix Navigation issues like scrolling in Portrait/Landscape Mode

---

### v1.4.0

**Focus**: Import & Controllers

**Planned Features:**

- Multi-select ROM import
- Physical controller mapping
- Controller profiles

---

### v1.5.0

**Focus**: Advanced Features

**Planned Features:**

- Cheat codes support
- Shader support
- CRT filter effects

---

### v1.6.0

**Focus**: Core Upgrades

**Planned Features:**

- N64 core improvements
- Better save state slots
- Auto-save feature

---

### v1.7.0

**Focus**: Cloud & Sync

**Planned Features:**

- Cloud save backup
- Settings export/import

---

### v1.8.0

**Focus**: Platform Support

**Planned Features:**

- WonderSwan support
- TurboGrafx-16 support

---

### v1.9.0

**Focus**: Polish

**Planned Features:**

- Animation improvements
- Memory optimizations
- Bug fixes

---

### v2.0.0

**Focus**: Major Release

**Planned Features:**

- UI redesign
- New features
- Complete core coverage

---

## Supported Platforms


| Platform | Status            | Core                              |
| -------- | ----------------- | --------------------------------- |
| SNES     | ✅ Working         | snes9x_libretro_android           |
| Genesis  | ✅ Working         | genesis_plus_gx_libretro_android  |
| PS1      | ✅ Working         | pcsx_rearmed_libretro_android     |
| GBA      | ✅ Working         | mgba_libretro_android             |
| GB/GBC   | ✅ Working         | mgba_libretro_android             |
| N64      | ✅ Working         | mupen64plus_next_libretro_android |
| GameCube | ❌ Planned         | dolphin_libretro_android          |
| Wii      | ❌ Planned         | dolphin_libretro_android          |

> [!NOTE]
> **Active Development:** Platform support and Libretro core integrations are actively expanding. Additional cores, optimizations, and features are currently planned for future updates.

---

## GitHub Workflow

### Branches

- **main** - Stable releases (v1.0.0, v1.1.0, etc.)

### CI/CD

GitHub Actions automatically builds and releases:

- **Push to main** → Creates official release
- **Every push** → Builds debug APK

---

## Contributing

This project is licensed under **GNU GPL v3.0**.

## Links

- **GitHub**: [https://github.com/Blink-Chase/arc-emulator](https://github.com/Blink-Chase/arc-emulator)

