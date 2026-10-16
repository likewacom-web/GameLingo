# GameLingo MVP

Android native Kotlin prototype for screen text translation using a floating button.

## Correct repository structure
Keep `settings.gradle.kts`, root `build.gradle.kts`, and `gradle.properties` at the root. The Android module belongs inside `app/`. Workflow belongs in `.github/workflows/build-apk.yml`.

## Build
GitHub Actions -> Build GameLingo APK -> Run workflow. Each successful build is published to the `latest` pre-release: download it from https://github.com/likewacom-web/GameLingo/releases/download/latest/GameLingo-debug.apk (no login needed). The `GameLingo-debug-apk` workflow artifact is also kept.

## Limitations
- Early prototype; not validated on a physical device.
- The initial translation model download requires internet. Downloaded supported models can be used offline.
- OCR picks the ML Kit recognizer from the source language: Japanese, Chinese, and Korean use their own recognizers; Thai uses Tesseract (the `tha` model is downloaded into `app/src/main/assets/tessdata/` by the build workflow); everything else uses the Latin recognizer. Cyrillic is not recognized yet.
- To build locally, download [tha.traineddata](https://github.com/tesseract-ocr/tessdata_fast/raw/main/tha.traineddata) into `app/src/main/assets/tessdata/` first.
- Online translation fallback is not implemented yet.

## Glossaries
Fixed translations override the on-device translator: the user's own corrections (main screen → แก้คำแปล), then glossary packs, then a small built-in list of common English → Thai game terms. A pack is a JSON file:

```json
{ "name": "9 Kings", "sourceLanguage": "en", "targetLanguage": "th", "entries": { "Royal Decree": "พระราชกฤษฎีกา" } }
```

Packs in `app/src/main/assets/glossaries/` are bundled (9 Kings is included); others can be imported in the app (นำเข้าพจนานุกรมเกม). Pack terms are also swapped into longer sentences before translation.
