package com.hiveworkshop.model;

import java.util.List;
import java.util.Locale;

public record ModelMetadata(
        List<String> animationNames,
        List<String> texturePaths,
        int polygonCount,
        int vertexCount,
        int boneCount,
        int sequenceCount,
        String modelName,
        boolean isHd
) {
    public static final int UNKNOWN_POLYGON_COUNT = -1;
    public static final ModelMetadata EMPTY = new ModelMetadata(List.of(), List.of(), UNKNOWN_POLYGON_COUNT, 0, 0, 0, "", false);

    /** Backwards-compatible constructor for existing callers. */
    public ModelMetadata(List<String> animationNames, List<String> texturePaths, int polygonCount) {
        this(animationNames, texturePaths, polygonCount, 0, 0, 0, "", false);
    }

    public ModelMetadata(List<String> animationNames, List<String> texturePaths,
                         int polygonCount, int vertexCount, int boneCount, int sequenceCount) {
        this(animationNames, texturePaths, polygonCount, vertexCount, boneCount, sequenceCount, "", false);
    }

    public ModelMetadata(List<String> animationNames, List<String> texturePaths,
                         int polygonCount, int vertexCount, int boneCount, int sequenceCount,
                         String modelName) {
        this(animationNames, texturePaths, polygonCount, vertexCount, boneCount, sequenceCount, modelName, false);
    }

    public boolean hasAnimationContaining(String needleLowerCase) {
        if (needleLowerCase == null || needleLowerCase.isEmpty()) {
            return true;
        }
        return animationNames.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains(needleLowerCase));
    }

    public boolean hasTextureContaining(String needleLowerCase) {
        if (needleLowerCase == null || needleLowerCase.isEmpty()) {
            return true;
        }
        return texturePaths.stream()
                .map(path -> path.toLowerCase(Locale.ROOT))
                .anyMatch(path -> path.contains(needleLowerCase));
    }

    public boolean hasKnownPolygonCount() {
        return polygonCount >= 0;
    }
}
