# Photo Editor

A simple Android photo editor written in Kotlin. It uses only the Android framework, with no extra libraries.

## Features

- Open a photo from the gallery, or share or "Edit with" a photo from another app
- Adjust brightness, contrast and saturation, with a live preview
- Filters: B&W, Sepia, Vintage, Warm, Cool, Vivid, Fade and Invert, with thumbnails
- Crop with a draggable frame: Free, 1:1, 4:3, 3:4, 16:9 and 9:16
- Rotate left or right, flip horizontally or vertically
- Add text in seven colours and any size, by tapping where it should go
- Undo (last 8 steps) and Reset
- Save as a JPEG to `Pictures/PhotoEditor`

Requires Android 10 (API 29) or later.

## Get the APK

Every push runs the **Build APK** workflow in GitHub Actions:

1. Open the **Actions** tab and pick the latest run.
2. Download the `photo-editor-debug-apk` artifact and unzip it.
3. Copy `app-debug.apk` to your phone and open it. Allow "Install unknown apps" when Android asks.

## Build it yourself

Open the folder in Android Studio and press Run, or run:

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Code

| File                 | What it does                                          |
| -------------------- | ----------------------------------------------------- |
| `MainActivity.kt`    | Screen layout, tools, undo, open and save             |
| `EditorView.kt`      | Shows the photo, the crop frame and handles taps      |
| `ImageOps.kt`        | Load, crop, rotate, flip, filters, text, save         |
| `Filters.kt`         | Colour adjustments and filter presets                 |
