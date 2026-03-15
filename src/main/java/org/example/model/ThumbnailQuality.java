package org.example.model;

/** Thumbnail rendering quality presets. */
public enum ThumbnailQuality {
    LOW("Low (256x256)", 256, 256),
    MEDIUM("Medium (512→256)", 512, 256),
    HIGH("High (1024→256)", 1024, 256);

    private final String label;
    private final int renderSize;
    private final int thumbSize;

    ThumbnailQuality(String label, int renderSize, int thumbSize) {
        this.label = label;
        this.renderSize = renderSize;
        this.thumbSize = thumbSize;
    }

    public int renderSize() { return renderSize; }
    public int thumbSize() { return thumbSize; }

    @Override
    public String toString() { return label; }
}
