# Credits & References

## Core Libraries

### LWJGL 3 (Lightweight Java Game Library)
- **Version**: 3.3.6
- **URL**: https://www.lwjgl.org/
- **License**: BSD 3-Clause
- **Usage**: OpenGL rendering, native windowing

### lwjgl3-awt
- **Version**: 0.2.3
- **URL**: https://github.com/LWJGLX/lwjgl3-awt
- **Usage**: AWT canvas integration for LWJGL OpenGL context

### FlatLaf
- **Version**: 3.4
- **URL**: https://www.formdev.com/flatlaf/
- **License**: Apache 2.0
- **Usage**: Modern flat look-and-feel for Swing UI

### org.json
- **Version**: 20240303
- **URL**: https://github.com/stleary/JSON-java
- **License**: JSON License
- **Usage**: JSON parsing for settings and configuration

## Warcraft III Format Libraries

### Retera Model Studio (modelstudio-0.05.jar)
- **URL**: https://github.com/Retera/RetesModelStudio
- **License**: GPL
- **Usage**: MDX/MDL model format parsing (MdlxModel, MdlxBone, MdlxGeoset, MdlxMaterial, MdlxSequence, MdlxTexture, MdlxRibbonEmitter, etc.)
- **Package**: `com.hiveworkshop.rms.parsers.mdlx`


### JCASC
- **URL**: https://github.com/inwc3/JCASC
- **Usage**: CASC (Content Addressable Storage Container) archive reading for Warcraft III Reforged game data

### JMPQ3
- **Version**: 1.7.14
- **URL**: https://github.com/inwc3/JMPQ3
- **License**: GPL 3.0
- **Usage**: MPQ archive reading for legacy Warcraft III game data

### BLP IIO Plugin (blp-iio-plugin.jar)
- **URL**: https://github.com/DrSuperGood/blp-iio-plugin
- **Usage**: Java ImageIO plugin for reading BLP (Blizzard Picture) texture files

### net.nikr:dds
- **Version**: 1.0.0
- **URL**: http://maven.nikr.net/
- **Usage**: DDS (DirectDraw Surface) texture format decoding

## Reference Implementations

### Retera Model Studio (RMS)
- **URL**: https://github.com/Retera/ReterasModelStudio
- **Referenced for**:
  - Camera framing conventions (boundsRadius-based, from `AnimatedPerspectiveViewport`)
  - Bone matrix calculation: `T(pivot+trans) * R(quat) * S(scale) * T(-pivot)` (column-major 4x4)
  - DontInherit flag handling and inheritance cancellation
  - Billboard bone orientation
  - Ribbon emitter simulation: pivot-based positioning, per-particle velocity/gravity, color interpolation
  - Geoset animation (alpha, color, UV transforms)
  - ParticleEmitter2 rendering: billboard quads, velocity-oriented tails, 3-segment lifecycle, 7-vector billboard system
  - Node type icons (`bone.png`, `helperhand.png`, `attachment.png`, `ribbon.png`, `particle2.png`) from `res/UI/Widgets/ReteraStudio/NodeIcons/`
  - MDX v1200 format changes: [commit d865928](https://github.com/Retera/ReterasModelStudio/commit/d8659281a0f242ff79abaae0ef4e874f27b48e4c) — Light chunk `shadowIntensity` field, forward-compatible version checks (`>= 900` instead of explicit version lists)

### war3-model (4eb0da)
- **URL**: https://github.com/4eb0da/war3-model
- **Referenced for**:
  - Ribbon emitter lifecycle: emission threshold, one-segment-per-tick spawning, age-based UV mapping
  - Texture atlas support for ribbons (rows/columns/textureSlot)
  - Visibility gating: visibility only controls emission, not particle aging/removal
  - Blend mode mapping for material filter modes

### Warcraft III MDX Format Documentation
- **URL**: https://www.hiveworkshop.com/threads/mdx-specifications.240487/
- **Referenced for**:
  - MDX binary format structure
  - Animation track tags (KGTR, KGRT, KGSC, KGAO, KGAC, KRHA, KRHB, KRAL, KRCO, KRVS, KRTX)
  - Node flags (billboard, DontInherit bits)
  - Material layer filter modes and shading flags
