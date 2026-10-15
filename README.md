# GameLingo MVP

Android native Kotlin prototype for screen text translation using a floating button.

## Correct repository structure
Keep `settings.gradle.kts`, root `build.gradle.kts`, and `gradle.properties` at the root. The Android module belongs inside `app/`. Workflow belongs in `.github/workflows/build-apk.yml`.

## Build
GitHub Actions -> Build GameLingo APK -> Run workflow. Download `GameLingo-debug-apk` artifact if the job succeeds.

## Limitations
- Early prototype; not validated on a physical device.
- The initial translation model download requires internet. Downloaded supported models can be used offline.
- OCR picks the ML Kit recognizer from the source language: Japanese, Chinese, and Korean use their own recognizers; everything else uses the Latin recognizer. Scripts ML Kit cannot read (such as Thai and Cyrillic) are not recognized yet.
- Online translation fallback is not implemented yet.
