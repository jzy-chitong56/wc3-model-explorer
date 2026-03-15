package org.example.parser;

import org.example.model.*;

import java.nio.file.Path;

public final class ModelMetadataExtractor {
    private ModelMetadataExtractor() {
    }

    public static ModelMetadata extract(Path path) {
        if (path == null) {
            return ModelMetadata.EMPTY;
        }
        return ReterasModelParser.parse(path).metadata();
    }
}
