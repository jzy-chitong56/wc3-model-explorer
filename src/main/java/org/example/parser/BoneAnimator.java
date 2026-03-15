package org.example.parser;

import org.example.model.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Computes world-space 4×4 matrices (column-major) for all bones at a given
 * animation time.
 *
 * Formula (from PLANS.md reference):
 *   boneLocal = T(pivot + trans) × R(quat) × S(scale) × T(−pivot)
 *   boneWorld = parentWorld × boneLocal
 */
public final class BoneAnimator {

    private BoneAnimator() {}

    /**
     * Returns a map from objectId → 4×4 column-major world matrix for every
     * bone in the supplied array.
     *
     * Billboard nodes cancel their parent's world rotation so that their
     * local rotation is applied in world space (matching RenderNode2.java):
     *   computedRotation = localRotation * inverse(parentWorldRotation)
     *
     * @param bones     all skeleton nodes (bones + helpers)
     * @param timeMs    current absolute animation time in milliseconds
     * @param seqStart  sequence interval start (ms)
     * @param seqEnd    sequence interval end   (ms)
     */
    public static Map<Integer, float[]> computeWorldMatrices(
            BoneNode[] bones, long timeMs, long seqStart, long seqEnd) {

        Map<Integer, float[]> worldMatrices = new HashMap<>(bones.length * 2);
        Map<Integer, float[]> worldRotations = new HashMap<>(bones.length * 2);
        Map<Integer, BoneNode> byId = new HashMap<>(bones.length * 2);
        for (BoneNode b : bones) byId.put(b.objectId(), b);

        for (BoneNode bone : bones) {
            computeWorldMatrix(bone, byId, worldMatrices, worldRotations, timeMs, seqStart, seqEnd);
        }
        return worldMatrices;
    }

    // ── Recursive (memoised) world matrix computation ────────────────────────

    private static float[] computeWorldMatrix(
            BoneNode bone,
            Map<Integer, BoneNode> byId,
            Map<Integer, float[]> matCache,
            Map<Integer, float[]> rotCache,
            long t, long s0, long s1) {

        if (matCache.containsKey(bone.objectId())) {
            return matCache.get(bone.objectId());
        }

        // Get parent world matrix and world rotation
        float[] parentWorldMatrix = IDENTITY;
        float[] parentWorldRot = IDENTITY_QUAT;
        if (bone.parentId() >= 0) {
            BoneNode parent = byId.get(bone.parentId());
            if (parent != null) {
                computeWorldMatrix(parent, byId, matCache, rotCache, t, s0, s1);
                parentWorldMatrix = matCache.getOrDefault(parent.objectId(), IDENTITY);
                parentWorldRot = rotCache.getOrDefault(parent.objectId(), IDENTITY_QUAT);
            }
        }

        // Interpolate local transform components
        float[] p = bone.pivot();
        float px = p != null && p.length > 0 ? p[0] : 0f;
        float py = p != null && p.length > 1 ? p[1] : 0f;
        float pz = p != null && p.length > 2 ? p[2] : 0f;

        float[] tr = interpVec3(bone.trans(), t, s0, s1, 0f, 0f, 0f);
        float[] localRot = interpQuat(bone.rot(), t, s0, s1);
        float[] sc = interpVec3(bone.scale(), t, s0, s1, 1f, 1f, 1f);

        // Billboard: cancel parent world rotation so local rotation acts in world space
        float[] computedRot;
        if (bone.isBillboarded()) {
            computedRot = quatMul(localRot, quatInverse(parentWorldRot));
        } else {
            computedRot = localRot;
        }

        // boneLocal = T(pivot+trans) × R(computedRot) × S × T(-pivot)
        float[] localMatrix = makeTranslate(-px, -py, -pz);
        localMatrix = matMul(makeScale(sc[0], sc[1], sc[2]), localMatrix);
        localMatrix = matMul(quatToMatrix(computedRot[0], computedRot[1], computedRot[2], computedRot[3]), localMatrix);
        localMatrix = matMul(makeTranslate(tr[0] + px, tr[1] + py, tr[2] + pz), localMatrix);

        // worldMatrix = parentWorldMatrix × localMatrix
        float[] worldMatrix = matMul(parentWorldMatrix, localMatrix);
        matCache.put(bone.objectId(), worldMatrix);

        // Track world rotation for children
        // Billboard nodes: worldRotation stays as localRotation (parent cancelled out)
        // Normal nodes: worldRotation = parentWorldRotation * localRotation
        float[] worldRot;
        if (bone.isBillboarded()) {
            worldRot = localRot;
        } else {
            worldRot = quatMul(parentWorldRot, localRot);
        }
        rotCache.put(bone.objectId(), worldRot);

        return worldMatrix;
    }

