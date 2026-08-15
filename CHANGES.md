# CHANGES.md

A changelog for tracking significant changes to Arc Emulator.

---

## Workflow & Development Notes

### Current Development Flow

1. **Make changes** - Edit source files in `android-app/`
2. **Build** - Run `android-app/build.bat` or use Android Studio
3. **Test** - Install APK on device/emulator
4. **Commit** - Write descriptive commit message

### Writing Good Commits

When making changes, include:
- **What** changed (brief description)
- **Why** it changed (reason/purpose)
- **Files** modified

Example commit message:
```
Add PS1 controller support to GameScreen

- Added new InputStyle enum for PS1 layout
- Updated EnhancedControlsOverlay with diamond button arrangement
- Modified MainActivity to handle PS1-specific button mappings

Closes #12
```

---

## GitHub Actions Workflow Ideas

### Automated Build & Test

We could set up GitHub Actions to:

1. **Build on Push**
   - Compile Android APK automatically
   - Run lint checks
   - Build debug APK

2. **Create Release APK**
   - On git tags (e.g., v1.0.0)
   - Generate signed release APK
   - Attach APK to GitHub releases

3. **Code Quality**
   - Kotlin lint checks
   - Dependency updates (Dependabot)
   - Security scanning

### Suggested Workflow File

Create `.github/workflows/android.yml`:

```yaml
name: Build Android

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build with Gradle
        run: ./android-app/gradlew build
      - name: Build APK
        run: ./android-app/gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: arc-emulator-debug.apk
          path: android-app/app/build/outputs/apk/debug/
```

---

## TODO: Better Workflow

- [ ] Set up GitHub Actions for auto-building
- [ ] Configure Dependabot for dependency updates
- [ ] Create release workflow for publishing APKs
- [ ] Add lint checks to CI
- [ ] Document coding standards
- [ ] Set up code review process

---