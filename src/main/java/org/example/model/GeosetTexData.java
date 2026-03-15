package org.example.model;

/**
 * Per-geoset texture + UV information needed for textured rendering.
 *
 * <p>A WC3 material can have multiple layers. The parser scans all layers to find:
 * <ul>
 *   <li>{@code texturePath} — the base (non-replaceable) texture path, e.g. "Textures\Grunt.blp"</li>
 *   <li>{@code replaceableId} — 0=normal, 1=TeamColor, 2=TeamGlow (from any layer)</li>
 *   <li>{@code filterMode} — blend mode ordinal from the base layer's FilterMode enum</li>
 * </ul>
 *
 * <p>When {@code replaceableId} is 1 or 2, the renderer composites the team color
 * with the base texture at load time using:
 * {@code result_rgb = base_alpha * base_rgb + (1 - base_alpha) * tc_rgb}
 */
public record GeosetTexData(float[] uvs, String texturePath, int filterMode, int replaceableId) {
    public static final GeosetTexData EMPTY = new GeosetTexData(new float[0], "", 0, 0);

    public boolean hasUvs() {
        return uvs != null && uvs.length > 0;
    }

    /** True if this geoset has a team color layer (replaceableId 1 or 2). */
    public boolean hasTeamColor() {
        return replaceableId == 1 || replaceableId == 2;
    }

    /** True if this geoset is opaque (NONE or TRANSPARENT) for two-pass ordering. */
    public boolean isOpaque() {
        // TC (replaceableId=1) is treated as opaque, TeamGlow (2) as transparent/additive
        if (replaceableId == 1) return true;
        if (replaceableId == 2) return false;
        return filterMode <= 1; // NONE or TRANSPARENT
    }
}
