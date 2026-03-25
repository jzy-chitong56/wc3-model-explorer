package org.example.model;

import org.example.i18n.Messages;

import java.util.Comparator;

public enum SortOrder {
    NAME_ASC("sort.nameAsc"),
    NAME_DESC("sort.nameDesc"),
    SIZE_ASC("sort.sizeAsc"),
    SIZE_DESC("sort.sizeDesc"),
    FOLDER_ASC("sort.folderAsc"),
    FOLDER_DESC("sort.folderDesc");

    private final String key;

    SortOrder(String key) {
        this.key = key;
    }

    public Comparator<ModelAsset> comparator() {
        return switch (this) {
            case NAME_ASC -> Comparator.comparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER);
            case NAME_DESC -> Comparator.comparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER).reversed();
            case SIZE_ASC -> Comparator.comparingLong(ModelAsset::fileSizeBytes);
            case SIZE_DESC -> Comparator.comparingLong(ModelAsset::fileSizeBytes).reversed();
            case FOLDER_ASC -> Comparator.comparing(ModelAsset::parentFolder, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER);
            case FOLDER_DESC -> Comparator.comparing(ModelAsset::parentFolder, String.CASE_INSENSITIVE_ORDER).reversed()
                    .thenComparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER);
        };
    }

    @Override
    public String toString() {
        return Messages.get(key);
    }
}
