package org.example.parser;

import org.example.model.*;

public final class ModelMeshExtractor {
    private ModelMeshExtractor() {
    }

    public static ModelMesh extract(ModelAsset asset) {
        if (asset == null) {
            return ModelMesh.EMPTY;
        }
        return ReterasModelParser.parse(asset.path()).mesh();
    }
}
