package org.example.model;

public enum ThumbnailSize {
    SMALL("Small (128)", 128),
    MEDIUM("Medium (192)", 192),
    LARGE("Large (252)", 252),
    EXTRA_LARGE("Extra Large (352)", 352);

    private final String label;
    private final int cardSize;

    ThumbnailSize(String label, int cardSize) {
        this.label = label;
        this.cardSize = cardSize;
    }

    public int cardSize() {
        return cardSize;
    }

    @Override
    public String toString() {
        return label;
    }
}
