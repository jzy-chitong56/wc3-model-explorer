# shaders/

Reference GLSL files retained for documentation purposes. **These shaders are not
loaded by the application** — the renderer uses inline shader strings in
`GlPreviewCanvas`.

## Why keep them?

These were drafted during an exploration into supporting Warcraft III: Reforged
HD models (PBR rendering with normal maps and ORM textures). HD support was
intentionally dropped — the app now detects HD models (any material with a
non-empty shader name) and shows an "Unsupported Format" badge instead of
rendering them.

The files are kept as a starting point in case HD support is revisited later, or
as a reference for anyone studying Reforged material conventions.

## Files

- `HDDiffuseVertColor.vert` — vertex stage prototype with HD skinning attributes
- `HDDiffuseVertColor.frag` — fragment stage prototype with PBR diffuse path

If you decide HD support should stay dropped permanently, the entire `shaders/`
folder can be deleted without impact.
