# WC3 Model Explorer

## Overview

A Warcraft III model explorer written in Java Swing with an OpenGL renderer based on LWJGL. Supports MDX (versions 800–1200) and MDL files. Reforged HD models are not supported.

The application has two main goals:

- browse a directory tree of `.mdx` / `.mdl` files and generate 3D thumbnails
- open a model viewer dialog to inspect meshes, animations, textures, materials, geosets, bones, helpers, attachments, ribbon emitters, particle emitters, extents, and collision shapes

## Current State

The project is release-ready (v1.0.0). It ships as a standalone executable via jpackage with a bundled JRE — no Java installation required.

Current capabilities:

- a Swing desktop shell (`MainWindow`) with directory scanning, search, sorting (name/size/folder), portrait filtering, advanced metadata filters, favorites, recent models, progressive list population, and thumbnail generation
- a model viewer dialog (`ModelViewerDialog`) with a large OpenGL preview and tabs for animation, info, textures, materials, geosets, UV maps, and nodes
- a custom OpenGL canvas (`GlPreviewCanvas`) built on `org.lwjgl.opengl.awt.AWTGLCanvas`
- parsing and metadata extraction backed by code adapted from Retera Model Studio
- texture lookup from disk, CASC, and MPQ sources
- map archive support (`.w3x` / `.w3m`) — both Reforged and pre-Reforged MPQ formats
- ribbon emitter and particle emitter 2 rendering with per-sequence visibility
- model camera view support
- persisted user settings in `%APPDATA%/wc3-model-explorer/settings.properties`
- i18n support (English, French)

## Primary Source Layout

- `src/main/java/com/hiveworkshop/Main.java`: application entry point
- `src/main/java/com/hiveworkshop/AppVersion.java`: runtime version from gradle
- `src/main/java/com/hiveworkshop/ui/MainWindow.java`: asset browser window
- `src/main/java/com/hiveworkshop/ui/ModelViewerDialog.java`: model inspection dialog
- `src/main/java/com/hiveworkshop/ui/GlPreviewCanvas.java`: OpenGL renderer and camera/input logic
- `src/main/java/com/hiveworkshop/ui/ThumbnailRenderer.java`: background thumbnail rendering
- `src/main/java/com/hiveworkshop/ui/SettingsDialog.java`: data source, theme, camera, and external program settings
- `src/main/java/com/hiveworkshop/parser/`: scanning, parsing, metadata extraction, animation, settings, and texture data source logic
- `src/main/java/com/hiveworkshop/model/`: immutable records and render data structures
- `src/main/java/com/hiveworkshop/i18n/`: internationalization
- `src/main/resources/i18n/`: message bundles (English, French)
- `src/main/resources/images/`: app icon and UI images
- `src/main/resources/version.properties`: version injected by gradle
- `libs/`: bundled jars used by the project

## Dependencies

The Gradle build depends on:

- LWJGL 3 (`lwjgl`, `lwjgl-opengl`, `lwjgl3-awt`)
- `modelstudio-0.05.jar` for Warcraft III model parsing support
- `JCASC.jar` for CASC access
- `JMPQ3` for MPQ access
- `blp-iio-plugin.jar` and `dds` for Warcraft texture decoding
- `org.json`
- FlatLaf
- `org.beryx.runtime` plugin (v2.0.1) for jpackage

The application is configured with:

- main class: `com.hiveworkshop.Main`
- JVM arg: `--enable-native-access=ALL-UNNAMED`
- JDK 17+ required (configured in `gradle.properties`)

## Build And Run

Use Gradle from the repository root.

Build:

```bash
./gradlew build
```

Run:

```bash
./gradlew run
```

Package as standalone application:

```bash
# App image (portable folder with .exe / binary)
./gradlew jpackageImage
# Output: build/jpackage/WC3ModelExplorer/

# Installer (.exe on Windows, .dmg on macOS, .deb on Linux)
./gradlew jpackage
```

Notes:

- JDK 17 is configured in `gradle.properties` — no need to set JAVA_HOME manually.
- Windows natives are included by default. For other platforms, add the corresponding LWJGL native dependencies.
- The OpenGL preview requires working GPU / driver support.

## Functional Areas

### Asset Browser

- recursive scan of a chosen root directory for `.mdx` and `.mdl`
- open map archives (`.w3x`, `.w3m`) directly
- cached scan results per root path
- filename and model name search
- sort by name, file size, or parent folder
- portrait filtering
- advanced filters for animation name, texture path, polygon count, and file size
- progressive thumbnail scheduling so the UI remains responsive
- favorites and recent models tracking
- right-click context menu: copy path/file, open file location, open in external program, toggle favorite, delete file
- drag & drop models to external applications
- team color selector for thumbnails

### Model Viewer

- orbit, pan, zoom, and reset camera controls
- model camera view toggle (uses camera defined in the model file, mapped to orbit controls)
- animation playback with sequence selection, speed control (0.1x–3x), looping, and recentering
- 6 shading modes: solid, textured, lit, normals, geoset colors, bone count heat map
- wireframe toggle
- overlays for extent, grid, bones, helpers, attachments, ribbon emitters, particle emitters, collision shapes, and node names
- per-sequence emitter visibility (emitters hidden when no visibility keyframes in current sequence)
- team color selection
- screenshot export to PNG
- on-screen stats HUD
- tabs for animation, info, textures, materials, geosets, UV maps, and node hierarchy

### Texture Resolution

Texture lookup order:

1. relative to the model directory and its parent directories
2. relative to the scan root
3. CASC sources
4. MPQ sources

The code handles `.blp` / `.dds` fallback and prefers `.dds` when CASC is the only archive source.

## Known Gaps / Active Work

- handle vertex animation
- improve geoset texture display and UV mapping behavior
- `GlPreviewCanvas.java`, `ThumbnailRenderer.java`, and `BoneAnimator.java` are active renderer files and likely to keep changing while rendering parity is being improved

## Settings

User settings are stored in:

- `%APPDATA%/wc3-model-explorer/settings.properties`

Current settings include:

- last scanned root directory
- portrait filter
- thumbnail size and quality
- thumbnail team color
- CASC path
- MPQ archive list
- Swing look and feel
- language
- 3D background color
- default viewer camera yaw and pitch
- default animation used for thumbnail posing
- external program launch entries
- favorites list
- recent models list

## Guidance For Future Work

- Be careful around AWTGLCanvas lifecycle code in `GlPreviewCanvas`; shutdown handling exists to avoid native crashes during dialog close.
- Do not assume a texture exists on disk next to the model. The code intentionally supports mixed disk, CASC, and MPQ resolution.
- The `ThumbnailRenderer` uses a hidden UTILITY JFrame for its GL context — do not change its window type or it will appear in the taskbar.