    /** Hamilton product of two quaternions [x,y,z,w]. */
    private static float[] quatMul(float[] a, float[] b) {
        return new float[]{
            a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
            a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
            a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
            a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
        };
    }

    /** Returns the inverse (conjugate) of a unit quaternion [x,y,z,w]. */
    private static float[] quatInverse(float[] q) {
        return new float[]{-q[0], -q[1], -q[2], q[3]};
    }

    // ── Track interpolation ──────────────────────────────────────────────────

    /**
     * Sample a scalar track (e.g. KGAO alpha) at time t.
     * Falls back to {@code def} when the track is empty.
     */
    public static float interpScalar(AnimTrack tk, long t, long s0, long s1, float def) {
        if (tk == null || tk.isEmpty()) return def;

        long[] frames = tk.frames();
        int n = frames.length;
        if (n == 0) return def;

        // Only consider keyframes within the sequence range [s0, s1]
        int firstInSeq = -1, lastInSeq = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] >= s0 && frames[i] <= s1) {
                if (firstInSeq < 0) firstInSeq = i;
                lastInSeq = i;
            }
        }
        if (firstInSeq < 0) return def; // no keys in this sequence

        // Bracket search restricted to [s0, s1]
        int lo = -1, hi = -1;
        for (int i = firstInSeq; i <= lastInSeq; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] >= t && hi < 0) hi = i;
        }

        if (lo < 0) return scalar(tk.values()[firstInSeq]);
        if (hi < 0) {
            // Past last in-sequence keyframe — wrap to first keyframe at s1
            if (firstInSeq != lastInSeq && s1 > frames[lastInSeq]) {
                long t0 = frames[lastInSeq], t1 = s1;
                float alpha = Math.min((float)(t - t0) / (float)(t1 - t0), 1f);
                return lerpScalar(scalar(tk.values()[lastInSeq]), scalar(tk.values()[firstInSeq]), alpha);
            }
            return scalar(tk.values()[lo]);
        }
        if (lo == hi) return scalar(tk.values()[lo]);

        long t0 = frames[lo], t1 = frames[hi];
        if (t1 == t0) return scalar(tk.values()[lo]);

        float alpha = (float)(t - t0) / (float)(t1 - t0);
        float v0 = scalar(tk.values()[lo]);
        float v1 = scalar(tk.values()[hi]);

        return switch (tk.interp()) {
            case 0 -> v0; // DONT_INTERP
            default -> lerpScalar(v0, v1, alpha);
        };
    }

    private static float scalar(float[] v) {
        return (v != null && v.length > 0) ? v[0] : 0f;
    }

    private static float lerpScalar(float a, float b, float t) {
        return a + t * (b - a);
    }

    /**
     * Sample a Vec3 track at time t, returning [dx,dy,dz].
     * Falls back to (defX, defY, defZ) when the track is empty.
     */
    public static float[] interpVec3(AnimTrack tk, long t, long s0, long s1,
                                      float defX, float defY, float defZ) {
        if (tk == null || tk.isEmpty()) return new float[]{defX, defY, defZ};

        long[] frames = tk.frames();
        int n = frames.length;
        if (n == 0) return new float[]{defX, defY, defZ};

        // Only consider keyframes within the sequence range [s0, s1]
        int firstInSeq = -1, lastInSeq = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] >= s0 && frames[i] <= s1) {
                if (firstInSeq < 0) firstInSeq = i;
                lastInSeq = i;
            }
        }
        if (firstInSeq < 0) return new float[]{defX, defY, defZ}; // no keys in this sequence

        // Bracket search restricted to [s0, s1]
        int lo = -1, hi = -1;
        for (int i = firstInSeq; i <= lastInSeq; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] >= t && hi < 0) hi = i;
        }

        if (lo < 0) return copy3(tk.values()[firstInSeq]);
        if (hi < 0) {
            // Past last in-sequence keyframe — wrap to first keyframe at s1
            if (firstInSeq != lastInSeq && s1 > frames[lastInSeq]) {
                long t0 = frames[lastInSeq], t1 = s1;
                float alpha = Math.min((float)(t - t0) / (float)(t1 - t0), 1f);
                return lerpVec3(tk.values()[lastInSeq], tk.values()[firstInSeq], alpha);
            }
            return copy3(tk.values()[lo]);
        }
        if (lo == hi) return copy3(tk.values()[lo]); // exact hit

        long t0 = frames[lo], t1 = frames[hi];
        if (t1 == t0) return copy3(tk.values()[lo]);

        float alpha = (float)(t - t0) / (float)(t1 - t0);
        float[] v0 = tk.values()[lo];
        float[] v1 = tk.values()[hi];

        return switch (tk.interp()) {
            case 0 -> copy3(v0);            // DONT_INTERP
            case 2 -> hermiteVec3(v0, tk.outTans()[lo], tk.inTans()[hi], v1, alpha);
            case 3 -> bezierVec3(v0, tk.outTans()[lo], tk.inTans()[hi], v1, alpha);
            default -> lerpVec3(v0, v1, alpha);  // LINEAR (1)
        };
    }

    /**
     * Sample a quaternion track at time t, returning [x,y,z,w].
     * Uses SLERP for all interpolation types (good enough for display).
     */
    static float[] interpQuat(AnimTrack tk, long t, long s0, long s1) {
        if (tk == null || tk.isEmpty()) return new float[]{0f, 0f, 0f, 1f};

        long[] frames = tk.frames();
        int n = frames.length;
        if (n == 0) return new float[]{0f, 0f, 0f, 1f};

        // Only consider keyframes within the sequence range [s0, s1]
        int firstInSeq = -1, lastInSeq = -1;
        for (int i = 0; i < n; i++) {
            if (frames[i] >= s0 && frames[i] <= s1) {
                if (firstInSeq < 0) firstInSeq = i;
                lastInSeq = i;
            }
        }
        if (firstInSeq < 0) return new float[]{0f, 0f, 0f, 1f}; // no keys in this sequence

        // Bracket search restricted to [s0, s1]
        int lo = -1, hi = -1;
        for (int i = firstInSeq; i <= lastInSeq; i++) {
            if (frames[i] <= t) lo = i;
            if (frames[i] >= t && hi < 0) hi = i;
        }

        if (lo < 0) return copy4(tk.values()[firstInSeq]);
        if (hi < 0) {
            // Past last in-sequence keyframe — wrap to first keyframe at s1
            if (firstInSeq != lastInSeq && s1 > frames[lastInSeq] && tk.interp() != 0) {
                long t0 = frames[lastInSeq], t1 = s1;
                float alpha = Math.min((float)(t - t0) / (float)(t1 - t0), 1f);
                return slerp(tk.values()[lastInSeq], tk.values()[firstInSeq], alpha);
            }
            return copy4(tk.values()[lo]);
        }
        if (lo == hi) return copy4(tk.values()[lo]);

        long t0 = frames[lo], t1 = frames[hi];
        if (t1 == t0) return copy4(tk.values()[lo]);

        if (tk.interp() == 0) return copy4(tk.values()[lo]); // DONT_INTERP

        float alpha = (float)(t - t0) / (float)(t1 - t0);
        return slerp(tk.values()[lo], tk.values()[hi], alpha);
    }

    // ── Vec3 interpolation methods ───────────────────────────────────────────

    private static float[] lerpVec3(float[] a, float[] b, float t) {
        return new float[]{
            a[0] + t * (b[0] - a[0]),
            a[1] + t * (b[1] - a[1]),
            a[2] + t * (b[2] - a[2])
        };
    }

    private static float[] hermiteVec3(float[] p0, float[] out0, float[] in1, float[] p1, float t) {
        float t2 = t * t, t3 = t2 * t;
        float h0 = 2*t3 - 3*t2 + 1;
        float h1 = t3 - 2*t2 + t;
        float h2 = -2*t3 + 3*t2;
        float h3 = t3 - t2;
        return new float[]{
            h0*p0[0] + h1*out0[0] + h2*p1[0] + h3*in1[0],
            h0*p0[1] + h1*out0[1] + h2*p1[1] + h3*in1[1],
            h0*p0[2] + h1*out0[2] + h2*p1[2] + h3*in1[2]
        };
    }

    private static float[] bezierVec3(float[] p0, float[] c0, float[] c1, float[] p1, float t) {
        float mt = 1f - t, mt2 = mt*mt, mt3 = mt2*mt, t2 = t*t, t3 = t2*t;
        return new float[]{
            mt3*p0[0] + 3*mt2*t*c0[0] + 3*mt*t2*c1[0] + t3*p1[0],
            mt3*p0[1] + 3*mt2*t*c0[1] + 3*mt*t2*c1[1] + t3*p1[1],
            mt3*p0[2] + 3*mt2*t*c0[2] + 3*mt*t2*c1[2] + t3*p1[2]
        };
    }

    // ── Quaternion SLERP ─────────────────────────────────────────────────────

    static float[] slerp(float[] q1, float[] q2, float t) {
        float x1 = q1[0], y1 = q1[1], z1 = q1[2], w1 = q1[3];
        float x2 = q2[0], y2 = q2[1], z2 = q2[2], w2 = q2[3];

        float dot = x1*x2 + y1*y2 + z1*z2 + w1*w2;
        if (dot < 0f) { x2 = -x2; y2 = -y2; z2 = -z2; w2 = -w2; dot = -dot; }

        if (dot > 0.9995f) {
            // Linear fallback for nearly-identical quaternions
            float[] r = { x1 + t*(x2-x1), y1 + t*(y2-y1), z1 + t*(z2-z1), w1 + t*(w2-w1) };
            return normalizeQuat(r);
        }

        float theta0 = (float) Math.acos(dot);
        float theta  = theta0 * t;
        float sinT0  = (float) Math.sin(theta0);
        float sinT   = (float) Math.sin(theta);

        float s1 = (float) Math.cos(theta) - dot * sinT / sinT0;
        float s2 = sinT / sinT0;

        return new float[]{
            s1*x1 + s2*x2,
            s1*y1 + s2*y2,
            s1*z1 + s2*z2,
            s1*w1 + s2*w2
        };
    }

    private static float[] normalizeQuat(float[] q) {
        float len = (float) Math.sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
        if (len < 1e-10f) return new float[]{0f, 0f, 0f, 1f};
        return new float[]{q[0]/len, q[1]/len, q[2]/len, q[3]/len};
    }

    // ── Matrix utilities (column-major 4×4) ──────────────────────────────────

    private static final float[] IDENTITY = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    private static final float[] IDENTITY_QUAT = {0f, 0f, 0f, 1f};

    static float[] identity() {
        return new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    private static float[] makeTranslate(float tx, float ty, float tz) {
        float[] m = identity();
        m[12] = tx; m[13] = ty; m[14] = tz;
        return m;
    }

    private static float[] makeScale(float sx, float sy, float sz) {
        float[] m = identity();
        m[0] = sx; m[5] = sy; m[10] = sz;
        return m;
    }

    /** Build a rotation matrix from quaternion (x, y, z, w). Column-major. */
    static float[] quatToMatrix(float x, float y, float z, float w) {
        float xx = x*x, yy = y*y, zz = z*z;
        float xy = x*y, xz = x*z, yz = y*z;
        float wx = w*x, wy = w*y, wz = w*z;
        return new float[]{
            1-2*(yy+zz), 2*(xy+wz),   2*(xz-wy),  0,   // col 0
            2*(xy-wz),   1-2*(xx+zz), 2*(yz+wx),   0,   // col 1
            2*(xz+wy),   2*(yz-wx),   1-2*(xx+yy), 0,   // col 2
            0,           0,           0,            1    // col 3
        };
    }

    /** Column-major 4×4 matrix multiply: returns a × b. */
    static float[] matMul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[row + k*4] * b[k + col*4];
                r[row + col*4] = s;
            }
        }
        return r;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static float[] copy3(float[] v) {
        if (v == null || v.length < 3) return new float[3];
        return new float[]{v[0], v[1], v[2]};
    }

    private static float[] copy4(float[] v) {
        if (v == null || v.length < 4) return new float[]{0f, 0f, 0f, 1f};
        return new float[]{v[0], v[1], v[2], v[3]};
    }
}
