package org.example.model;

import java.util.List;
import java.util.Map;

/**
 * Full animation data extracted from an MDX/MDL model.
 *
 * <p>{@code geosetAlpha} maps geoset index → KGAO alpha AnimTrack (single float values).
 * {@code geosetStaticAlpha} maps geoset index → static fallback alpha (0–1).
 */
public record ModelAnimData(
        List<SequenceInfo>    sequences,
        BoneNode[]            bones,             // all nodes (bones + helpers), indexed by position
        List<GeosetSkinData>  geosets,           // one entry per geoset, same order as ModelMesh
        Map<Integer, AnimTrack> geosetAlpha,     // geoset index → KGAO track
        Map<Integer, Float>     geosetStaticAlpha // geoset index → static alpha fallback
) {
    public static final ModelAnimData EMPTY =
            new ModelAnimData(List.of(), new BoneNode[0], List.of(), Map.of(), Map.of());

    /** Convenience constructor without geoset alpha (backward compat). */
    public ModelAnimData(List<SequenceInfo> sequences, BoneNode[] bones, List<GeosetSkinData> geosets) {
        this(sequences, bones, geosets, Map.of(), Map.of());
    }

    public boolean hasAnimation() {
        return !sequences.isEmpty() && bones.length > 0;
    }
}
