package org.example.model;

/**
 * Per-geoset skinning data for SD (Standard Definition) vertex deformation.
 * bindVertices – bind-pose positions, flat [x0,y0,z0, x1,y1,z1, ...]
 * vertexGroup  – for each vertex: index into groupBoneObjectIds
 * groupBoneObjectIds – for each group: array of bone objectIds that influence it
 */
public record GeosetSkinData(
        float[]   bindVertices,
        int[]     vertexGroup,
        int[][]   groupBoneObjectIds,
        int       materialId
) {
    public static final GeosetSkinData EMPTY =
            new GeosetSkinData(new float[0], new int[0], new int[0][], -1);

    /** Backwards-compatible constructor without materialId. */
    public GeosetSkinData(float[] bindVertices, int[] vertexGroup, int[][] groupBoneObjectIds) {
        this(bindVertices, vertexGroup, groupBoneObjectIds, -1);
    }

    public int vertexCount() {
        return bindVertices.length / 3;
    }

    public boolean hasSkinning() {
        return groupBoneObjectIds.length > 0;
    }
}
