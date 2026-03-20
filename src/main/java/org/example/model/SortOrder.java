package org.example.model;

import java.util.Comparator;

public enum SortOrder {
    NAME_ASC("Name (A→Z)"),
    NAME_DESC("Name (Z→A)"),
    SIZE_ASC("Size (smallest)"),
    SIZE_DESC("Size (largest)");

    private final String label;

    SortOrder(String label) {
        this.label = label;
    }

    public Comparator<ModelAsset> comparator() {
        return switch (this) {
            case NAME_ASC -> Comparator.comparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER);
            case NAME_DESC -> Comparator.comparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER).reversed();
            case SIZE_ASC -> Comparator.comparingLong(ModelAsset::fileSizeBytes);
            case SIZE_DESC -> Comparator.comparingLong(ModelAsset::fileSizeBytes).reversed();
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
