# Changelog

All notable changes to this project will be documented in this file.

## [1.3.0] - 2026-05-03

### Added
- Tag system: auto-extract tags from `readme.html` files (in a model's folder or its parent), parsing lines like `Tags: Hero, Unit, Historic`
- Tri-state tag filter buttons in the toolbar — click to cycle **Neutral → Include → Exclude** to require or hide models carrying a given tag
- Per-model custom tags via the right-click **Tags** submenu (add / remove / create new)
- **Settings > Tags**: toggle automatic tag parsing, hide unwanted tags from the filter bar, and restore previously hidden tags
- HD (Reforged) model detection: cards display a static "HD (Unsupported)" badge instead of a thumbnail, and clicking shows a warning dialog rather than opening the viewer
- Project licensing files

### Changed
- Card grid distributes spacing evenly across the viewport so cards fill the available width instead of leaving a ragged right margin
- Camera settings: yaw/pitch sliders replaced with circular angle dials; controls and 3D preview now sit side-by-side in a resizable horizontal split
- Mouse-orbit drag in the camera preview now updates the yaw/pitch dials live (the dragged angles are saved on Apply)
- Settings: "recent models" renamed to "recent folders" (now records the directory of the last opened scan rather than individual model paths; capacity reduced from 20 → 15)

### Removed
- HD/Reforged skinning and rendering paths that were prototyped but never fully supported — HD models are now explicitly gated rather than partially rendered

## [1.2.0] - 2026-03-29

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
