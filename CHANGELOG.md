# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Stop button to cancel directory scanning (useful for large root folders)
- Data source status indicator on main window status bar and in Settings > Data Sources tab
- Logs tab in Settings for viewing and copying application logs (for debugging/support)
- In-memory log capture (`AppLogBuffer`) with timestamps for all `System.out`/`System.err` output

### Changed
- Browse button is now disabled during scanning to prevent conflicting actions
- Scan cancellation uses cooperative `AtomicBoolean` flag for reliable abort of both directory walk and model parsing phases
- Moved 3D background color setting from Theme tab to Camera tab, now updates the camera preview live

## [1.1.0] - 2026-03-28

### Fixed
- BLP textures appearing too bright due to double gamma correction when the blp-iio-plugin returns images with CS_LINEAR_RGB color model
- Thumbnail cache not invalidating when CASC/MPQ data sources are changed in Settings, causing stale thumbnails until manual rescan

### Changed
- Include project version in jpackage image name
