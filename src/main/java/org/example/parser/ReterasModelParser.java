package org.example.parser;

import org.example.model.*;

import com.hiveworkshop.rms.parsers.mdlx.MdlLoadSave;
import com.hiveworkshop.rms.parsers.mdlx.MdlxBone;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGeoset;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGenericObject;
import com.hiveworkshop.rms.parsers.mdlx.MdlxHelper;
import com.hiveworkshop.rms.parsers.mdlx.MdlxAttachment;
import com.hiveworkshop.rms.parsers.mdlx.MdlxRibbonEmitter;
import com.hiveworkshop.rms.parsers.mdlx.MdlxGeosetAnimation;
import com.hiveworkshop.rms.parsers.mdlx.MdlxLayer;
import com.hiveworkshop.rms.parsers.mdlx.MdlxMaterial;
import com.hiveworkshop.rms.parsers.mdlx.MdlxCamera;
import com.hiveworkshop.rms.parsers.mdlx.MdlxCollisionShape;
import com.hiveworkshop.rms.parsers.mdlx.MdlxModel;
import com.hiveworkshop.rms.parsers.mdlx.MdlxSequence;
import com.hiveworkshop.rms.parsers.mdlx.MdlxTexture;
import com.hiveworkshop.rms.parsers.mdlx.MdlxTextureAnimation;
import com.hiveworkshop.rms.parsers.mdlx.MdxLoadSave;
import com.hiveworkshop.rms.parsers.mdlx.InterpolationType;
import com.hiveworkshop.rms.parsers.mdlx.timeline.MdlxTimeline;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ReterasModelParser {
    private static final long MAX_PARSE_BYTES = 96L * 1024L * 1024L;
    private static final Map<Path, ReterasParsedModel> CACHE = new ConcurrentHashMap<>();

    private ReterasModelParser() {}

    public static ReterasParsedModel parse(Path path) {
        if (path == null) return ReterasParsedModel.EMPTY;
        Path normalized = path.toAbsolutePath().normalize();
        return CACHE.computeIfAbsent(normalized, ReterasModelParser::parseInternal);
    }

    public static void invalidate(Path path) {
        if (path != null) CACHE.remove(path.toAbsolutePath().normalize());
    }

    public static void clearCache() {
        CACHE.clear();
    }

    // ── Internal parse ───────────────────────────────────────────────────────

    private static ReterasParsedModel parseInternal(Path path) {
        String ext = fileExtension(path);
        if (!"mdl".equals(ext) && !"mdx".equals(ext)) return ReterasParsedModel.EMPTY;
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_PARSE_BYTES) return ReterasParsedModel.EMPTY;
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 4) return ReterasParsedModel.EMPTY;

            MdlxModel model = new MdlxModel();
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            if (isMdx(bytes)) {
                MdxLoadSave.loadMdx(model, buffer);
            } else {
                MdlLoadSave.loadMdl(model, buffer);
            }
            ModelMesh mesh = buildMesh(model);
            return new ReterasParsedModel(buildMetadata(model), mesh, buildAnimData(model),
                    buildTexData(model), buildCameras(model), buildCollisionShapes(model),
                    buildMaterials(model), buildRibbonEmitters(model));
        } catch (IOException | RuntimeException ex) {
            return ReterasParsedModel.EMPTY;
        }
    }

    private static boolean isMdx(byte[] bytes) {
        return bytes[0] == 'M' && bytes[1] == 'D' && bytes[2] == 'L' && bytes[3] == 'X';
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    private static ModelMetadata buildMetadata(MdlxModel model) {
        List<String> animNames = new ArrayList<>();
        for (MdlxSequence s : model.sequences) {
            if (s != null && s.name != null) {
                String n = s.name.trim();
                if (!n.isEmpty()) animNames.add(n);
            }
        }

        List<String> texturePaths = new ArrayList<>();
        for (MdlxTexture t : model.textures) {
            if (t != null && t.path != null) {
                String p = t.path.trim();
                if (!p.isEmpty()) texturePaths.add(p);
            }
        }

        int polyCount = 0;
        int vertexCount = 0;
        boolean hasData = false;
        for (MdlxGeoset g : model.geosets) {
            if (g == null || g.faces == null) continue;
            hasData = true;
            polyCount += g.faces.length / 3;
            if (g.vertices != null) vertexCount += g.vertices.length / 3;
        }

        int boneCount = (model.bones != null ? model.bones.size() : 0)
                      + (model.helpers != null ? model.helpers.size() : 0);
        int sequenceCount = animNames.size();

        return new ModelMetadata(List.copyOf(animNames), List.copyOf(texturePaths),
                hasData ? polyCount : ModelMetadata.UNKNOWN_POLYGON_COUNT,
                vertexCount, boneCount, sequenceCount);
    }

    // ── Mesh ─────────────────────────────────────────────────────────────────

    private static ModelMesh buildMesh(MdlxModel model) {
        List<Float>   vertices = new ArrayList<>();
        List<Float>   normals  = new ArrayList<>();
        List<Integer> indices  = new ArrayList<>();

        for (MdlxGeoset g : model.geosets) {
            if (g == null || g.vertices == null || g.faces == null) continue;
            if (g.vertices.length < 3 || g.faces.length < 3) continue;

            int base = vertices.size() / 3;
            for (float v : g.vertices) vertices.add(v);

            // Extract vertex normals (same count as vertices)
            if (g.normals != null && g.normals.length == g.vertices.length) {
                for (float n : g.normals) normals.add(n);
            } else {
                // No normals — fill with zeros (shader will use dFdx/dFdy fallback)
                for (int i = 0; i < g.vertices.length; i++) normals.add(0f);
            }

            int nv = g.vertices.length / 3;
            for (int idx : g.faces) {
                if (idx >= 0 && idx < nv) indices.add(base + idx);
            }
        }

        if (vertices.isEmpty() || indices.isEmpty()) return ModelMesh.EMPTY;

        float[] va = new float[vertices.size()];
        for (int i = 0; i < va.length; i++) va[i] = vertices.get(i);

        float[] na = new float[normals.size()];
        for (int i = 0; i < na.length; i++) na[i] = normals.get(i);

        int[] ia = indices.stream().mapToInt(Integer::intValue).toArray();
        if (ia.length < 3) return ModelMesh.EMPTY;

        float minX=Float.POSITIVE_INFINITY, minY=Float.POSITIVE_INFINITY, minZ=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY, maxY=Float.NEGATIVE_INFINITY, maxZ=Float.NEGATIVE_INFINITY;
        for (int i = 0; i < va.length; i += 3) {
            minX=Math.min(minX,va[i]); maxX=Math.max(maxX,va[i]);
            minY=Math.min(minY,va[i+1]); maxY=Math.max(maxY,va[i+1]);
            minZ=Math.min(minZ,va[i+2]); maxZ=Math.max(maxZ,va[i+2]);
        }
        return new ModelMesh(va, na, ia, minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ── Texture / UV data ────────────────────────────────────────────────────

    /**
     * Builds one {@link GeosetTexData} per mesh-included geoset (same order as
     * {@link #buildMesh(MdlxModel)}).  Geosets that buildMesh() skips are omitted.
     */
    private static GeosetTexData[] buildTexData(MdlxModel model) {
        // Build texture-path lookup: index → path
        List<String> texturePaths = new ArrayList<>();
        for (MdlxTexture t : model.textures) {
            texturePaths.add(t != null && t.path != null ? t.path.trim() : "");
        }

        List<GeosetTexData> result = new ArrayList<>();
        for (MdlxGeoset g : model.geosets) {
            // Same guard as buildMesh()
            if (g == null || g.vertices == null || g.faces == null
                    || g.vertices.length < 3 || g.faces.length < 3) continue;

            // All UV sets
            float[][] uvSets;
            if (g.uvSets != null && g.uvSets.length > 0) {
                uvSets = new float[g.uvSets.length][];
                for (int u = 0; u < g.uvSets.length; u++) {
                    uvSets[u] = (g.uvSets[u] != null) ? g.uvSets[u].clone() : new float[0];
                }
            } else {
                uvSets = new float[][]{new float[0]};
            }

            // Build per-layer data from the material
            List<GeosetTexData.LayerTexData> layers = new ArrayList<>();
            String effectiveTexPath = "";
            int effectiveFilterMode = 0;
            int effectiveReplaceableId = 0;

            int matIdx = (int) g.materialId;
            if (matIdx >= 0 && matIdx < model.materials.size()) {
                MdlxMaterial mat = model.materials.get(matIdx);
                if (mat != null) {
                    boolean foundTc = false;
                    int tcReplId = 0;

                    for (MdlxLayer layer : mat.layers) {
                        if (layer == null) continue;
                        int texIdx = layer.textureId;
                        String lTexPath = "";
                        int lReplId = 0;
                        if (texIdx >= 0 && texIdx < model.textures.size()) {
                            MdlxTexture tex = model.textures.get(texIdx);
                            if (tex != null) {
                                lReplId = tex.replaceableId;
                                if (texIdx < texturePaths.size()) lTexPath = texturePaths.get(texIdx);
                            }
                        }
                        int lFilterMode = layer.filterMode != null ? layer.filterMode.ordinal() : 0;
                        int lCoordId = (int) layer.coordId;
                        layers.add(new GeosetTexData.LayerTexData(
                                lTexPath, lFilterMode, lReplId, layer.alpha, layer.flags, lCoordId));

                        if ((lReplId == 1 || lReplId == 2) && !foundTc) {
                            foundTc = true;
                            tcReplId = lReplId;
                        }
                    }

                    if (foundTc) effectiveReplaceableId = tcReplId;

                    // Effective layer: first non-TC layer, or first layer
                    GeosetTexData.LayerTexData effective = null;
                    for (GeosetTexData.LayerTexData l : layers) {
                        if (l.replaceableId() == 0) { effective = l; break; }
                    }
                    if (effective == null && !layers.isEmpty()) effective = layers.get(0);
                    if (effective != null) {
                        effectiveTexPath = effective.texturePath();
                        effectiveFilterMode = effective.filterMode();
                    }
                }
            }

            result.add(new GeosetTexData(uvSets, List.copyOf(layers),
                    effectiveTexPath, effectiveFilterMode, effectiveReplaceableId));
        }
        return result.toArray(new GeosetTexData[0]);
    }

    // ── Animation data ───────────────────────────────────────────────────────

    private static ModelAnimData buildAnimData(MdlxModel model) {
        // Sequences
        List<SequenceInfo> sequences = new ArrayList<>();
        for (MdlxSequence s : model.sequences) {
            if (s == null || s.name == null || s.interval == null || s.interval.length < 2) continue;
            String name = s.name.trim();
            if (!name.isEmpty()) {
                float[] minExt = null, maxExt = null;
                float boundsR = 0f;
                if (s.extent != null) {
                    minExt = s.extent.min;
                    maxExt = s.extent.max;
                    boundsR = s.extent.boundsRadius;
                }
                sequences.add(new SequenceInfo(name, s.interval[0], s.interval[1], s.flags,
                        minExt, maxExt, boundsR));
            }
        }

        // All nodes: bones + helpers + attachments
        List<MdlxGenericObject> allNodes = new ArrayList<>();
        Map<Integer, BoneNode.NodeType> nodeTypes = new HashMap<>();
        for (MdlxBone b : model.bones) { allNodes.add(b); nodeTypes.put(b.objectId, BoneNode.NodeType.BONE); }
        for (MdlxHelper h : model.helpers) { allNodes.add(h); nodeTypes.put(h.objectId, BoneNode.NodeType.HELPER); }
        for (MdlxAttachment a : model.attachments) { allNodes.add(a); nodeTypes.put(a.objectId, BoneNode.NodeType.ATTACHMENT); }
        for (MdlxRibbonEmitter r : model.ribbonEmitters) { allNodes.add(r); nodeTypes.put(r.objectId, BoneNode.NodeType.RIBBON_EMITTER); }
        allNodes.sort((a, b) -> Integer.compare(a.objectId, b.objectId));

        List<float[]> pivots = model.pivotPoints;

        BoneNode[] bones = new BoneNode[allNodes.size()];
        for (int i = 0; i < allNodes.size(); i++) {
            MdlxGenericObject node = allNodes.get(i);
            float[] pivot = (node.objectId < pivots.size()) ? pivots.get(node.objectId) : new float[3];

            AnimTrack trans = AnimTrack.EMPTY;
            AnimTrack rot   = AnimTrack.EMPTY;
            AnimTrack scale = AnimTrack.EMPTY;

            for (MdlxTimeline<?> tl : node.timelines) {
                if (tl == null || tl.name == null) continue;
                String tag = tl.name.asStringValue();
                AnimTrack extracted = extractTrack(tl);
                switch (tag) {
                    case "KGTR" -> trans = extracted;
                    case "KGRT" -> rot   = extracted;
                    case "KGSC" -> scale = extracted;
                }
            }

            String nodeName = node.name != null ? node.name : "";
            BoneNode.NodeType type = nodeTypes.getOrDefault(node.objectId, BoneNode.NodeType.BONE);
            bones[i] = new BoneNode(node.objectId, node.parentId, nodeName, type, node.flags, pivot, trans, rot, scale);
        }

        // Geoset skinning (SD only – skip geosets with HD skin data).
        // IMPORTANT: use the same skip conditions as buildMesh() so the geoset index in
        // animData always maps to the same vertex-offset range in ModelMesh.vertices().
        List<GeosetSkinData> geosets = new ArrayList<>();
        for (MdlxGeoset g : model.geosets) {
            // Same guard as buildMesh(): null, missing faces, or too-short arrays → EMPTY slot
            if (g == null || g.vertices == null || g.faces == null
                    || g.vertices.length < 3 || g.faces.length < 3) {
                geosets.add(GeosetSkinData.EMPTY);
                continue;
            }
            int geoMatId = (int) g.materialId;
            // HD models have the 'skin' short[] array; skip skinning for them (use bind pose)
            if (g.skin != null && g.skin.length > 0) {
                geosets.add(new GeosetSkinData(g.vertices.clone(), new int[0], new int[0][], geoMatId));
                continue;
            }

            int vertCount = g.vertices.length / 3;
            int groupCount = (g.matrixGroups != null) ? g.matrixGroups.length : 0;

            // Build per-group bone-id arrays
            int[][] groupBoneIds = new int[groupCount][];
            int matIdx = 0;
            for (int gi = 0; gi < groupCount; gi++) {
                int cnt = (int) g.matrixGroups[gi];
                groupBoneIds[gi] = new int[cnt];
                for (int k = 0; k < cnt; k++) {
                    int absIdx = matIdx + k;
                    groupBoneIds[gi][k] = (g.matrixIndices != null && absIdx < g.matrixIndices.length)
                            ? (int) g.matrixIndices[absIdx] : 0;
                }
                matIdx += cnt;
            }

            // Per-vertex group assignment
            int[] vertexGroup = new int[vertCount];
            if (g.vertexGroups != null) {
                for (int vi = 0; vi < Math.min(vertCount, g.vertexGroups.length); vi++) {
                    vertexGroup[vi] = g.vertexGroups[vi] & 0xFFFF; // unsigned short
                }
            }

            geosets.add(new GeosetSkinData(g.vertices.clone(), vertexGroup, groupBoneIds, geoMatId));
        }

        // Geoset animation alpha (KGAO)
        // geosetAnimations are linked to geosets by geosetId field.
        // We need to map from the MESH-included geoset index (skipping empty geosets)
        // to the KGAO track.
        Map<Integer, AnimTrack> geosetAlpha = new HashMap<>();
        Map<Integer, Float> geosetStaticAlpha = new HashMap<>();
        Map<Integer, AnimTrack> geosetColor = new HashMap<>();
        Map<Integer, float[]> geosetStaticColor = new HashMap<>();
        // Build mapping: original geoset list index → mesh-included index
        Map<Integer, Integer> origToMeshIdx = new HashMap<>();
        int meshIdx = 0;
        for (int oi = 0; oi < model.geosets.size(); oi++) {
            MdlxGeoset g = model.geosets.get(oi);
            if (g == null || g.vertices == null || g.faces == null
                    || g.vertices.length < 3 || g.faces.length < 3) continue;
            origToMeshIdx.put(oi, meshIdx++);
        }
        for (MdlxGeosetAnimation ga : model.geosetAnimations) {
            if (ga == null || ga.geosetId < 0) continue;
            Integer mi = origToMeshIdx.get(ga.geosetId);
            if (mi == null) continue;
            for (MdlxTimeline<?> tl : ga.timelines) {
                if (tl == null || tl.name == null) continue;
                String tag = tl.name.asStringValue();
                if ("KGAO".equals(tag)) {
                    AnimTrack track = extractTrack(tl);
                    if (!track.isEmpty()) geosetAlpha.put(mi, track);
                } else if ("KGAC".equals(tag)) {
                    AnimTrack track = extractTrack(tl);
                    if (!track.isEmpty()) geosetColor.put(mi, track);
                }
            }
            // Always store static alpha as fallback
            geosetStaticAlpha.put(mi, ga.alpha);
            // Store static color if present (flags bit 0x2 = color present)
            if (ga.color != null && ga.color.length >= 3) {
                geosetStaticColor.put(mi, new float[]{ga.color[0], ga.color[1], ga.color[2]});
            }
        }

        // Texture animations (KTAT/KTAR/KTAS) — indexed by textureAnimationId
        TextureAnimTracks[] texAnimArray = extractTextureAnims(model);

        // Map geoset → texture animation via material layer's textureAnimationId
        Map<Integer, TextureAnimTracks> textureAnims = new HashMap<>();
        meshIdx = 0;
        for (int oi = 0; oi < model.geosets.size(); oi++) {
            MdlxGeoset g = model.geosets.get(oi);
            if (g == null || g.vertices == null || g.faces == null
                    || g.vertices.length < 3 || g.faces.length < 3) continue;
            int mi = meshIdx++;
            int matId = (int) g.materialId;
            if (matId < 0 || matId >= model.materials.size()) continue;
            MdlxMaterial mat = model.materials.get(matId);
            if (mat == null) continue;
            // Use the first layer that references a texture animation
            for (MdlxLayer layer : mat.layers) {
                if (layer == null) continue;
                int taId = layer.textureAnimationId;
                if (taId >= 0 && taId < texAnimArray.length && texAnimArray[taId].hasAnimation()) {
                    textureAnims.put(mi, texAnimArray[taId]);
                    break;
                }
            }
        }

        // Global sequences (loop durations for global animations like texture anims)
        long[] globalSeqs = new long[0];
        if (model.globalSequences != null && !model.globalSequences.isEmpty()) {
            globalSeqs = new long[model.globalSequences.size()];
            for (int i = 0; i < globalSeqs.length; i++) {
                globalSeqs[i] = model.globalSequences.get(i);
            }
        }

        return new ModelAnimData(List.copyOf(sequences), bones, List.copyOf(geosets),
                Map.copyOf(geosetAlpha), Map.copyOf(geosetStaticAlpha),
                Map.copyOf(geosetColor), Map.copyOf(geosetStaticColor),
                Map.copyOf(textureAnims), globalSeqs);
    }

    /** Extract texture animation tracks from model.textureAnimations. */
    private static TextureAnimTracks[] extractTextureAnims(MdlxModel model) {
        if (model.textureAnimations == null || model.textureAnimations.isEmpty()) {
            return new TextureAnimTracks[0];
        }
        TextureAnimTracks[] result = new TextureAnimTracks[model.textureAnimations.size()];
        for (int i = 0; i < model.textureAnimations.size(); i++) {
            MdlxTextureAnimation ta = model.textureAnimations.get(i);
            if (ta == null) { result[i] = TextureAnimTracks.EMPTY; continue; }
            AnimTrack trans = AnimTrack.EMPTY;
            AnimTrack rot   = AnimTrack.EMPTY;
            AnimTrack scale = AnimTrack.EMPTY;
            for (MdlxTimeline<?> tl : ta.timelines) {
                if (tl == null || tl.name == null) continue;
                String tag = tl.name.asStringValue();
                AnimTrack extracted = extractTrack(tl);
                switch (tag) {
                    case "KTAT" -> trans = extracted;
                    case "KTAR" -> rot   = extracted;
                    case "KTAS" -> scale = extracted;
                }
            }
            result[i] = new TextureAnimTracks(trans, rot, scale);
        }
        return result;
    }

    /** Extract an AnimTrack from a generic MdlxTimeline<?> (handles float[] values). */
    @SuppressWarnings("unchecked")
    private static AnimTrack extractTrack(MdlxTimeline<?> tl) {
        if (tl.frames == null || tl.frames.length == 0) return AnimTrack.EMPTY;

        int n = tl.frames.length;
        float[][] values = toFloat2D((Object[]) tl.values, n);
        float[][] inTans = (tl.inTans  != null) ? toFloat2D((Object[]) tl.inTans,  n) : values;
        float[][] outTans = (tl.outTans != null) ? toFloat2D((Object[]) tl.outTans, n) : values;

        int interp = (tl.interpolationType != null) ? tl.interpolationType.ordinal() : 1;

        return new AnimTrack(tl.frames.clone(), values, inTans, outTans, interp, tl.globalSequenceId);
    }

    private static float[][] toFloat2D(Object[] raw, int n) {
        float[][] result = new float[n][];
        for (int i = 0; i < n; i++) {
            result[i] = (raw != null && i < raw.length && raw[i] instanceof float[])
                    ? (float[]) raw[i] : new float[]{0f, 0f, 0f};
        }
        return result;
    }

    // ── Cameras ──────────────────────────────────────────────────────────────

    private static CameraNode[] buildCameras(MdlxModel model) {
        if (model.cameras == null || model.cameras.isEmpty()) return CameraNode.EMPTY_ARRAY;
        List<CameraNode> result = new ArrayList<>();
        for (MdlxCamera cam : model.cameras) {
            if (cam == null) continue;
            result.add(new CameraNode(
                    cam.name != null ? cam.name : "",
                    cam.position != null ? cam.position.clone() : new float[3],
                    cam.targetPosition != null ? cam.targetPosition.clone() : new float[3],
                    cam.fieldOfView, cam.nearClippingPlane, cam.farClippingPlane));
        }
        return result.toArray(CameraNode[]::new);
    }

    // ── Collision shapes ────────────────────────────────────────────────────

    private static CollisionShape[] buildCollisionShapes(MdlxModel model) {
        if (model.collisionShapes == null || model.collisionShapes.isEmpty()) return CollisionShape.EMPTY_ARRAY;
        List<CollisionShape> result = new ArrayList<>();
        for (MdlxCollisionShape cs : model.collisionShapes) {
            if (cs == null) continue;
            CollisionShape.ShapeType type = switch (cs.type) {
                case BOX      -> CollisionShape.ShapeType.BOX;
                case PLANE    -> CollisionShape.ShapeType.PLANE;
                case SPHERE   -> CollisionShape.ShapeType.SPHERE;
                case CYLINDER -> CollisionShape.ShapeType.CYLINDER;
            };
            float[][] verts = new float[cs.vertices.length][];
            for (int i = 0; i < cs.vertices.length; i++) {
                verts[i] = cs.vertices[i] != null ? cs.vertices[i].clone() : new float[3];
            }
            result.add(new CollisionShape(type, verts, cs.boundsRadius));
        }
        return result.toArray(CollisionShape[]::new);
    }

    // ── Materials ────────────────────────────────────────────────────────────

    private static MaterialInfo[] buildMaterials(MdlxModel model) {
        if (model.materials == null || model.materials.isEmpty()) return MaterialInfo.EMPTY_ARRAY;

        // Build texture path lookup
        List<String> texPaths = new ArrayList<>();
        for (MdlxTexture t : model.textures) {
            texPaths.add(t != null && t.path != null ? t.path.trim() : "");
        }

        MaterialInfo[] result = new MaterialInfo[model.materials.size()];
        for (int mi = 0; mi < model.materials.size(); mi++) {
            MdlxMaterial mat = model.materials.get(mi);
            List<MaterialInfo.LayerInfo> layers = new ArrayList<>();
            if (mat != null && mat.layers != null) {
                for (MdlxLayer layer : mat.layers) {
                    if (layer == null) continue;
                    int texId = layer.textureId;
                    String texPath = (texId >= 0 && texId < texPaths.size()) ? texPaths.get(texId) : "";
                    int replId = 0;
                    if (texId >= 0 && texId < model.textures.size() && model.textures.get(texId) != null) {
                        replId = model.textures.get(texId).replaceableId;
                    }
                    int fm = layer.filterMode != null ? layer.filterMode.ordinal() : 0;
                    layers.add(new MaterialInfo.LayerInfo(
                            fm, layer.flags, texId, texPath, replId,
                            layer.alpha, layer.textureAnimationId, layer.coordId));
                }
            }
            result[mi] = new MaterialInfo(
                    mi,
                    mat != null ? mat.priorityPlane : 0,
                    mat != null ? mat.flags : 0,
                    mat != null && mat.shader != null ? mat.shader : "",
                    List.copyOf(layers));
        }
        return result;
    }

    // ── Ribbon emitters ────────────────────────────────────────────────────

    private static RibbonEmitterData[] buildRibbonEmitters(MdlxModel model) {
        if (model.ribbonEmitters == null || model.ribbonEmitters.isEmpty())
            return RibbonEmitterData.EMPTY_ARRAY;

        List<RibbonEmitterData> result = new ArrayList<>();
        for (MdlxRibbonEmitter re : model.ribbonEmitters) {
            if (re == null) continue;

            AnimTrack heightAboveTrack = AnimTrack.EMPTY;
            AnimTrack heightBelowTrack = AnimTrack.EMPTY;
            AnimTrack alphaTrack       = AnimTrack.EMPTY;
            AnimTrack colorTrack       = AnimTrack.EMPTY;
            AnimTrack visibilityTrack  = AnimTrack.EMPTY;
            AnimTrack texSlotTrack     = AnimTrack.EMPTY;

            for (MdlxTimeline<?> tl : re.timelines) {
                if (tl == null || tl.name == null) continue;
                String tag = tl.name.asStringValue();
                AnimTrack extracted = extractTrack(tl);
                switch (tag) {
                    case "KRHA" -> heightAboveTrack = extracted;
                    case "KRHB" -> heightBelowTrack = extracted;
                    case "KRAL" -> alphaTrack       = extracted;
                    case "KRCO" -> colorTrack       = extracted;
                    case "KRVS" -> visibilityTrack  = extracted;
                    case "KRTX" -> texSlotTrack     = extracted;
                    // KGTR/KGRT/KGSC handled in buildAnimData via allNodes
                }
            }

            result.add(new RibbonEmitterData(
                    re.objectId, re.materialId,
                    re.heightAbove, re.heightBelow,
                    re.alpha,
                    re.color != null ? re.color.clone() : new float[]{1f, 1f, 1f},
                    re.lifeSpan, re.gravity,
                    (int) re.emissionRate,
                    (int) re.rows, (int) re.columns,
                    (int) re.textureSlot,
                    heightAboveTrack, heightBelowTrack, alphaTrack,
                    colorTrack, visibilityTrack, texSlotTrack));
        }
        return result.toArray(new RibbonEmitterData[0]);
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static String fileExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return (dot < 0 || dot == name.length() - 1) ? "" : name.substring(dot + 1);
    }
}
