package com.hiveworkshop.model;

/**
 * Per-geoset SD (classic) skinning data.
 * <ul>
 *   <li>bindVertices – bind-pose positions, flat [x0,y0,z0, x1,y1,z1, ...]</li>
 *   <li>vertexGroup  – for each vertex: index into groupBoneObjectIds</li>
 *   <li>groupBoneObjectIds – for each group: array of bone objectIds that influence it</li>
 * </ul>
 */
public record GeosetSkinData(
        float[]   bindVertices,
        int[]     vertexGroup,
        int[][]   groupBoneObjectIds,
        int       materialId
) {
    public static final GeosetSkinData EMPTY =
            new GeosetSkinData(new float[0], new int[0], new int[0][], -1);

    /** Constructor without materialId. */
    public GeosetSkinData(float[] bindVertices, int[] vertexGroup, int[][] groupBoneObjectIds) {
        this(bindVertices, vertexGroup, groupBoneObjectIds, -1);
    }

    public int vertexCount() {
        return bindVertices.length / 3;
    }

    /** True if this geoset has skinning data. */
    public boolean hasSkinning() {
        return groupBoneObjectIds.length > 0;
    }
}
