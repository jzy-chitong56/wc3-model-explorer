package com.hiveworkshop.model;

import java.nio.file.Path;
import java.util.List;

public record ModelAsset(Path path, long fileSizeBytes, ModelMetadata metadata, String parseError, List<String> tags) {
    public ModelAsset(Path path, long fileSizeBytes, ModelMetadata metadata) {
        this(path, fileSizeBytes, metadata, null, List.of());
    }

    public ModelAsset(Path path, long fileSizeBytes, ModelMetadata metadata, String parseError) {
        this(path, fileSizeBytes, metadata, parseError, List.of());
    }

    public ModelAsset {
        if (metadata == null) {
            metadata = ModelMetadata.EMPTY;
        }
        if (tags == null) {
            tags = List.of();
        }
    }

    public boolean hasParseError() {
        return parseError != null && !parseError.isEmpty();
    }

    public String fileName() {
        return path.getFileName().toString();
    }

    public String parentFolder() {
        Path parent = path.getParent();
        return parent != null ? parent.toString() : "";
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
