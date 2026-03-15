package org.example.model;

import java.util.Locale;

public enum PortraitFilter {
    MODELS_ONLY("Models only"),
    PORTRAITS_ONLY("Portraits only"),
    BOTH("Both");

    private final String label;

    PortraitFilter(String label) {
        this.label = label;
    }

    public boolean allows(ModelAsset asset) {
        boolean portrait = isPortrait(asset);
        return switch (this) {
            case MODELS_ONLY -> !portrait;
            case PORTRAITS_ONLY -> portrait;
            case BOTH -> true;
        };
    }

    @Override
    public String toString() {
        return label;
    }

    private static boolean isPortrait(ModelAsset asset) {
        String name = asset.fileName().toLowerCase(Locale.ROOT);
        return name.endsWith("_portrait.mdx") || name.endsWith("_portrait.mdl");
    }
}
