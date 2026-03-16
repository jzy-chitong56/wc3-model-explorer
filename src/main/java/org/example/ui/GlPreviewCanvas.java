package org.example.ui;

import org.example.model.*;
import org.example.parser.*;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public final class GlPreviewCanvas extends AWTGLCanvas {
    private static final float MIN_DISTANCE = 30.0f;
    private static final float MAX_DISTANCE = 3000.0f;

    public enum ShadingMode { SOLID, TEXTURED, LIT, NORMALS }

    private final ModelMesh         mesh;
    private final ModelAnimData     animData;
    private final GeosetTexData[]   texData;
    private final CollisionShape[]  collisionShapes;
    private final Path              modelDir;
    private final Path              rootDir;
    private volatile ShadingMode  shadingMode = ShadingMode.TEXTURED;

    private float   modelScale   = 1.0f;
    private float   initialYaw   = 200.0f;
    private float   initialPitch = 20.0f;
    private float   yawDegrees   = 200.0f;
    private float   pitchDegrees = 20.0f;
    private float   distance     = 300.0f;
    private float   panX         = 0.0f;
    private float   panY         = -30.0f;
    private boolean wireframe;
    private volatile boolean showExtent;
    private volatile boolean showBones;
    private volatile boolean showHelpers;
    private volatile boolean showAttachments;
    private volatile boolean showNodeNames;
    private volatile boolean showGrid = true;
    private volatile boolean showCollision;
    private volatile float   nodeSize = 3.0f;
    private volatile float bgR = 0.06f, bgG = 0.08f, bgB = 0.11f;
    private int     lastMouseX, lastMouseY;
    private boolean draggingOrbit, draggingPan;

    // Camera View state
    private volatile boolean       usingCameraView = false;
    private volatile CameraNode    cameraViewNode;
    private float   savedYaw, savedPitch, savedDistance, savedPanX, savedPanY;

    // Render loop – latch signals that the loop has fully exited
    private volatile boolean   renderRunning = false;
    private Thread             renderThread;
    private CountDownLatch     renderStopped;

    // GL resources (all initialised in initGL on the render thread)
    private int solidShader = 0, solidMvp = -1, solidColor = -1;
    private int texShader   = 0, texMvp   = -1, texSampler = -1, texHasTex = -1, texAlphaThresh = -1, texAlphaU = -1, texUVTransform = -1;
    private int litShader   = 0, litMvp   = -1, litSampler = -1, litHasTex = -1, litAlphaThresh = -1, litAlphaU = -1, litUVTransform = -1;
    private int normalsShader = 0, normalsMvp = -1;

    private int gridVao = 0, gridVbo = 0, gridVertexCount = 0;

    // Combined VAO for Solid mode
    private int meshVao = 0, meshVbo = 0, meshEbo = 0, meshIndexCount = 0;

    // Per-geoset arrays for Textured mode (one entry per mesh-included geoset)
    private int[] geoVao, geoVbo, geoUvVbo, geoEbo, geoIndexCount, geoTex, geoVertCount;

    private int cubeVao = 0, cubeVbo = 0, cubeEbo = 0;
    private int nodeCubeVao = 0, nodeCubeVbo = 0, nodeCubeEbo = 0;
    private int extentVao = 0, extentVbo = 0, extentLineCount = 0;
    private int collisionVao = 0, collisionVbo = 0, collisionLineCount = 0;
    private int boneVao = 0, boneVbo = 0;

    // Animation (volatile: written from EDT, read from render thread)
    private volatile int     currentSeqIdx = -1;
    private volatile boolean animPlaying   = false;
    private volatile float   animSpeed     = 1.0f;
    private volatile long    animTimeMs    = 0L;
    private volatile long    lastNanoNs    = 0L;
    private volatile boolean animLooping  = true;
    private volatile int     teamColorIdx = 0;
    private volatile boolean tcDirty      = false;
    private float[]          animatedVertices;
    private float[]          geosetAlphaValues; // per-geoset alpha (sampled from KGAO)
    private float[][]        geosetUVTransforms; // per-geoset 4x4 UV transform matrices (null = identity)
    private volatile Map<Integer, float[]> lastWorldMap; // cached for node names overlay
    // Node names GL overlay (rendered as textured fullscreen quad)
    private int nodeNamesQuadVao, nodeNamesQuadVbo, nodeNamesTex;
    private int nodeNamesTexW, nodeNamesTexH;
    // Cached node positions after updateBoneVbo, for cube drawing
    private float[][] bonePositions, helperPositions, attachPositions;

    // ── Shaders ──────────────────────────────────────────────────────────────

    static final String SOLID_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "uniform mat4 mvp;\n" +
        "void main(){ gl_Position = mvp * vec4(aPos,1.0); }\n";

    static final String SOLID_FRAG =
        "#version 330 core\n" +
        "uniform vec3 uColor;\n" +
        "out vec4 fragColor;\n" +
        "void main(){ fragColor = vec4(uColor,1.0); }\n";

    static final String TEX_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec2 aUV;\n" +
        "uniform mat4 mvp;\n" +
        "uniform mat4 uUVTransform;\n" +
        "out vec2 vUV;\n" +
        // WC3 UV: V=0 is top; OpenGL: V=0 is bottom → flip V
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vec2 flipped = vec2(aUV.x, 1.0-aUV.y);\n" +
        "  vUV = (uUVTransform * vec4(flipped, 0.0, 1.0)).xy;\n" +
        "}\n";

    static final String TEX_FRAG =
        "#version 330 core\n" +
        "in vec2 vUV;\n" +
        "uniform sampler2D uTex;\n" +
        "uniform bool uHasTex;\n" +
        "uniform float uAlphaThreshold;\n" +
        "uniform float uAlpha;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec4 c = uHasTex ? texture(uTex, vUV) : vec4(0.74,0.78,0.86,1.0);\n" +
        "  c.a *= uAlpha;\n" +
        "  if(c.a < uAlphaThreshold) discard;\n" +
        "  fragColor = c;\n" +
        "}\n";

    // Lit shader: textured + simple directional light using flat normals from dFdx/dFdy
    static final String LIT_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "layout(location=1) in vec2 aUV;\n" +
        "uniform mat4 mvp;\n" +
        "uniform mat4 uUVTransform;\n" +
        "out vec2 vUV;\n" +
        "out vec3 vWorldPos;\n" +
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vec2 flipped = vec2(aUV.x, 1.0-aUV.y);\n" +
        "  vUV = (uUVTransform * vec4(flipped, 0.0, 1.0)).xy;\n" +
        "  vWorldPos = aPos;\n" +
        "}\n";

    static final String LIT_FRAG =
        "#version 330 core\n" +
        "in vec2 vUV;\n" +
        "in vec3 vWorldPos;\n" +
        "uniform sampler2D uTex;\n" +
        "uniform bool uHasTex;\n" +
        "uniform float uAlphaThreshold;\n" +
        "uniform float uAlpha;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec3 N = normalize(cross(dFdx(vWorldPos), dFdy(vWorldPos)));\n" +
        "  vec3 L = normalize(vec3(0.3, 0.8, 0.5));\n" +  // front-right-top light
        "  float diff = max(dot(N, L), 0.0);\n" +
        "  float ambient = 0.35;\n" +
        "  float light = ambient + (1.0 - ambient) * diff;\n" +
        "  vec4 base = uHasTex ? texture(uTex, vUV) : vec4(0.74,0.78,0.86,1.0);\n" +
        "  base.a *= uAlpha;\n" +
        "  if(base.a < uAlphaThreshold) discard;\n" +
        "  fragColor = vec4(base.rgb * light, base.a);\n" +
        "}\n";

    // Normals shader: visualises face normals as RGB colour (N*0.5+0.5)
    static final String NORMALS_VERT =
        "#version 330 core\n" +
        "layout(location=0) in vec3 aPos;\n" +
        "uniform mat4 mvp;\n" +
        "out vec3 vWorldPos;\n" +
        "void main(){\n" +
        "  gl_Position = mvp * vec4(aPos,1.0);\n" +
        "  vWorldPos = aPos;\n" +
        "}\n";

    static final String NORMALS_FRAG =
        "#version 330 core\n" +
        "in vec3 vWorldPos;\n" +
        "out vec4 fragColor;\n" +
        "void main(){\n" +
        "  vec3 N = normalize(cross(dFdx(vWorldPos), dFdy(vWorldPos)));\n" +
        "  fragColor = vec4(N * 0.5 + 0.5, 1.0);\n" +
        "}\n";

    // ── Construction ─────────────────────────────────────────────────────────

    public GlPreviewCanvas(ModelAsset asset) {
        this(ReterasModelParser.parse(asset == null ? null : asset.path()));
    }

    public GlPreviewCanvas(ReterasParsedModel parsed) {
        this(parsed == null ? ModelMesh.EMPTY      : parsed.mesh(),
             parsed == null ? ModelAnimData.EMPTY  : parsed.animData(),
             parsed == null ? new GeosetTexData[0] : parsed.texData(),
             parsed == null ? CollisionShape.EMPTY_ARRAY : parsed.collisionShapes(),
             null, null);
    }

    public GlPreviewCanvas(ModelMesh mesh, ModelAnimData animData,
                           GeosetTexData[] texData, Path modelDir) {
        this(mesh, animData, texData, CollisionShape.EMPTY_ARRAY, modelDir, null);
    }

    public GlPreviewCanvas(ModelMesh mesh, ModelAnimData animData,
                           GeosetTexData[] texData, Path modelDir, Path rootDir) {
        this(mesh, animData, texData, CollisionShape.EMPTY_ARRAY, modelDir, rootDir);
    }

    public GlPreviewCanvas(ModelMesh mesh, ModelAnimData animData,
                           GeosetTexData[] texData, CollisionShape[] collisionShapes,
                           Path modelDir, Path rootDir) {
        super(createGlData());
        this.mesh            = mesh            != null ? mesh            : ModelMesh.EMPTY;
        this.animData        = animData        != null ? animData        : ModelAnimData.EMPTY;
        this.texData         = texData         != null ? texData         : new GeosetTexData[0];
        this.collisionShapes = collisionShapes != null ? collisionShapes : CollisionShape.EMPTY_ARRAY;
        this.modelDir        = modelDir;
        this.rootDir         = rootDir;
        setFocusable(true);
        installInputHandlers();
        applyInitialCameraDistance();
    }

    private static GLData createGlData() {
        GLData d = new GLData();
        d.swapInterval = 1;
        d.doubleBuffer = true;
        d.depthSize    = 24;
        return d;
    }

    // ── GL lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void addNotify() {
        super.addNotify();
        renderStopped = new CountDownLatch(1);
        renderRunning = true;
        renderThread  = new Thread(() -> {
            try {
                while (renderRunning) {
                    try {
                        render();
                    } catch (RuntimeException ex) {
                        // GL context was destroyed (dialog closing) – exit loop cleanly
                        if (renderRunning) System.err.println("[GL-Render] " + ex);
                        break;
                    }
                    try { Thread.sleep(16); } catch (InterruptedException e) { break; }
                }
                // Dispose the GL canvas here, while the GL context is still current and the
                // JAWT drawing surface address is still valid. PlatformWin32GLCanvas.dispose()
                // frees the DS and nulls its stored reference. When removeNotify() later calls
                // disposeCanvas() on the EDT, ds == null and the call is a safe no-op — preventing
                // the JAWT_FreeDrawingSurface(address=0) native crash seen on JDK 25.
                disposeGlCanvas();
            } finally {
                renderStopped.countDown();
            }
        }, "GL-Render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    @Override
    public void initGL() {
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        System.out.println("[GL] " + glGetString(GL_VENDOR) + " / " + glGetString(GL_VERSION));

        compileSolidShader();
        compileTexShader();
        compileLitShader();
        compileNormalsShader();
        buildGridVao();
        if (hasRenderableMesh()) {
            buildMeshVao();
            buildPerGeosetVaos();
            if (animData.hasAnimation()) animatedVertices = mesh.vertices().clone();
            // Initialize per-geoset alpha values (1.0 = fully opaque)
            geosetAlphaValues = new float[texData.length];
            java.util.Arrays.fill(geosetAlphaValues, 1.0f);
            geosetUVTransforms = new float[texData.length][];
            buildExtentVao();
            buildCollisionVao();
            buildBoneVao();
            buildNodeCubeVao();
        } else {
            buildCubeVao();
        }
    }

    @Override
    public void paintGL() {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        glViewport(0, 0, w, h);
        glClearColor(bgR, bgG, bgB, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        float[] proj = buildProjection(45f, (float)w/h, 4f, 10000f);
        float[] modelMvp = matMul(proj, buildModelView());

        // Grid (Z-up model space, XY plane at Z=0)
        if (showGrid && solidShader != 0) {
            glUseProgram(solidShader);
            glUniformMatrix4fv(solidMvp, false, modelMvp);
            glUniform3f(solidColor, 0.23f, 0.27f, 0.31f);
            glBindVertexArray(gridVao);
            glDrawArrays(GL_LINES, 0, gridVertexCount);
            glBindVertexArray(0);
        }

        if (hasRenderableMesh()) {
            if (tcDirty && geoTex != null) { tcDirty = false; reloadTeamColorTextures(); }
            // Update extent overlay if sequence changed
            SequenceInfo extUpd = pendingExtentUpdate;
            if (extUpd != null) {
                pendingExtentUpdate = null;
                if (extUpd.hasExtent()) {
                    updateExtentVao(extUpd.minExtent()[0], extUpd.minExtent()[1], extUpd.minExtent()[2],
                                    extUpd.maxExtent()[0], extUpd.maxExtent()[1], extUpd.maxExtent()[2]);
                } else {
                    updateExtentVao(mesh.minX(), mesh.minY(), mesh.minZ(),
                                    mesh.maxX(), mesh.maxY(), mesh.maxZ());
                }
            }
            if (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size()) {
                advanceAnimation();
                if (animData.hasAnimation() && animatedVertices != null) {
                    uploadAnimatedVertices();
                }
                sampleGeosetAlpha();
                sampleTextureAnims();
            } else {
                // Even without a selected sequence, global texture animations should run
                sampleTextureAnims();
            }
            if (shadingMode == ShadingMode.NORMALS && normalsShader != 0 && geoVao != null) {
                drawNormals(modelMvp);
            } else if (shadingMode == ShadingMode.LIT && litShader != 0 && geoVao != null) {
                drawLit(modelMvp);
            } else if (shadingMode == ShadingMode.TEXTURED && texShader != 0 && geoVao != null) {
                drawTextured(modelMvp);
            } else if (solidShader != 0 && meshVao != 0) {
                drawSolid(modelMvp);
            }
            // Extent overlay (bounding box wireframe)
            if (showExtent && extentVao != 0 && solidShader != 0) {
                glUseProgram(solidShader);
                glUniformMatrix4fv(solidMvp, false, modelMvp);
                glUniform3f(solidColor, 0.2f, 0.9f, 0.4f); // green wireframe
                glBindVertexArray(extentVao);
                glDrawArrays(GL_LINES, 0, extentLineCount);
                glBindVertexArray(0);
                glUseProgram(0);
            }
            // Collision shapes overlay
            if (showCollision && collisionVao != 0 && solidShader != 0) {
                glUseProgram(solidShader);
                glUniformMatrix4fv(solidMvp, false, modelMvp);
                glUniform3f(solidColor, 1.0f, 0.55f, 0.0f); // orange wireframe
                glBindVertexArray(collisionVao);
                glDrawArrays(GL_LINES, 0, collisionLineCount);
                glBindVertexArray(0);
                glUseProgram(0);
            }
            // Node overlays (Bones / Helpers / Attachments)
            boolean anyNodeOverlay = showBones || showHelpers || showAttachments;
            // Also compute world map when node names are shown (even without visual overlays)
            if (!anyNodeOverlay && showNodeNames && animData.bones().length > 0) {
                updateBoneVbo();
            }
            if (anyNodeOverlay && boneVao != 0 && solidShader != 0 && animData.bones().length > 0) {
                int[] segments = updateBoneVbo();
                // segments: [boneLines, helperLines, attachLines]

                glUseProgram(solidShader);
                glUniformMatrix4fv(solidMvp, false, modelMvp);
                glDisable(GL_DEPTH_TEST);

                // Draw parent-child lines
                int totalLines = segments[0] + segments[1] + segments[2];
                if (totalLines > 0) {
                    glBindVertexArray(boneVao);
                    int lineOff = 0;
                    if (showBones && segments[0] > 0) {
                        glUniform3f(solidColor, 1.0f, 0.6f, 0.1f); // orange
                        glDrawArrays(GL_LINES, lineOff, segments[0]);
                    }
                    lineOff += segments[0];
                    if (showHelpers && segments[1] > 0) {
                        glUniform3f(solidColor, 0.9f, 0.85f, 0.2f); // yellow
                        glDrawArrays(GL_LINES, lineOff, segments[1]);
                    }
                    lineOff += segments[1];
                    if (showAttachments && segments[2] > 0) {
                        glUniform3f(solidColor, 0.2f, 0.85f, 0.9f); // cyan
                        glDrawArrays(GL_LINES, lineOff, segments[2]);
                    }
                    glBindVertexArray(0);
                }

                // Draw 3D wireframe cubes at node positions
                if (nodeCubeVao != 0) {
                    glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                    glBindVertexArray(nodeCubeVao);
                    float ns = nodeSize;
                    if (showBones && bonePositions != null) {
                        glUniform3f(solidColor, 1.0f, 0.8f, 0.2f);
                        drawNodeCubes(bonePositions, modelMvp, ns);
                    }
                    if (showHelpers && helperPositions != null) {
                        glUniform3f(solidColor, 1.0f, 1.0f, 0.4f);
                        drawNodeCubes(helperPositions, modelMvp, ns);
                    }
                    if (showAttachments && attachPositions != null) {
                        glUniform3f(solidColor, 0.4f, 1.0f, 1.0f);
                        drawNodeCubes(attachPositions, modelMvp, ns);
                    }
                    glBindVertexArray(0);
                    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
                }

                glEnable(GL_DEPTH_TEST);
                glUseProgram(0);
            }

            // Node names (GL-rendered text overlay)
            if (showNodeNames && animData.bones().length > 0) {
                drawNodeNamesGL(modelMvp, w, h);
            }
        } else if (cubeVao != 0 && solidShader != 0) {
            float[] cubeMvp = matMul(proj, buildCameraView());
            glUseProgram(solidShader);
            glUniformMatrix4fv(solidMvp, false, cubeMvp);
            glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
            glUniform3f(solidColor, 0.74f, 0.78f, 0.86f);
            glBindVertexArray(cubeVao);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0L);
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
            glBindVertexArray(0);
            glUseProgram(0);
        }

        swapBuffers();
    }

    private void drawSolid(float[] mvp) {
        glUseProgram(solidShader);
        glUniformMatrix4fv(solidMvp, false, mvp);
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
        glUniform3f(solidColor, wireframe ? 0.84f : 0.74f, wireframe ? 0.86f : 0.78f, wireframe ? 0.92f : 0.86f);
        glBindVertexArray(meshVao);
        glDrawElements(GL_TRIANGLES, meshIndexCount, GL_UNSIGNED_INT, 0L);

        // Edge overlay: draw wireframe on top of solid fill
        if (!wireframe) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
            glEnable(GL_POLYGON_OFFSET_LINE);
            glPolygonOffset(-1f, -1f);
            glUniform3f(solidColor, 0.15f, 0.15f, 0.15f);
            glDrawElements(GL_TRIANGLES, meshIndexCount, GL_UNSIGNED_INT, 0L);
            glDisable(GL_POLYGON_OFFSET_LINE);
        }

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    private void drawTextured(float[] mvp) {
        drawPerGeoset(mvp, texShader, texMvp, texSampler, texHasTex, texAlphaThresh, texAlphaU, texUVTransform);
    }

    private void drawLit(float[] mvp) {
        drawPerGeoset(mvp, litShader, litMvp, litSampler, litHasTex, litAlphaThresh, litAlphaU, litUVTransform);
    }

    private void drawNormals(float[] mvp) {
        glUseProgram(normalsShader);
        glUniformMatrix4fv(normalsMvp, false, mvp);
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            glBindVertexArray(geoVao[gi]);
            glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
        }
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glUseProgram(0);
    }

    /**
     * Two-pass per-geoset draw with filter mode GL state.
     * Pass 1: opaque geosets (NONE, TRANSPARENT) — depth write ON.
     * Pass 2: transparent geosets (BLEND, ADDITIVE, etc.) — depth write OFF.
     */
    private static final float[] IDENTITY_4X4 = {
        1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1
    };

    private void drawPerGeoset(float[] mvp, int shader, int mvpLoc, int samplerLoc,
                                int hasTexLoc, int alphaThreshLoc, int alphaLoc, int uvTransformLoc) {
        glUseProgram(shader);
        glUniformMatrix4fv(mvpLoc, false, mvp);
        glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);

        // Pass 1: opaque (NONE + TRANSPARENT)
        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            if (gi < texData.length && !texData[gi].isOpaque()) continue;
            float alpha = geosetAlpha(gi);
            if (alpha <= 0f) continue; // fully invisible
            setUVTransformUniform(uvTransformLoc, gi);
            drawGeoset(gi, samplerLoc, hasTexLoc, alphaThreshLoc, alphaLoc, alpha);
        }

        // Pass 2: transparent (BLEND, ADDITIVE, ADDALPHA, MODULATE, MODULATE2X)
        glEnable(GL_BLEND);
        glDepthMask(false);
        for (int gi = 0; gi < geoVao.length; gi++) {
            if (geoVao[gi] == 0 || geoIndexCount[gi] == 0) continue;
            if (gi >= texData.length || texData[gi].isOpaque()) continue;
            float alpha = geosetAlpha(gi);
            if (alpha <= 0f) continue;
            // Team glow always uses additive blending
            if (texData[gi].replaceableId() == 2) {
                glBlendFunc(GL_SRC_ALPHA, GL_ONE);
            } else {
                applyBlendMode(texData[gi].filterMode());
            }
            setUVTransformUniform(uvTransformLoc, gi);
            drawGeoset(gi, samplerLoc, hasTexLoc, alphaThreshLoc, alphaLoc, alpha);
        }
        glDepthMask(true);
        glDisable(GL_BLEND);

        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        glBindVertexArray(0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
    }

    private void setUVTransformUniform(int loc, int gi) {
        if (loc < 0) return;
        float[] m = (geosetUVTransforms != null && gi < geosetUVTransforms.length)
                ? geosetUVTransforms[gi] : null;
        glUniformMatrix4fv(loc, false, m != null ? m : IDENTITY_4X4);
    }

    private float geosetAlpha(int gi) {
        return (geosetAlphaValues != null && gi < geosetAlphaValues.length)
                ? geosetAlphaValues[gi] : 1.0f;
    }

    private void drawGeoset(int gi, int samplerLoc, int hasTexLoc, int alphaThreshLoc,
                             int alphaLoc, float alpha) {
        boolean hasTex = geoTex != null && gi < geoTex.length && geoTex[gi] != 0;
        glUniform1i(hasTexLoc, hasTex ? 1 : 0);
        // Alpha threshold: 0.75 for TRANSPARENT, 0 for everything else
        float threshold = (gi < texData.length && texData[gi].filterMode() == 1) ? 0.75f : 0.0f;
        glUniform1f(alphaThreshLoc, threshold);
        glUniform1f(alphaLoc, alpha);
        if (hasTex) {
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, geoTex[gi]);
            glUniform1i(samplerLoc, 0);
        }
        glBindVertexArray(geoVao[gi]);
        glDrawElements(GL_TRIANGLES, geoIndexCount[gi], GL_UNSIGNED_INT, 0L);
    }

    /** Sets GL blend function for a given MDX filter mode ordinal. */
    static void applyBlendMode(int filterMode) {
        switch (filterMode) {
            case 2 -> // BLEND
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            case 3, 4 -> // ADDITIVE, ADDALPHA
                glBlendFunc(GL_SRC_ALPHA, GL_ONE);
            case 5 -> // MODULATE
                glBlendFunc(GL_ZERO, GL_SRC_COLOR);
            case 6 -> // MODULATE2X
                glBlendFunc(GL_DST_COLOR, GL_SRC_COLOR);
            default ->
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    }

    /**
     * Calls AWTGLCanvas.disposeCanvas() via reflection from the render thread.
     * Must be called while the GL context is current (i.e. from the render thread after the loop).
     */
    private void disposeGlCanvas() {
        try {
            java.lang.reflect.Method m = AWTGLCanvas.class.getDeclaredMethod("disposeCanvas");
            m.setAccessible(true);
            m.invoke(this);
        } catch (Exception ex) {
            System.err.println("[GL] disposeGlCanvas: " + ex);
        }
    }

    /**
     * Stop the render thread and wait for it to exit.
     *
     * MUST be called while the EDT does NOT hold the AWT toolkit lock (i.e. from
     * WindowListener.windowClosing(), before DISPOSE_ON_CLOSE calls dispose()).
     * If called from removeNotify() (AWT lock held), JAWT_DrawingSurface_Lock() in
     * the render thread will deadlock waiting for that same lock.
     */
    public void stopRenderThread() {
        renderRunning = false;
        if (renderThread != null) renderThread.interrupt();
        if (renderStopped != null) {
            try {
                renderStopped.await(2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void removeNotify() {
        // Signal the render thread to stop (if not already stopped by windowClosing).
        renderRunning = false;
        if (renderThread != null) renderThread.interrupt();
        // Brief non-blocking wait — if already stopped via windowClosing this is instant.
        if (renderStopped != null) {
            try { renderStopped.await(100, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        // By now the render thread has exited and called disposeGlCanvas(), which set ds=null
        // inside PlatformWin32GLCanvas. The super.removeNotify() → disposeCanvas() call below
        // will therefore be a safe no-op (the ds null-check inside dispose() prevents
        // JAWT_FreeDrawingSurface from being called with address=0, which is a fatal native
        // crash on JDK 25).
        try {
            super.removeNotify();
        } catch (NullPointerException ex) {
            if (ex.getMessage() == null || !ex.getMessage().contains("JAWTDrawingSurface")) throw ex;
        }
    }

    // ── Animation ────────────────────────────────────────────────────────────

    private void advanceAnimation() {
        long nowNs = System.nanoTime(), prev = lastNanoNs;
        if (prev == 0L || !animPlaying) { lastNanoNs = nowNs; return; }
        long deltaNs = nowNs - prev; lastNanoNs = nowNs;
        long deltaMs = (long)(deltaNs / 1_000_000.0 * animSpeed);
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        long duration = seq.end() - seq.start();
        if (duration <= 0) return;
        long newTime = animTimeMs + deltaMs;
        if (newTime >= seq.end()) {
            if (animLooping) {
                long t = newTime - seq.start();
                animTimeMs = ((t % duration) + duration) % duration + seq.start();
            } else {
                animTimeMs = seq.end();
                animPlaying = false;
                Runnable cb = onAnimationFinished;
                if (cb != null) SwingUtilities.invokeLater(cb);
            }
        } else {
            animTimeMs = newTime;
        }
    }

    private void sampleGeosetAlpha() {
        if (geosetAlphaValues == null) return;
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        long[] globalSeqs = animData.globalSequences();
        for (int gi = 0; gi < geosetAlphaValues.length; gi++) {
            AnimTrack track = animData.geosetAlpha().get(gi);
            if (track != null && !track.isEmpty()) {
                if (track.isGlobal() || hasKeysInRange(track, seq.start(), seq.end())) {
                    float val = BoneAnimator.interpTrackScalar(track, animTimeMs, seq.start(), seq.end(), globalSeqs, 1f);
                    geosetAlphaValues[gi] = Math.max(0f, Math.min(1f, val));
                } else {
                    geosetAlphaValues[gi] = 1.0f;
                }
            } else {
                geosetAlphaValues[gi] = 1.0f;
            }
        }
    }

    private void sampleTextureAnims() {
        if (geosetUVTransforms == null) return;
        Map<Integer, TextureAnimTracks> taMap = animData.textureAnims();
        long[] globalSeqs = animData.globalSequences();
        for (int gi = 0; gi < geosetUVTransforms.length; gi++) {
            TextureAnimTracks ta = taMap.get(gi);
            if (ta == null || !ta.hasAnimation()) {
                geosetUVTransforms[gi] = null; // identity
                continue;
            }

            // Determine time for non-global tracks
            long t;
            long s0, s1;
            if (currentSeqIdx >= 0 && currentSeqIdx < animData.sequences().size()) {
                SequenceInfo seq = animData.sequences().get(currentSeqIdx);
                t = animTimeMs;
                s0 = seq.start();
                s1 = seq.end();
            } else {
                // No sequence selected — only global tracks can animate
                if (ta.globalSequenceId() < 0) {
                    geosetUVTransforms[gi] = null;
                    continue;
                }
                t = 0; s0 = 0; s1 = 0;
            }

            // interpTrack* auto-dispatches to cyclic for global sequence tracks
            float[] trans = BoneAnimator.interpTrackVec3(ta.translation(), t, s0, s1, globalSeqs, 0, 0, 0);
            float[] rot = BoneAnimator.interpTrackQuat(ta.rotation(), t, s0, s1, globalSeqs);
            float[] scl = BoneAnimator.interpTrackVec3(ta.scale(), t, s0, s1, globalSeqs, 1, 1, 1);
            geosetUVTransforms[gi] = buildUVTransformMatrix(trans, rot, scl);
        }
    }

    /**
     * Builds a 4x4 column-major UV transform matrix from T/R/S around center (0.5, 0.5).
     * TRS order: translate to origin → scale → rotate → translate back → apply translation.
     */
    static float[] buildUVTransformMatrix(float[] trans, float[] rot, float[] scl) {
        float cx = 0.5f, cy = 0.5f;
        // Quaternion → rotation matrix (2D: only z-axis rotation matters for UV, but we do full 3D)
        float qx = rot[0], qy = rot[1], qz = rot[2], qw = rot[3];
        float r00 = 1 - 2*(qy*qy + qz*qz), r01 = 2*(qx*qy - qz*qw), r02 = 2*(qx*qz + qy*qw);
        float r10 = 2*(qx*qy + qz*qw),     r11 = 1 - 2*(qx*qx + qz*qz), r12 = 2*(qy*qz - qx*qw);
        float r20 = 2*(qx*qz - qy*qw),     r21 = 2*(qy*qz + qx*qw),     r22 = 1 - 2*(qx*qx + qy*qy);

        float sx = scl[0], sy = scl[1], sz = scl[2];

        // M = T(trans) * T(center) * R * S * T(-center)
        // Combine: first translate by -center, then scale, then rotate, then translate by center+trans
        float m00 = r00*sx, m01 = r01*sy, m02 = r02*sz;
        float m10 = r10*sx, m11 = r11*sy, m12 = r12*sz;
        float m20 = r20*sx, m21 = r21*sy, m22 = r22*sz;

        float tx = -cx*m00 - cy*m01 + cx + trans[0];
        float ty = -cx*m10 - cy*m11 + cy + trans[1];
        float tz = -cx*m20 - cy*m21 + trans[2];

        // Column-major 4x4
        return new float[] {
            m00, m10, m20, 0,
            m01, m11, m21, 0,
            m02, m12, m22, 0,
            tx,  ty,  tz,  1
        };
    }

    /** Returns true if the track has at least one keyframe within [start, end]. */
    private static boolean hasKeysInRange(AnimTrack track, long start, long end) {
        for (long f : track.frames()) {
            if (f >= start && f <= end) return true;
        }
        return false;
    }

    private void uploadAnimatedVertices() {
        SequenceInfo seq = animData.sequences().get(currentSeqIdx);
        Map<Integer, float[]> worldMap = BoneAnimator.computeWorldMatrices(
                animData.bones(), animTimeMs, seq.start(), seq.end(), animData.globalSequences());

        // Update flat combined buffer
        int meshVertOffset = 0;
        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount(); if (vc == 0) continue;
            if (skin.hasSkinning()) {
                for (int vi = 0; vi < vc; vi++) {
                    float[] p = transformVertex(skin, vi, worldMap);
                    int base = (meshVertOffset + vi) * 3;
                    animatedVertices[base] = p[0]; animatedVertices[base+1] = p[1]; animatedVertices[base+2] = p[2];
                }
            } else {
                System.arraycopy(skin.bindVertices(), 0, animatedVertices, meshVertOffset * 3, vc * 3);
            }
            meshVertOffset += vc;
        }
        glBindBuffer(GL_ARRAY_BUFFER, meshVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, animatedVertices);

        // Update per-geoset position VBOs
        if (geoVbo != null && geoVertCount != null) {
            int off = 0;
            for (int gi = 0; gi < geoVbo.length; gi++) {
                int vc = geoVertCount[gi];
                if (geoVbo[gi] != 0 && vc > 0) {
                    float[] slice = new float[vc * 3];
                    System.arraycopy(animatedVertices, off * 3, slice, 0, vc * 3);
                    glBindBuffer(GL_ARRAY_BUFFER, geoVbo[gi]);
                    glBufferSubData(GL_ARRAY_BUFFER, 0L, slice);
                }
                off += vc;
            }
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    static float[] transformVertex(GeosetSkinData skin, int vi, Map<Integer, float[]> wm) {
        float bx = skin.bindVertices()[vi*3], by = skin.bindVertices()[vi*3+1], bz = skin.bindVertices()[vi*3+2];
        int[] vg = skin.vertexGroup();
        int gi = (vg != null && vi < vg.length) ? vg[vi] : 0;
        int[][] g = skin.groupBoneObjectIds();
        if (gi >= g.length || g[gi].length == 0) return new float[]{bx,by,bz};
        float[] avg = new float[16]; int cnt = 0;
        for (int bid : g[gi]) { float[] m = wm.get(bid); if (m!=null){for(int j=0;j<16;j++)avg[j]+=m[j];cnt++;} }
        if (cnt == 0) return new float[]{bx,by,bz};
        float inv = 1f/cnt; for(int j=0;j<16;j++) avg[j]*=inv;
        return new float[]{ avg[0]*bx+avg[4]*by+avg[8]*bz+avg[12], avg[1]*bx+avg[5]*by+avg[9]*bz+avg[13], avg[2]*bx+avg[6]*by+avg[10]*bz+avg[14] };
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setSequence(int idx) {
        if (idx < 0 || idx >= animData.sequences().size()) return;
        currentSeqIdx = idx;
        SequenceInfo seq = animData.sequences().get(idx);
        animTimeMs    = seq.start();
        lastNanoNs    = 0L;
        // Update extent overlay to show sequence extents if available
        pendingExtentUpdate = seq;
    }
    private volatile SequenceInfo pendingExtentUpdate;
    public void setPlaying(boolean p) { animPlaying = p; if (p) lastNanoNs = 0L; }
    public boolean isPlaying()        { return animPlaying; }
    public void setSpeed(float s)     { animSpeed = Math.max(0.1f, s); }
    public boolean hasAnimationData() { return animData.hasAnimation(); }
    public void setLooping(boolean l)          { animLooping = l; }
    private volatile Runnable onAnimationFinished;
    public void setOnAnimationFinished(Runnable r) { onAnimationFinished = r; }
    public void setTeamColor(int idx)          { int v = Math.max(0, Math.min(11, idx)); if (v != teamColorIdx) { teamColorIdx = v; tcDirty = true; } }
    public int  getTeamColor()                 { return teamColorIdx; }
    public void setShadingMode(ShadingMode m) { shadingMode = m != null ? m : ShadingMode.SOLID; }
    public void setWireframe(boolean w)       { wireframe = w; }
    public void setShowExtent(boolean e)     { showExtent = e; }
    public void setShowBones(boolean b)       { showBones = b; }
    public void setShowHelpers(boolean h)    { showHelpers = h; }
    public void setShowAttachments(boolean a){ showAttachments = a; }
    public void setShowNodeNames(boolean n)  { showNodeNames = n; }
    public void setShowGrid(boolean g)       { showGrid = g; }
    public void setShowCollision(boolean c)  { showCollision = c; }
    public void setNodeSize(float s)         { nodeSize = Math.max(0.5f, Math.min(20f, s)); }

    /** Snaps the camera to the model's camera node position/target. */
    public void applyCameraView(CameraNode cam) {
        if (cam == null) return;
        // Save current orbit state
        savedYaw = yawDegrees; savedPitch = pitchDegrees;
        savedDistance = distance; savedPanX = panX; savedPanY = panY;

        // WC3 is Z-up; our orbit camera works in Y-up after the -90° X rotation.
        // Camera position and target are in Z-up model space.
        float cx = cam.position()[0], cy = cam.position()[1], cz = cam.position()[2];
        float tx = cam.targetPosition()[0], ty = cam.targetPosition()[1], tz = cam.targetPosition()[2];
        // Direction from camera to target
        float dx = tx - cx, dy = ty - cy, dz = tz - cz;
        float dist = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
        if (dist < 0.001f) dist = 100f;

        // Convert to Y-up orbit angles:
        // In Z-up: X=right, Y=forward, Z=up
        // yaw = angle in XY plane from Y axis
        // pitch = angle from XY plane toward Z
        float horizLen = (float) Math.sqrt(dx*dx + dy*dy);
        yawDegrees = (float) Math.toDegrees(Math.atan2(-dx, -dy));
        pitchDegrees = horizLen > 0.001f ? (float) Math.toDegrees(Math.atan2(dz, horizLen)) : 0f;

        // Place the orbit center at the target, distance = camera-to-target distance
        distance = dist * modelScale;
        // Pan to center on the target (convert from Z-up model to Y-up view)
        panX = 0f;
        panY = 0f;
        usingCameraView = true;
        cameraViewNode = cam;
    }

    /** Restores the orbit camera after Camera View. */
    public void resetCameraView() {
        if (!usingCameraView) return;
        yawDegrees = savedYaw; pitchDegrees = savedPitch;
        distance = savedDistance; panX = savedPanX; panY = savedPanY;
        usingCameraView = false;
        cameraViewNode = null;
    }
    public void setBackgroundColor(String hex) {
        if (hex == null || hex.length() < 6) return;
        try {
            bgR = Integer.parseInt(hex.substring(0, 2), 16) / 255f;
            bgG = Integer.parseInt(hex.substring(2, 4), 16) / 255f;
            bgB = Integer.parseInt(hex.substring(4, 6), 16) / 255f;
        } catch (NumberFormatException ignored) {}
    }
    public ModelMesh getModelMesh()           { return mesh; }
    public ModelAnimData getAnimData()        { return animData; }
    public GeosetTexData[] getTexData()       { return texData; }

    /**
     * Renders node names as a GL textured quad overlay.
     * Projects 3D bone positions to 2D, draws text into a BufferedImage,
     * uploads as a GL texture, and draws a fullscreen blended quad.
     */
    private void drawNodeNamesGL(float[] modelMvp, int vpW, int vpH) {
        Map<Integer, float[]> wm = lastWorldMap;
        if (wm == null || vpW <= 0 || vpH <= 0) return;
        BoneNode[] bones = animData.bones();

        // Build list of (name, screenX, screenY)
        java.util.List<int[]> screenPositions = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        boolean anyTypeFilter = showBones || showHelpers || showAttachments;
        for (BoneNode bone : bones) {
            if (bone.name().isEmpty()) continue;
            if (anyTypeFilter) {
                boolean visible = switch (bone.nodeType()) {
                    case BONE       -> showBones;
                    case HELPER     -> showHelpers;
                    case ATTACHMENT -> showAttachments;
                };
                if (!visible) continue;
            }

            float[] pos = boneWorldPos(bone, wm);
            float wx = pos[0], wy = pos[1], wz = pos[2];

            float cx = modelMvp[0]*wx + modelMvp[4]*wy + modelMvp[8]*wz + modelMvp[12];
            float cy = modelMvp[1]*wx + modelMvp[5]*wy + modelMvp[9]*wz + modelMvp[13];
            float cw = modelMvp[3]*wx + modelMvp[7]*wy + modelMvp[11]*wz + modelMvp[15];
            if (cw <= 0.001f) continue;

            float ndcX = cx / cw, ndcY = cy / cw;
            int sx = (int) ((ndcX * 0.5f + 0.5f) * vpW);
            int sy = (int) ((1f - (ndcY * 0.5f + 0.5f)) * vpH);

            names.add(bone.name());
            screenPositions.add(new int[]{sx, sy});
        }
        if (names.isEmpty()) return;

        // Render text to BufferedImage
        java.awt.image.BufferedImage textImg = new java.awt.image.BufferedImage(
                vpW, vpH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = textImg.createGraphics();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11));
        java.awt.FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int[] sp = screenPositions.get(i);
            int tw = fm.stringWidth(name);
            int tx = sp[0] - tw / 2;
            int ty = sp[1] + 14;
            // Shadow
            g2.setColor(new java.awt.Color(0, 0, 0, 180));
            g2.drawString(name, tx + 1, ty + 1);
            // Text
            g2.setColor(new java.awt.Color(220, 230, 240));
            g2.drawString(name, tx, ty);
        }
        g2.dispose();

        // Upload as GL texture
        if (nodeNamesTex == 0) nodeNamesTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, nodeNamesTex);
        // Convert to RGBA byte buffer
        int[] pixels = textImg.getRGB(0, 0, vpW, vpH, null, 0, vpW);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(vpW * vpH * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        for (int pixel : pixels) {
            buf.put((byte) ((pixel >> 16) & 0xFF)); // R
            buf.put((byte) ((pixel >> 8) & 0xFF));  // G
            buf.put((byte) (pixel & 0xFF));          // B
            buf.put((byte) ((pixel >> 24) & 0xFF)); // A
        }
        buf.flip();
        if (vpW != nodeNamesTexW || vpH != nodeNamesTexH) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, vpW, vpH, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            nodeNamesTexW = vpW;
            nodeNamesTexH = vpH;
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, vpW, vpH, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        }

        // Build fullscreen quad VAO if needed
        if (nodeNamesQuadVao == 0) {
            float[] quad = {
                // pos (x,y), uv (u,v)  — V flipped so BufferedImage top maps to GL top
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                 1f,  1f, 1f, 1f,
                -1f, -1f, 0f, 0f,
                 1f,  1f, 1f, 1f,
                -1f,  1f, 0f, 1f,
            };
            nodeNamesQuadVao = glGenVertexArrays();
            nodeNamesQuadVbo = glGenBuffers();
            glBindVertexArray(nodeNamesQuadVao);
            glBindBuffer(GL_ARRAY_BUFFER, nodeNamesQuadVbo);
            glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0L);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8L);
            glEnableVertexAttribArray(1);
            glBindVertexArray(0);
        }

        // Draw fullscreen quad with blending
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);
        glUseProgram(texShader);
        // Identity MVP (NDC passthrough)
        float[] identity = identity();
        glUniformMatrix4fv(texMvp, false, identity);
        glUniform1i(texHasTex, 1);
        glUniform1f(texAlphaThresh, 0f);
        glUniform1f(texAlphaU, 1f);
        glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, nodeNamesTex);
        glUniform1i(texSampler, 0);
        glBindVertexArray(nodeNamesQuadVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glUseProgram(0);
    }

    // ── Shader compilation ───────────────────────────────────────────────────

    private void compileSolidShader() {
        solidShader = linkProgram(SOLID_VERT, SOLID_FRAG);
        if (solidShader != 0) { solidMvp = glGetUniformLocation(solidShader,"mvp"); solidColor = glGetUniformLocation(solidShader,"uColor"); }
    }
    private void compileTexShader() {
        texShader = linkProgram(TEX_VERT, TEX_FRAG);
        if (texShader != 0) { texMvp = glGetUniformLocation(texShader,"mvp"); texSampler = glGetUniformLocation(texShader,"uTex"); texHasTex = glGetUniformLocation(texShader,"uHasTex"); texAlphaThresh = glGetUniformLocation(texShader,"uAlphaThreshold"); texAlphaU = glGetUniformLocation(texShader,"uAlpha"); texUVTransform = glGetUniformLocation(texShader,"uUVTransform"); }
    }
    private void compileLitShader() {
        litShader = linkProgram(LIT_VERT, LIT_FRAG);
        if (litShader != 0) { litMvp = glGetUniformLocation(litShader,"mvp"); litSampler = glGetUniformLocation(litShader,"uTex"); litHasTex = glGetUniformLocation(litShader,"uHasTex"); litAlphaThresh = glGetUniformLocation(litShader,"uAlphaThreshold"); litAlphaU = glGetUniformLocation(litShader,"uAlpha"); litUVTransform = glGetUniformLocation(litShader,"uUVTransform"); }
    }
    private void compileNormalsShader() {
        normalsShader = linkProgram(NORMALS_VERT, NORMALS_FRAG);
        if (normalsShader != 0) { normalsMvp = glGetUniformLocation(normalsShader, "mvp"); }
    }
    static int linkProgram(String vs, String fs) {
        int v = compileShader(GL_VERTEX_SHADER,vs), f = compileShader(GL_FRAGMENT_SHADER,fs);
        if (v==0||f==0) return 0;
        int p = glCreateProgram(); glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p); glDeleteShader(v); glDeleteShader(f);
        if (glGetProgrami(p,GL_LINK_STATUS)==GL_FALSE){System.err.println("[GL] link: "+glGetProgramInfoLog(p));glDeleteProgram(p);return 0;}
        return p;
    }
    static int compileShader(int type, String src) {
        int id = glCreateShader(type); glShaderSource(id,src); glCompileShader(id);
        if (glGetShaderi(id,GL_COMPILE_STATUS)==GL_FALSE){System.err.println("[GL] compile: "+glGetShaderInfoLog(id));glDeleteShader(id);return 0;}
        return id;
    }

    // ── Geometry upload ──────────────────────────────────────────────────────

    private void buildGridVao() {
        // Grid in Z-up XY plane at Z=0 (model space), so it aligns with the model's ground
        int half=200,step=20,cnt=0; for(int i=-half;i<=half;i+=step)cnt++;
        gridVertexCount=cnt*4; float[] d=new float[gridVertexCount*3]; int ix=0;
        for(int i=-half;i<=half;i+=step){
            // line along Y: (i, -half, 0) → (i, half, 0)
            d[ix++]=i;d[ix++]=-half;d[ix++]=0;d[ix++]=i;d[ix++]=half;d[ix++]=0;
            // line along X: (-half, i, 0) → (half, i, 0)
            d[ix++]=-half;d[ix++]=i;d[ix++]=0;d[ix++]=half;d[ix++]=i;d[ix++]=0;
        }
        gridVao=glGenVertexArrays();gridVbo=glGenBuffers();glBindVertexArray(gridVao);glBindBuffer(GL_ARRAY_BUFFER,gridVbo);glBufferData(GL_ARRAY_BUFFER,d,GL_STATIC_DRAW);glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);glBindVertexArray(0);
    }

    private void buildMeshVao() {
        float[] v=mesh.vertices(); int[] ix=mesh.indices();
        if(v.length==0||ix.length==0) return;
        meshIndexCount=ix.length; meshVao=glGenVertexArrays();meshVbo=glGenBuffers();meshEbo=glGenBuffers();
        glBindVertexArray(meshVao);
        glBindBuffer(GL_ARRAY_BUFFER,meshVbo);glBufferData(GL_ARRAY_BUFFER,v,animData.hasAnimation()?GL_DYNAMIC_DRAW:GL_STATIC_DRAW);
        glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,meshEbo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,ix,GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    /**
     * Builds per-geoset VAOs for Textured mode.
     *
     * texData[] has one entry per MESH-INCLUDED geoset.
     * animData.geosets() has one entry per ALL geosets (EMPTY for skipped ones).
     * We iterate animData.geosets(), skip EMPTY entries, and use a separate texIdx counter
     * to index into texData[].
     */
    private void buildPerGeosetVaos() {
        int geoCount = texData.length;
        if (geoCount == 0) return;

        geoVao       = new int[geoCount];
        geoVbo       = new int[geoCount];
        geoUvVbo     = new int[geoCount];
        geoEbo       = new int[geoCount];
        geoIndexCount= new int[geoCount];
        geoTex       = new int[geoCount];
        geoVertCount = new int[geoCount];

        int[] allIndices = mesh.indices();
        int vertOffset   = 0;
        int indexOffset  = 0;
        int gi           = 0;   // index into texData[] / geoVao[]

        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount();
            if (vc == 0) continue;   // EMPTY – skipped in mesh
            if (gi >= geoCount) break;

            // Count indices belonging to this geoset
            // (all adjacent indices in range [vertOffset, vertOffset+vc))
            int faceCount = 0;
            for (int ii = indexOffset; ii < allIndices.length; ii++) {
                if (allIndices[ii] >= vertOffset && allIndices[ii] < vertOffset + vc) faceCount++;
                else break;
            }

            if (faceCount > 0) {
                float[] verts = new float[vc * 3];
                System.arraycopy(mesh.vertices(), vertOffset * 3, verts, 0, vc * 3);

                int[] indices = new int[faceCount];
                for (int ii = 0; ii < faceCount; ii++) indices[ii] = allIndices[indexOffset + ii] - vertOffset;

                int usage = animData.hasAnimation() ? GL_DYNAMIC_DRAW : GL_STATIC_DRAW;
                geoVao[gi]        = glGenVertexArrays();
                geoVbo[gi]        = glGenBuffers();
                geoEbo[gi]        = glGenBuffers();
                geoIndexCount[gi] = faceCount;
                geoVertCount[gi]  = vc;

                glBindVertexArray(geoVao[gi]);
                glBindBuffer(GL_ARRAY_BUFFER, geoVbo[gi]);
                glBufferData(GL_ARRAY_BUFFER, verts, usage);
                glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
                glEnableVertexAttribArray(0);

                if (texData[gi].hasUvs()) {
                    geoUvVbo[gi] = glGenBuffers();
                    glBindBuffer(GL_ARRAY_BUFFER, geoUvVbo[gi]);
                    glBufferData(GL_ARRAY_BUFFER, texData[gi].uvs(), GL_STATIC_DRAW);
                    glVertexAttribPointer(1, 2, GL_FLOAT, false, 8, 0L);
                    glEnableVertexAttribArray(1);
                }

                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, geoEbo[gi]);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
                glBindVertexArray(0);

                String texPath = texData[gi].texturePath();
                int replId = texData[gi].replaceableId();
                if (replId == 1 && !texPath.isEmpty()) {
                    // Team color + base texture: composite TC under base
                    geoTex[gi] = loadTeamColorTexture(texPath, teamColorIdx);
                } else if (replId == 1) {
                    // Team color only (no base): solid TC color texture
                    geoTex[gi] = createSolidColorTexture(TEAM_COLORS[teamColorIdx]);
                } else if (replId == 2) {
                    // Team glow: try loading glow texture, fallback to base
                    String glowPath = replaceableTexturePath(2, teamColorIdx);
                    geoTex[gi] = loadGlTexture(glowPath);
                    if (geoTex[gi] == 0 && !texPath.isEmpty()) {
                        geoTex[gi] = loadGlTexture(texPath);
                    }
                } else if (!texPath.isEmpty()) {
                    geoTex[gi] = loadGlTexture(texPath);
                }

                indexOffset += faceCount;
            }
            vertOffset += vc;
            gi++;
        }
    }

    // WC3 team color RGB values (indices 0–11)
    private static final int[][] TEAM_COLORS = {
        {255,   3,   3}, // 0 Red
        {  0,  66, 255}, // 1 Blue
        {  0, 206, 209}, // 2 Teal
        { 84,   0, 129}, // 3 Purple
        {255, 252,   0}, // 4 Yellow
        {254, 138,  14}, // 5 Orange
        { 32, 192,   0}, // 6 Green
        {229,  91, 176}, // 7 Pink
        {149, 150, 151}, // 8 Gray
        {126, 191, 241}, // 9 Light Blue
        {  0,  97,  31}, // 10 Dark Green
        { 78,  42,   4}, // 11 Brown
    };

    private static String replaceableTexturePath(int replaceableId, int teamColorIdx) {
        String idx = String.format("%02d", teamColorIdx);
        if (replaceableId == 1) return "ReplaceableTextures\\TeamColor\\TeamColor" + idx + ".blp";
        if (replaceableId == 2) return "ReplaceableTextures\\TeamGlow\\TeamGlow" + idx + ".blp";
        return "";
    }

    /**
     * Loads the base texture and composites team color underneath using:
     * result_rgb = base_alpha * base_rgb + (1 - base_alpha) * tc_rgb
     */
    private int loadTeamColorTexture(String basePath, int tcIdx) {
        BufferedImage base = GameDataSource.getInstance().loadTexture(basePath, modelDir, rootDir);
        if (base == null) {
            System.out.println("[GL] TC base texture not found: " + basePath + ", using solid TC");
            return createSolidColorTexture(TEAM_COLORS[tcIdx]);
        }
        int[] tc = TEAM_COLORS[tcIdx];
        int w = base.getWidth(), h = base.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = base.getRGB(x, y);
                float a = ((argb >> 24) & 0xFF) / 255f;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >>  8) & 0xFF;
                int b =  argb        & 0xFF;
                // Composite: TC shows through transparent areas, base shows in opaque areas
                int cr = Math.round(a * r + (1f - a) * tc[0]);
                int cg = Math.round(a * g + (1f - a) * tc[1]);
                int cb = Math.round(a * b + (1f - a) * tc[2]);
                // Keep full alpha — the geoset's filterMode controls blending in the renderer
                result.setRGB(x, y, 0xFF000000 | (cr << 16) | (cg << 8) | cb);
            }
        }
        System.out.println("[GL] Composited TC " + tcIdx + " with " + basePath + " (" + w + "x" + h + ")");
        return uploadTexture(result);
    }

    /** Creates a 4x4 solid color GL texture. */
    private static int createSolidColorTexture(int[] rgb) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++) {
            buf.put((byte) rgb[0]); buf.put((byte) rgb[1]); buf.put((byte) rgb[2]); buf.put((byte) 255);
        }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    /** Re-composites and re-uploads GL textures for all geosets with team color. */
    private void reloadTeamColorTextures() {
        int tc = teamColorIdx;
        for (int gi = 0; gi < texData.length; gi++) {
            if (!texData[gi].hasTeamColor()) continue;
            if (gi >= geoTex.length) break;
            // Delete old texture
            if (geoTex[gi] != 0) { glDeleteTextures(geoTex[gi]); geoTex[gi] = 0; }
            int replId = texData[gi].replaceableId();
            String texPath = texData[gi].texturePath();
            if (replId == 1 && !texPath.isEmpty()) {
                geoTex[gi] = loadTeamColorTexture(texPath, tc);
            } else if (replId == 1) {
                geoTex[gi] = createSolidColorTexture(TEAM_COLORS[tc]);
            } else if (replId == 2) {
                String glowPath = replaceableTexturePath(2, tc);
                geoTex[gi] = loadGlTexture(glowPath);
                if (geoTex[gi] == 0 && !texPath.isEmpty()) {
                    geoTex[gi] = loadGlTexture(texPath);
                }
            }
        }
    }

    private int loadGlTexture(String texPath) {
        BufferedImage img = GameDataSource.getInstance().loadTexture(texPath, modelDir, rootDir);
        if (img == null) {
            System.out.println("[GL] Texture not found: " + texPath);
            return 0;
        }
        System.out.println("[GL] Loaded texture: " + texPath + " (" + img.getWidth() + "x" + img.getHeight() + ")");
        return uploadTexture(img);
    }

    static int uploadTexture(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        // Flip Y: OpenGL expects rows bottom-to-top; BufferedImage/BLP stores top-to-bottom
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                buf.put((byte)((rgb >> 16) & 0xFF));
                buf.put((byte)((rgb >>  8) & 0xFF));
                buf.put((byte)( rgb        & 0xFF));
                buf.put((byte)((rgb >> 24) & 0xFF));
            }
        }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    private void buildBoneVao() {
        if (animData.bones().length == 0) return;
        // Only lines now (cubes are drawn separately via nodeCubeVao)
        int maxLineVerts = animData.bones().length * 2;
        boneVao = glGenVertexArrays(); boneVbo = glGenBuffers();
        glBindVertexArray(boneVao);
        glBindBuffer(GL_ARRAY_BUFFER, boneVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) maxLineVerts * 3 * 4, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    /** Unit cube centered at origin (±0.5), used for node overlays. */
    private void buildNodeCubeVao() {
        float s = 0.5f;
        float[] v = {
            -s,-s,-s, s,-s,-s, s,s,-s, -s,s,-s,
            -s,-s, s, s,-s, s, s,s, s, -s,s, s
        };
        int[] ix = {
            0,1,2,0,2,3, 4,5,6,4,6,7, 0,4,7,0,7,3,
            1,5,6,1,6,2, 0,1,5,0,5,4, 3,2,6,3,6,7
        };
        nodeCubeVao = glGenVertexArrays(); nodeCubeVbo = glGenBuffers(); nodeCubeEbo = glGenBuffers();
        glBindVertexArray(nodeCubeVao);
        glBindBuffer(GL_ARRAY_BUFFER, nodeCubeVbo);
        glBufferData(GL_ARRAY_BUFFER, v, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, nodeCubeEbo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ix, GL_STATIC_DRAW);
        glBindVertexArray(0);
    }

    /**
     * Extracts the animated world-space position of a bone node.
     * This is the bone's pivot point transformed through its world matrix.
     * Using worldMatrix × pivot (not just column 3, which is worldMatrix × origin).
     */
    private static float[] boneWorldPos(BoneNode bone, Map<Integer, float[]> worldMap) {
        float[] w = worldMap.get(bone.objectId());
        float[] p = bone.pivot();
        float px = p != null && p.length > 0 ? p[0] : 0f;
        float py = p != null && p.length > 1 ? p[1] : 0f;
        float pz = p != null && p.length > 2 ? p[2] : 0f;
        if (w == null) return new float[]{px, py, pz};
        return new float[]{
            w[0]*px + w[4]*py + w[8]*pz  + w[12],
            w[1]*px + w[5]*py + w[9]*pz  + w[13],
            w[2]*px + w[6]*py + w[10]*pz + w[14]
        };
    }

    /**
     * Draws a cube at each position using the solid shader (must be active, nodeCubeVao bound).
     * Each cube is translated+scaled via a modified MVP.
     */
    private void drawNodeCubes(float[][] positions, float[] baseMvp, float size) {
        for (float[] pos : positions) {
            // Build mvp = baseMvp × T(pos) × S(size)
            float[] m = identity();
            m[0] = size; m[5] = size; m[10] = size;
            m[12] = pos[0]; m[13] = pos[1]; m[14] = pos[2];
            float[] cubeMvp = matMul(baseMvp, m);
            glUniformMatrix4fv(solidMvp, false, cubeMvp);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0L);
        }
    }

    /**
     * Updates the bone VBO with parent-child lines grouped by node type,
     * and caches per-type node positions for cube drawing.
     * Returns [boneLineVerts, helperLineVerts, attachLineVerts].
     */
    private int[] updateBoneVbo() {
        BoneNode[] bones = animData.bones();
        Map<Integer, float[]> worldMap;
        if (currentSeqIdx >= 0 && animData.hasAnimation()) {
            SequenceInfo seq = animData.sequences().get(currentSeqIdx);
            worldMap = BoneAnimator.computeWorldMatrices(bones, animTimeMs, seq.start(), seq.end(), animData.globalSequences());
        } else {
            worldMap = BoneAnimator.computeWorldMatrices(bones, 0, 0, 0, animData.globalSequences());
        }
        lastWorldMap = worldMap;

        Map<Integer, BoneNode> byId = new java.util.HashMap<>(bones.length * 2);
        for (BoneNode b : bones) byId.put(b.objectId(), b);

        float[] buf = new float[bones.length * 2 * 3];
        int li = 0;

        // Collect positions per type for cube drawing
        java.util.List<float[]> bonePosList   = new java.util.ArrayList<>();
        java.util.List<float[]> helperPosList  = new java.util.ArrayList<>();
        java.util.List<float[]> attachPosList  = new java.util.ArrayList<>();

        for (BoneNode bone : bones) {
            float[] pos = boneWorldPos(bone, worldMap);
            switch (bone.nodeType()) {
                case BONE       -> bonePosList.add(pos);
                case HELPER     -> helperPosList.add(pos);
                case ATTACHMENT -> attachPosList.add(pos);
            }
        }
        bonePositions   = bonePosList.toArray(new float[0][]);
        helperPositions = helperPosList.toArray(new float[0][]);
        attachPositions = attachPosList.toArray(new float[0][]);

        // Lines: bones first, then helpers, then attachments
        int boneLineVerts = 0, helperLineVerts = 0, attachLineVerts = 0;
        for (BoneNode.NodeType type : BoneNode.NodeType.values()) {
            for (BoneNode bone : bones) {
                if (bone.nodeType() != type) continue;
                if (bone.parentId() < 0) continue;
                BoneNode parent = byId.get(bone.parentId());
                if (parent == null) continue;
                float[] childPos  = boneWorldPos(bone, worldMap);
                float[] parentPos = boneWorldPos(parent, worldMap);
                buf[li++] = parentPos[0]; buf[li++] = parentPos[1]; buf[li++] = parentPos[2];
                buf[li++] = childPos[0];  buf[li++] = childPos[1];  buf[li++] = childPos[2];
                switch (type) {
                    case BONE       -> boneLineVerts += 2;
                    case HELPER     -> helperLineVerts += 2;
                    case ATTACHMENT -> attachLineVerts += 2;
                }
            }
        }

        glBindBuffer(GL_ARRAY_BUFFER, boneVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, java.util.Arrays.copyOf(buf, li));
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        return new int[]{boneLineVerts, helperLineVerts, attachLineVerts};
    }

    private void buildExtentVao() {
        updateExtentVao(mesh.minX(), mesh.minY(), mesh.minZ(),
                        mesh.maxX(), mesh.maxY(), mesh.maxZ());
    }

    private void updateExtentVao(float x0, float y0, float z0,
                                  float x1, float y1, float z1) {
        // 12 edges of a box = 24 line endpoints
        float[] lines = {
            // Bottom face (4 edges)
            x0,y0,z0, x1,y0,z0,  x1,y0,z0, x1,y1,z0,  x1,y1,z0, x0,y1,z0,  x0,y1,z0, x0,y0,z0,
            // Top face (4 edges)
            x0,y0,z1, x1,y0,z1,  x1,y0,z1, x1,y1,z1,  x1,y1,z1, x0,y1,z1,  x0,y1,z1, x0,y0,z1,
            // Vertical pillars (4 edges)
            x0,y0,z0, x0,y0,z1,  x1,y0,z0, x1,y0,z1,  x1,y1,z0, x1,y1,z1,  x0,y1,z0, x0,y1,z1,
        };
        extentLineCount = 24;
        if (extentVao == 0) {
            extentVao = glGenVertexArrays();
            extentVbo = glGenBuffers();
            glBindVertexArray(extentVao);
            glBindBuffer(GL_ARRAY_BUFFER, extentVbo);
            glBufferData(GL_ARRAY_BUFFER, lines, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
            glEnableVertexAttribArray(0);
        } else {
            glBindVertexArray(extentVao);
            glBindBuffer(GL_ARRAY_BUFFER, extentVbo);
            glBufferData(GL_ARRAY_BUFFER, lines, GL_STATIC_DRAW);
        }
        glBindVertexArray(0);
    }

    private void buildCollisionVao() {
        if (collisionShapes.length == 0) return;
        java.util.List<Float> lines = new java.util.ArrayList<>();
        for (CollisionShape cs : collisionShapes) {
            switch (cs.type()) {
                case BOX -> {
                    if (cs.vertices().length >= 2) {
                        float[] v0 = cs.vertices()[0], v1 = cs.vertices()[1];
                        addBoxLines(lines, v0[0], v0[1], v0[2], v1[0], v1[1], v1[2]);
                    }
                }
                case SPHERE -> {
                    if (cs.vertices().length >= 1) {
                        float[] c = cs.vertices()[0];
                        addSphereLines(lines, c[0], c[1], c[2], cs.boundsRadius());
                    }
                }
                case CYLINDER -> {
                    // Approximate as sphere at midpoint
                    if (cs.vertices().length >= 1) {
                        float[] c = cs.vertices()[0];
                        addSphereLines(lines, c[0], c[1], c[2], cs.boundsRadius());
                    }
                }
                case PLANE -> {} // skip
            }
        }
        if (lines.isEmpty()) return;
        float[] data = new float[lines.size()];
        for (int i = 0; i < data.length; i++) data[i] = lines.get(i);
        collisionLineCount = data.length / 3;
        collisionVao = glGenVertexArrays(); collisionVbo = glGenBuffers();
        glBindVertexArray(collisionVao);
        glBindBuffer(GL_ARRAY_BUFFER, collisionVbo);
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    private static void addBoxLines(java.util.List<Float> lines,
                                     float x0, float y0, float z0, float x1, float y1, float z1) {
        // 12 edges of a box
        float[][] corners = {
            {x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0},
            {x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}
        };
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        for (int[] e : edges) {
            addLine(lines, corners[e[0]], corners[e[1]]);
        }
    }

    private static void addSphereLines(java.util.List<Float> lines,
                                        float cx, float cy, float cz, float r) {
        // 3 circles (XY, XZ, YZ planes) with 24 segments each
        int segments = 24;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < segments; i++) {
                double a0 = 2 * Math.PI * i / segments;
                double a1 = 2 * Math.PI * (i + 1) / segments;
                float c0 = (float) Math.cos(a0) * r, s0 = (float) Math.sin(a0) * r;
                float c1 = (float) Math.cos(a1) * r, s1 = (float) Math.sin(a1) * r;
                switch (ring) {
                    case 0 -> { // XY plane
                        addLine(lines, new float[]{cx+c0,cy+s0,cz}, new float[]{cx+c1,cy+s1,cz});
                    }
                    case 1 -> { // XZ plane
                        addLine(lines, new float[]{cx+c0,cy,cz+s0}, new float[]{cx+c1,cy,cz+s1});
                    }
                    case 2 -> { // YZ plane
                        addLine(lines, new float[]{cx,cy+c0,cz+s0}, new float[]{cx,cy+c1,cz+s1});
                    }
                }
            }
        }
    }

    private static void addLine(java.util.List<Float> lines, float[] a, float[] b) {
        lines.add(a[0]); lines.add(a[1]); lines.add(a[2]);
        lines.add(b[0]); lines.add(b[1]); lines.add(b[2]);
    }

    private void buildCubeVao() {
        float s=40f;
        float[] v={-s,-s,-s,s,-s,-s,s,s,-s,-s,s,-s,-s,-s,s,s,-s,s,s,s,s,-s,s,s};
        int[]  ix={0,1,2,0,2,3,4,5,6,4,6,7,0,4,7,0,7,3,1,5,6,1,6,2,0,1,5,0,5,4,3,2,6,3,6,7};
        cubeVao=glGenVertexArrays();cubeVbo=glGenBuffers();cubeEbo=glGenBuffers();
        glBindVertexArray(cubeVao);glBindBuffer(GL_ARRAY_BUFFER,cubeVbo);glBufferData(GL_ARRAY_BUFFER,v,GL_STATIC_DRAW);
        glVertexAttribPointer(0,3,GL_FLOAT,false,12,0L);glEnableVertexAttribArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,cubeEbo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,ix,GL_STATIC_DRAW);glBindVertexArray(0);
    }

    // ── Matrix math ──────────────────────────────────────────────────────────

    static float[] buildProjection(float fov, float asp, float near, float far) {
        float f=(float)(1.0/Math.tan(Math.toRadians(fov)*0.5)), ri=1f/(near-far);
        return new float[]{f/asp,0,0,0,0,f,0,0,0,0,(near+far)*ri,-1,0,0,2*near*far*ri,0};
    }
    /** Camera orbit transform — used for the grid and fallback cube (already Y-up). */
    private float[] buildCameraView() {
        float[] m=identity();
        m=translate(m,panX,panY,-distance); m=rotateX(m,pitchDegrees); m=rotateY(m,yawDegrees);
        return m;
    }
    /** Camera orbit + Z-up→Y-up conversion + model centering/scaling. */
    private float[] buildModelView() {
        float[] m=buildCameraView();
        if(hasRenderableMesh()){
            m=rotateX(m,-90f); // WC3 Z-up → OpenGL Y-up
            m=translate(m,-mesh.centerX(),-mesh.centerY(),-mesh.centerZ());
            m=scale(m,modelScale);
        }
        return m;
    }
    static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    static float[] translate(float[] m,float tx,float ty,float tz){float[] t=identity();t[12]=tx;t[13]=ty;t[14]=tz;return matMul(m,t);}
    static float[] rotateX(float[] m,float d){float c=(float)Math.cos(Math.toRadians(d)),s=(float)Math.sin(Math.toRadians(d));float[] r=identity();r[5]=c;r[6]=s;r[9]=-s;r[10]=c;return matMul(m,r);}
    static float[] rotateY(float[] m,float d){float c=(float)Math.cos(Math.toRadians(d)),s=(float)Math.sin(Math.toRadians(d));float[] r=identity();r[0]=c;r[2]=-s;r[8]=s;r[10]=c;return matMul(m,r);}
    static float[] scale(float[] m,float s){float[] sc=identity();sc[0]=s;sc[5]=s;sc[10]=s;return matMul(m,sc);}
    static float[] matMul(float[] a,float[] b){float[] r=new float[16];for(int row=0;row<4;row++)for(int col=0;col<4;col++){float s=0;for(int k=0;k<4;k++)s+=a[row+k*4]*b[k+col*4];r[row+col*4]=s;}return r;}

    // ── Camera ───────────────────────────────────────────────────────────────

    private boolean hasRenderableMesh() {
        return mesh != null && !mesh.isEmpty()
            && Float.isFinite(mesh.radius()) && mesh.radius() > 0.0001f;
    }
    private void applyInitialCameraDistance() {
        if (hasRenderableMesh()) {
            float r = computeVertexRadius();
            modelScale = clamp(120f / r, 0.005f, 500f);
            distance = 420f;
            panY = -20f;
        } else {
            modelScale = 1f; distance = 300f; panY = -30f;
        }
    }

    /** Reframe the camera to fit the given sequence's bounding volume, resetting angles. */
    public void reframeToSequence(SequenceInfo seq) {
        if (!hasRenderableMesh()) return;
        float r;
        if (seq != null && seq.hasExtent() && seq.extentRadius() > 0.001f) {
            r = Math.max(30f, seq.extentRadius());
        } else {
            r = computeVertexRadius();
        }
        modelScale = clamp(120f / r, 0.005f, 500f);
        distance = 420f;
        panX = 0f;
        panY = -20f;
        yawDegrees = initialYaw;
        pitchDegrees = initialPitch;
    }

    /** Compute bounding radius from actual vertex positions for tighter camera framing. */
    private float computeVertexRadius() {
        float[] verts = mesh.vertices();
        if (verts.length < 3) return Math.max(30f, mesh.radius());
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < verts.length; i += 3) {
            minX = Math.min(minX, verts[i]);     maxX = Math.max(maxX, verts[i]);
            minY = Math.min(minY, verts[i + 1]); maxY = Math.max(maxY, verts[i + 1]);
            minZ = Math.min(minZ, verts[i + 2]); maxZ = Math.max(maxZ, verts[i + 2]);
        }
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return Math.max(30f, (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    private void installInputHandlers() {
        java.awt.event.MouseAdapter a = new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                requestFocusInWindow(); lastMouseX=e.getX(); lastMouseY=e.getY();
                draggingOrbit=SwingUtilities.isLeftMouseButton(e)&&!e.isShiftDown();
                draggingPan=SwingUtilities.isRightMouseButton(e)||(SwingUtilities.isLeftMouseButton(e)&&e.isShiftDown());
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e){draggingOrbit=false;draggingPan=false;}
            @Override public void mouseDragged(java.awt.event.MouseEvent e){
                int dx=e.getX()-lastMouseX,dy=e.getY()-lastMouseY;lastMouseX=e.getX();lastMouseY=e.getY();
                if(draggingOrbit){yawDegrees+=dx*0.5f;pitchDegrees=clamp(pitchDegrees+dy*0.4f,-89,89);}
                else if(draggingPan){float sc=Math.max(0.05f,distance/900f);panX+=dx*sc;panY-=dy*sc;}
            }
            @Override public void mouseClicked(java.awt.event.MouseEvent e){if(SwingUtilities.isRightMouseButton(e)&&e.getClickCount()==2)resetCamera();}
        };
        addMouseListener(a); addMouseMotionListener(a);
        addMouseWheelListener(e->{distance=clamp(distance*(e.getWheelRotation()>0?1.08f:0.92f),MIN_DISTANCE,MAX_DISTANCE);});
    }
    /** Sets the default camera angles used on reset and initial view. */
    public void setInitialCamera(float yaw, float pitch) {
        this.initialYaw = yaw;
        this.initialPitch = pitch;
        this.yawDegrees = yaw;
        this.pitchDegrees = pitch;
    }

    private void resetCamera(){yawDegrees=initialYaw;pitchDegrees=initialPitch;panX=0f;applyInitialCameraDistance();}
    static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
