package org.example.model;

public final class TeamColorOptions {
    public static final int COUNT = 25;

    private static final String[] NAMES = {
            "Red", "Blue", "Teal", "Purple", "Yellow", "Orange",
            "Green", "Pink", "Gray", "Light Blue", "Dark Green", "Brown",
            "Maroon", "Navy", "Turquoise", "Violet", "Wheat", "Peach",
            "Mint", "Lavender", "Coal", "Snow", "Emerald", "Peanut",
            "Black"
    };
    private static final int[][] FALLBACK_RGB = {
            {255, 4, 2}, {0, 66, 255}, {27, 230, 186}, {84, 0, 129},
            {255, 252, 0}, {255, 138, 13}, {32, 192, 0}, {228, 91, 176},
            {148, 150, 151}, {126, 191, 241}, {16, 98, 71}, {79, 42, 5},
            {156, 0, 0}, {0, 0, 195}, {0, 235, 255}, {189, 0, 255},
            {236, 205, 134}, {247, 164, 139}, {192, 255, 128}, {220, 185, 236},
            {79, 79, 85}, {236, 240, 255}, {0, 120, 30}, {164, 111, 51},
            {46, 45, 46}
    };

    private static final String[] LABELS = buildLabels();

    private TeamColorOptions() {}

    public static int clampIndex(int idx) {
        return Math.max(0, Math.min(COUNT - 1, idx));
    }

    public static String[] labels() {
        return LABELS.clone();
    }

    public static int[] fallbackRgb(int idx) {
        return FALLBACK_RGB[clampIndex(idx)].clone();
    }

    private static String[] buildLabels() {
        String[] labels = new String[COUNT];
        for (int i = 0; i < COUNT; i++) {
            labels[i] = NAMES[i];
        }
        return labels;
    }
}
