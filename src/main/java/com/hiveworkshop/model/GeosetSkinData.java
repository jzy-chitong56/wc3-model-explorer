package com.hiveworkshop.model;

/**
 * Per-geoset skinning data for both SD and HD vertex deformation.
 * <p>SD (classic) skinning:</p>
 * <ul>
 *   <li>bindVertices – bind-pose positions, flat [x0,y0,z0, x1,y1,z1, ...]</li>
 *   <li>vertexGroup  – for each vertex: index into groupBoneObjectIds</li>
 *   <li>groupBoneObjectIds – for each group: array of bone objectIds that influence it</li>
 * </ul>
 * <p>HD (Reforged) skinning:</p>
 * <ul>
 *   <li>hdBoneIds – per vertex, up to 4 bone objectIds: flat [b0,b1,b2,b3, b0,b1,b2,b3, ...]</li>
 *   <li>hdWeights – per vertex, up to 4 weights (0.0–1.0): flat [w0,w1,w2,w3, w0,w1,w2,w3, ...]</li>
 * </ul>
 */
public record GeosetSkinData(
        float[]   bindVertices,
        int[]     vertexGroup,
        int[][]   groupBoneObjectIds,
        int       materialId,
        int[]     hdBoneIds,
        float[]   hdWeights
) {
    public static final GeosetSkinData EMPTY =
            new GeosetSkinData(new float[0], new int[0], new int[0][], -1, null, null);

    /** SD constructor without materialId. */
    public GeosetSkinData(float[] bindVertices, int[] vertexGroup, int[][] groupBoneObjectIds) {
        this(bindVertices, vertexGroup, groupBoneObjectIds, -1, null, null);
    }

    /** SD constructor with materialId. */
    public GeosetSkinData(float[] bindVertices, int[] vertexGroup, int[][] groupBoneObjectIds, int materialId) {
        this(bindVertices, vertexGroup, groupBoneObjectIds, materialId, null, null);
    }

    public int vertexCount() {
        return bindVertices.length / 3;
    }

    /** True if this geoset has SD skinning data. */
    public boolean hasSkinning() {
        return groupBoneObjectIds.length > 0;
    }

    /** True if this geoset has HD (Reforged) skinning data. */
    public boolean hasHdSkinning() {
        return hdBoneIds != null && hdBoneIds.length > 0;
    }

    /** True if this geoset has any form of skinning (SD or HD). */
    public boolean hasAnySkinning() {
        return hasSkinning() || hasHdSkinning();
    }
}
