package org.example.model;

public record ReterasParsedModel(
        ModelMetadata       metadata,
        ModelMesh           mesh,
        ModelAnimData       animData,
        GeosetTexData[]     texData,   // one entry per mesh-included geoset
        CameraNode[]        cameras,
        CollisionShape[]    collisionShapes,
        MaterialInfo[]      materials,
        RibbonEmitterData[] ribbonEmitters
) {
    public static final ReterasParsedModel EMPTY =
            new ReterasParsedModel(ModelMetadata.EMPTY, ModelMesh.EMPTY, ModelAnimData.EMPTY,
                    new GeosetTexData[0], CameraNode.EMPTY_ARRAY, CollisionShape.EMPTY_ARRAY,
                    MaterialInfo.EMPTY_ARRAY, RibbonEmitterData.EMPTY_ARRAY);

    /** Backwards-compatible constructor without cameras/collisions/materials. */
    public ReterasParsedModel(ModelMetadata metadata, ModelMesh mesh,
                              ModelAnimData animData, GeosetTexData[] texData) {
        this(metadata, mesh, animData, texData, CameraNode.EMPTY_ARRAY, CollisionShape.EMPTY_ARRAY,
                MaterialInfo.EMPTY_ARRAY, RibbonEmitterData.EMPTY_ARRAY);
    }

    /** Backwards-compatible constructor without materials. */
    public ReterasParsedModel(ModelMetadata metadata, ModelMesh mesh,
                              ModelAnimData animData, GeosetTexData[] texData,
                              CameraNode[] cameras, CollisionShape[] collisionShapes) {
        this(metadata, mesh, animData, texData, cameras, collisionShapes, MaterialInfo.EMPTY_ARRAY,
                RibbonEmitterData.EMPTY_ARRAY);
    }

    /** Backwards-compatible constructor without ribbonEmitters. */
    public ReterasParsedModel(ModelMetadata metadata, ModelMesh mesh,
                              ModelAnimData animData, GeosetTexData[] texData,
                              CameraNode[] cameras, CollisionShape[] collisionShapes,
                              MaterialInfo[] materials) {
        this(metadata, mesh, animData, texData, cameras, collisionShapes, materials,
                RibbonEmitterData.EMPTY_ARRAY);
    }

    public ReterasParsedModel {
        if (metadata        == null) metadata        = ModelMetadata.EMPTY;
        if (mesh            == null) mesh            = ModelMesh.EMPTY;
        if (animData        == null) animData        = ModelAnimData.EMPTY;
        if (texData         == null) texData         = new GeosetTexData[0];
        if (cameras         == null) cameras         = CameraNode.EMPTY_ARRAY;
        if (collisionShapes == null) collisionShapes = CollisionShape.EMPTY_ARRAY;
        if (materials       == null) materials       = MaterialInfo.EMPTY_ARRAY;
        if (ribbonEmitters  == null) ribbonEmitters  = RibbonEmitterData.EMPTY_ARRAY;
    }
}
