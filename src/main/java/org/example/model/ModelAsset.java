package org.example.model;

import java.nio.file.Path;

public record ModelAsset(Path path, long fileSizeBytes, ModelMetadata metadata) {
    public ModelAsset {
        if (metadata == null) {
            metadata = ModelMetadata.EMPTY;
        }
    }

    public String fileName() {
        return path.getFileName().toString();
    }

    public String fileExtension() {
        String name = fileName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }
}
