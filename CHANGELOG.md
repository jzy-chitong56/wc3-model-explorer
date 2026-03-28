# Changelog

All notable changes to this project will be documented in this file.

## [1.1.0] - 2026-03-28

### Fixed
- BLP textures appearing too bright due to double gamma correction when the blp-iio-plugin returns images with CS_LINEAR_RGB color model
- Thumbnail cache not invalidating when CASC/MPQ data sources are changed in Settings, causing stale thumbnails until manual rescan

### Changed
- Include project version in jpackage image name
