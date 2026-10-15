# GameLingo MVP (Android Native)

GameLingo is an early Android prototype for translating visible game text using a floating button.

## MVP scope
- Android native (Kotlin)
- User-consented MediaProjection screen capture
- Floating `G` button
- OCR using ML Kit Text Recognition
- On-device translation using ML Kit Translate
- Selectable source/target language controls
- GitHub Actions workflow to build a debug APK

## Important limitations
- This is an **early scaffold, not a verified release APK**. It has not been compiled or tested on a physical device in this environment.
- The first run needs internet to download the on-device translation model. Translation can then work offline for downloaded language models.
- This MVP uses on-device translation only. A true online fallback provider is not wired yet; adding one can introduce API keys, privacy considerations, and potential cost. Do not put secret API keys in the Android app.
- OCR uses the Latin text recognizer in this starter, so Japanese/Korean/Chinese OCR is not supported yet. Add ML Kit Japanese, Korean, or Chinese recognizer dependencies and choose the recognizer based on source language before claiming multi-script support.
- Overlay and screen capture are subject to Android version, manufacturer, game capture protections, and user permissions.
- This scaffold requires a current Android SDK and Java 17 to build.

## Build using GitHub Actions (phone-friendly)
1. Create a new GitHub repository named `GameLingo`.
2. Upload all files in this ZIP while preserving the folder structure.
3. Open **Actions** → **Build GameLingo APK** → **Run workflow**.
4. When the run finishes, open its run page → **Artifacts** → download `GameLingo-debug-apk`.
5. Extract the ZIP artifact and install `app-debug.apk` on your Android phone. Android may ask you to allow installation from that source.

## First test
1. Open GameLingo and choose source and target languages.
2. Tap **เริ่มใช้งานปุ่มลอย**.
3. Allow “Display over other apps”.
4. Approve Android's screen-capture prompt.
5. Open a game, tap `G`, and inspect the translated text panel.

## Before wider testing
- Fix lifecycle/foreground-service behavior against the Android versions being targeted.
- Add script-specific OCR recognizers.
- Add robust selection/dragging and close/stop controls.
- Implement optional online translation backend.
- Add privacy notice and make capture state obvious.
- Test across portrait/landscape, secure screens, and popular game engines.
