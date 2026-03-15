package org.example.model;

/** Sampled keyframe track (translation, rotation, or scale). */
public record AnimTrack(
        long[]    frames,
        float[][] values,    // parallel to frames: [n][3] for vec3, [n][4] for quat
        float[][] inTans,
        float[][] outTans,
        int       interp     // 0=DONT_INTERP, 1=LINEAR, 2=HERMITE, 3=BEZIER
) {
    public static final AnimTrack EMPTY =
            new AnimTrack(new long[0], new float[0][], new float[0][], new float[0][], 1);

    public boolean isEmpty() {
        return frames == null || frames.length == 0;
    }
}
