package org.example.ui;

import org.example.model.*;
import org.example.parser.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;

/**
 * Model viewer dialog with a split-pane layout:
 *   Left  – OpenGL 3D preview canvas
 *   Right – tabbed panel (Animation, Info, Textures, Materials)
 *
 * Layout reproduces the structure of War3AdvancedModelViewer's ModelDetailDialog.
 */
public final class ModelViewerDialog extends JDialog {
    private static final int VIEW_W = 800;
    private static final int DIAG_W = 360;

    private final GlPreviewCanvas previewCanvas;
    private final ReterasParsedModel parsedModel;
    private final ModelAsset asset;
    private final Path scanRoot;

    public ModelViewerDialog(JFrame owner, ModelAsset asset, Path scanRoot) {
        super(owner, "Model Viewer – " + asset.fileName(), false);
        this.asset = asset;
        this.scanRoot = scanRoot;
        setSize(new Dimension(VIEW_W + DIAG_W, 700));
        setMinimumSize(new Dimension(800, 600));
        setLayout(new BorderLayout());

        ReterasModelParser.invalidate(asset.path()); // ensure fresh parse with latest extractor
        parsedModel = ReterasModelParser.parse(asset.path());

        // ── GL canvas ────────────────────────────────────────────────────
        GlPreviewCanvas createdCanvas;
        try {
            createdCanvas = new GlPreviewCanvas(
                    parsedModel.mesh(), parsedModel.animData(),
                    parsedModel.texData(), parsedModel.collisionShapes(),
                    asset.path().getParent(), scanRoot);
        } catch (Throwable t) {
            System.err.println("[ModelViewer] GlPreviewCanvas failed: " + t);
            t.printStackTrace();
            createdCanvas = null;
        }
        if (createdCanvas != null) {
            AppSettings s = AppSettings.loadDefault();
            createdCanvas.setInitialCamera(s.cameraYaw(), s.cameraPitch());
        }
        this.previewCanvas = createdCanvas;

        // ── Safe close (background GL-Stopper thread) ────────────────────
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (previewCanvas == null) { dispose(); return; }
                new Thread(() -> {
                    previewCanvas.stopRenderThread();
                    SwingUtilities.invokeLater(() -> dispose());
                }, "GL-Stopper").start();
            }
        });

        // ── Build layout ─────────────────────────────────────────────────
        JPanel leftPanel = buildLeftPanel();
        JTabbedPane rightTabs = buildRightTabs();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightTabs);
        splitPane.setDividerLocation(VIEW_W);
        splitPane.setResizeWeight(1.0); // extra space goes to the 3D view
        splitPane.setOneTouchExpandable(true);
        add(splitPane, BorderLayout.CENTER);

        // Auto-select first sequence
        if (previewCanvas != null) {
            // Apply background color from settings
            AppSettings s = AppSettings.loadDefault();
            previewCanvas.setBackgroundColor(s.bgColor());
            if (parsedModel.animData().hasAnimation()) {
                previewCanvas.setSequence(0);
                previewCanvas.setPlaying(true);
                previewCanvas.reframeToSequence(parsedModel.animData().sequences().get(0));
            }
        }
    }

    // ── Left panel: GL canvas with fallback ──────────────────────────────

    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setMinimumSize(new Dimension(400, 300));
        if (previewCanvas != null) {
            // JLayeredPane with manual sizing so GL canvas + overlays stack correctly
            // Stats HUD overlay
            JLabel statsLabel = buildStatsHud();

            // JLayeredPane with doLayout() override to reliably size children
            JLayeredPane layered = new JLayeredPane() {
                @Override
                public void doLayout() {
                    int w = getWidth(), h = getHeight();
                    previewCanvas.setBounds(0, 0, w, h);
                    statsLabel.setBounds(8, 8, 150, 95);
                }
            };

            layered.add(previewCanvas, JLayeredPane.DEFAULT_LAYER);
            layered.add(statsLabel, JLayeredPane.PALETTE_LAYER);

            layered.setPreferredSize(new Dimension(VIEW_W, 600));
            panel.add(layered, BorderLayout.CENTER);
        } else {
            JLabel fallback = new JLabel(
                    "<html><center>OpenGL preview unavailable for <b>" + asset.fileName() + "</b><br>"
                            + "Check the console for details.</center></html>",
                    JLabel.CENTER);
            fallback.setForeground(new Color(200, 80, 80));
            fallback.setBorder(new EmptyBorder(12, 12, 12, 12));
            panel.add(fallback, BorderLayout.CENTER);
        }
        return panel;
    }

    private JLabel buildStatsHud() {
        NumberFormat fmt = NumberFormat.getIntegerInstance();
        ModelMesh mesh = parsedModel.mesh();
        ModelAnimData animData = parsedModel.animData();

        int verts = mesh.vertices().length / 3;
        int tris = mesh.indices().length / 3;
        int bones = animData.bones().length;
        int geosets = (int) animData.geosets().stream().filter(g -> g.vertexCount() > 0).count();
        int seqs = animData.sequences().size();

        String html = "<html><body style='font-family:monospace; font-size:10px; color:#ccddee;'>"
                + "Geosets: " + geosets + "<br>"
                + "Vertices: " + fmt.format(verts) + "<br>"
                + "Triangles: " + fmt.format(tris) + "<br>"
                + "Bones: " + bones + "<br>"
                + "Sequences: " + seqs
                + "</body></html>";

        JLabel label = new JLabel(html);
        label.setOpaque(true);
        label.setBackground(new Color(0, 0, 0, 140));
        label.setBorder(new EmptyBorder(6, 10, 6, 10));
        label.setBounds(8, 8, 150, 95);
        return label;
    }

    // ── Right panel: tabbed pane ─────────────────────────────────────────

    private JTabbedPane buildRightTabs() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setMinimumSize(new Dimension(280, 0));
        tabs.setPreferredSize(new Dimension(DIAG_W, 0));

        tabs.addTab("Animation", buildAnimationTab());
        tabs.addTab("Info", buildInfoTab());
        tabs.addTab("Textures", buildTexturesTab());
        tabs.addTab("Materials", buildMaterialsTab());

        return tabs;
    }

    // ── Animation tab ────────────────────────────────────────────────────

    private JPanel buildAnimationTab() {
        ModelAnimData animData = parsedModel.animData();
        List<SequenceInfo> sequences = animData.sequences();

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Sequence selector
        JPanel seqRow = flowRow("Animation:");
        JComboBox<String> seqCombo = buildSequenceCombo(sequences);
        seqCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        seqRow.add(seqCombo);
        panel.add(seqRow);
        panel.add(Box.createVerticalStrut(8));

        // Play / Pause + Loop
        JPanel playRow = flowRow();
        JButton playPauseBtn = new JButton("Stop");
        playPauseBtn.setEnabled(animData.hasAnimation());
        playPauseBtn.addActionListener(e -> {
            if (previewCanvas == null) return;
            boolean nowPlaying = !previewCanvas.isPlaying();
            previewCanvas.setPlaying(nowPlaying);
            playPauseBtn.setText(nowPlaying ? "Stop" : "Play");
        });
        JCheckBox loopCheckbox = new JCheckBox("Loop", true);
        loopCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setLooping(loopCheckbox.isSelected());
        });
        // Reset button to "Play" when a non-looping animation finishes
        previewCanvas.setOnAnimationFinished(() -> {
            playPauseBtn.setText("Play");
        });
        JButton recenterBtn = new JButton("Recenter");
        recenterBtn.addActionListener(e -> {
            if (previewCanvas == null) return;
            int idx = seqCombo.getSelectedIndex();
            SequenceInfo seq = (idx >= 0 && idx < sequences.size()) ? sequences.get(idx) : null;
            previewCanvas.reframeToSequence(seq);
        });
        playRow.add(playPauseBtn);
        playRow.add(loopCheckbox);
        playRow.add(recenterBtn);
        panel.add(playRow);
        panel.add(Box.createVerticalStrut(8));

        // Speed slider
        JPanel speedRow = flowRow("Speed:");
        JLabel speedLabel = new JLabel("1.0x");
        speedLabel.setPreferredSize(new Dimension(40, 20));
        JSlider speedSlider = new JSlider(10, 300, 100);
        speedSlider.setPreferredSize(new Dimension(160, 24));
        speedSlider.setEnabled(animData.hasAnimation());
        speedSlider.addChangeListener(e -> {
            float spd = speedSlider.getValue() / 100f;
            speedLabel.setText(String.format("%.1fx", spd));
            if (previewCanvas != null) previewCanvas.setSpeed(spd);
        });
        speedRow.add(speedSlider);
        speedRow.add(speedLabel);
        panel.add(speedRow);
        panel.add(Box.createVerticalStrut(12));

        // Separator
        panel.add(new JSeparator(SwingConstants.HORIZONTAL));
        panel.add(Box.createVerticalStrut(8));

        // Shading mode
        JPanel shadingRow = flowRow("Shading:");
        JComboBox<String> shadingCombo = new JComboBox<>(new String[]{"Solid", "Textured", "Lit", "Wireframe"});
        shadingCombo.setSelectedIndex(1);
        shadingCombo.addActionListener(e -> {
            if (previewCanvas == null) return;
            int idx = shadingCombo.getSelectedIndex();
            if (idx == 3) {
                previewCanvas.setShadingMode(GlPreviewCanvas.ShadingMode.SOLID);
                previewCanvas.setWireframe(true);
            } else {
                previewCanvas.setWireframe(false);
                GlPreviewCanvas.ShadingMode mode = switch (idx) {
                    case 0  -> GlPreviewCanvas.ShadingMode.SOLID;
                    case 2  -> GlPreviewCanvas.ShadingMode.LIT;
                    default -> GlPreviewCanvas.ShadingMode.TEXTURED;
                };
                previewCanvas.setShadingMode(mode);
            }
        });
        shadingRow.add(shadingCombo);
        panel.add(shadingRow);
        panel.add(Box.createVerticalStrut(6));

        // Extent overlay
        JCheckBox extentCheckbox = new JCheckBox("Show Extent");
        extentCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        extentCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowExtent(extentCheckbox.isSelected());
        });
        panel.add(extentCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Node overlays
        JCheckBox bonesCheckbox = new JCheckBox("Bones");
        bonesCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        bonesCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowBones(bonesCheckbox.isSelected());
        });
        panel.add(bonesCheckbox);
        panel.add(Box.createVerticalStrut(4));

        JCheckBox helpersCheckbox = new JCheckBox("Helpers");
        helpersCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        helpersCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowHelpers(helpersCheckbox.isSelected());
        });
        panel.add(helpersCheckbox);
        panel.add(Box.createVerticalStrut(4));

        JCheckBox attachmentsCheckbox = new JCheckBox("Attachments");
        attachmentsCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        attachmentsCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowAttachments(attachmentsCheckbox.isSelected());
        });
        panel.add(attachmentsCheckbox);
        panel.add(Box.createVerticalStrut(4));

        JCheckBox nodeNamesCheckbox = new JCheckBox("Node Names");
        nodeNamesCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        nodeNamesCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowNodeNames(nodeNamesCheckbox.isSelected());
        });
        panel.add(nodeNamesCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Node size slider
        JPanel nodeSizeRow = flowRow("Node Size:");
        JLabel nodeSizeLabel = new JLabel("3.0");
        nodeSizeLabel.setPreferredSize(new Dimension(30, 20));
        JSlider nodeSizeSlider = new JSlider(5, 200, 30); // 0.5 to 20.0 (÷10)
        nodeSizeSlider.setPreferredSize(new Dimension(120, 24));
        nodeSizeSlider.addChangeListener(e -> {
            float sz = nodeSizeSlider.getValue() / 10f;
            nodeSizeLabel.setText(String.format("%.1f", sz));
            if (previewCanvas != null) previewCanvas.setNodeSize(sz);
        });
        nodeSizeRow.add(nodeSizeSlider);
        nodeSizeRow.add(nodeSizeLabel);
        panel.add(nodeSizeRow);
        panel.add(Box.createVerticalStrut(6));

        // Collision shapes overlay
        JCheckBox collisionCheckbox = new JCheckBox("Collision Shapes");
        collisionCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        collisionCheckbox.setEnabled(parsedModel.collisionShapes().length > 0);
        collisionCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowCollision(collisionCheckbox.isSelected());
        });
        panel.add(collisionCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Grid toggle
        JCheckBox gridCheckbox = new JCheckBox("Show Grid", true);
        gridCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        gridCheckbox.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setShowGrid(gridCheckbox.isSelected());
        });
        panel.add(gridCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Camera View checkbox
        CameraNode[] cameras = parsedModel.cameras();
        JCheckBox cameraViewCheckbox = new JCheckBox("Camera View");
        cameraViewCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
        cameraViewCheckbox.setEnabled(cameras.length > 0);
        cameraViewCheckbox.addActionListener(e -> {
            if (previewCanvas != null) {
                if (cameraViewCheckbox.isSelected() && cameras.length > 0) {
                    previewCanvas.applyCameraView(cameras[0]);
                } else {
                    previewCanvas.resetCameraView();
                }
            }
        });
        panel.add(cameraViewCheckbox);
        panel.add(Box.createVerticalStrut(6));

        // Team color selector
        JPanel tcRow = flowRow("Team Color:");
        String[] tcNames = {"Red", "Blue", "Teal", "Purple", "Yellow", "Orange",
                "Green", "Pink", "Gray", "Light Blue", "Dark Green", "Brown"};
        JComboBox<String> tcCombo = new JComboBox<>(tcNames);
        tcCombo.setSelectedIndex(0);
        tcCombo.addActionListener(e -> {
            if (previewCanvas != null) previewCanvas.setTeamColor(tcCombo.getSelectedIndex());
        });
        tcRow.add(tcCombo);
        panel.add(tcRow);

        // Wire sequence combo
        seqCombo.addActionListener(e -> {
            if (previewCanvas != null) {
                int idx = seqCombo.getSelectedIndex();
                previewCanvas.setSequence(idx);
            }
        });

        // Glue at bottom
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ── Info tab ─────────────────────────────────────────────────────────

    private JScrollPane buildInfoTab() {
        NumberFormat fmt = NumberFormat.getIntegerInstance();
        ModelMesh mesh = parsedModel.mesh();
        ModelAnimData animData = parsedModel.animData();
        ModelMetadata meta = parsedModel.metadata();

        String polygons = meta.polygonCount() >= 0
                ? fmt.format(meta.polygonCount()) : "N/A";

        String[][] rows = {
                {"File", asset.fileName()},
                {"Path", asset.path().toString()},
                {"Size", formatFileSize(asset.fileSizeBytes())},
                {"Vertices", fmt.format(mesh.vertices().length / 3)},
                {"Polygons", polygons},
                {"Bones", String.valueOf(animData.bones().length)},
                {"Geosets", String.valueOf(animData.geosets().size())},
                {"Sequences", String.valueOf(animData.sequences().size())},
                {"Textures", String.valueOf(parsedModel.texData().length)},
                {"Bounding Radius", String.format("%.2f", mesh.radius())},
        };

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        for (int r = 0; r < rows.length; r++) {
            gbc.gridx = 0; gbc.gridy = r; gbc.weightx = 0;
            JLabel key = new JLabel(rows[r][0]);
            key.setFont(key.getFont().deriveFont(Font.BOLD));
            grid.add(key, gbc);

            gbc.gridx = 1; gbc.weightx = 1.0;
            JLabel val = new JLabel(rows[r][1]);
            val.setToolTipText(rows[r][1]);
            grid.add(val, gbc);
        }

        // Push content to top
        gbc.gridx = 0; gbc.gridy = rows.length; gbc.weighty = 1.0;
        grid.add(Box.createGlue(), gbc);

        JScrollPane scroll = new JScrollPane(grid);
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        return scroll;
    }

    // ── Textures tab ─────────────────────────────────────────────────────

    private JPanel buildTexturesTab() {
        GeosetTexData[] texData = parsedModel.texData();
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel("Textures (" + texData.length + ")");
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        panel.add(header, BorderLayout.NORTH);

        DefaultTableModel tableModel = new DefaultTableModel(
                new String[]{"#", "Path", "Source"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };

        Path modelDir = asset.path().getParent();
        GameDataSource gds = GameDataSource.getInstance();

        for (int i = 0; i < texData.length; i++) {
            String path = texData[i].texturePath();
            String source;
            if (path.isEmpty()) {
                source = texData[i].replaceableId() > 0 ? "REPLACEABLE" : "NONE";
            } else {
                source = gds.resolveTextureSource(path, texData[i].replaceableId(), modelDir, scanRoot);
            }
            tableModel.addRow(new Object[]{i, path.isEmpty() ? "(no texture)" : path, source});
        }

        JTable table = new JTable(tableModel);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(2).setMaxWidth(100);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Color-coded source column
        table.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected && value instanceof String s) {
                    switch (s) {
                        case "DISK"        -> { c.setForeground(new Color(0, 140, 60));  c.setBackground(new Color(220, 255, 230)); }
                        case "CASC"        -> { c.setForeground(new Color(0, 80, 180));  c.setBackground(new Color(220, 235, 255)); }
                        case "MPQ"         -> { c.setForeground(new Color(100, 60, 160));c.setBackground(new Color(235, 225, 255)); }
                        case "REPLACEABLE" -> { c.setForeground(new Color(160, 120, 0)); c.setBackground(new Color(255, 245, 210)); }
                        case "MISSING"     -> { c.setForeground(new Color(180, 40, 40)); c.setBackground(new Color(255, 225, 225)); }
                        default            -> { c.setForeground(t.getForeground()); c.setBackground(t.getBackground()); }
                    }
                }
                return c;
            }
        });

        // Double-click to show texture popup, right-click to copy path
        javax.swing.JPopupMenu texPopup = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem copyPathItem = new javax.swing.JMenuItem("Copy Path");
        copyPathItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                Object val = table.getValueAt(row, 1);
                if (val != null) {
                    java.awt.datatransfer.StringSelection sel =
                            new java.awt.datatransfer.StringSelection(val.toString());
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                }
            }
        });
        texPopup.add(copyPathItem);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < texData.length) {
                        showTexturePopup(row);
                    }
                }
            }
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    table.setRowSelectionInterval(row, row);
                    texPopup.show(table, e.getX(), e.getY());
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        panel.add(scroll, BorderLayout.CENTER);

        // Texture preview below the table
        JLabel previewLabel = new JLabel();
        previewLabel.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        previewLabel.setPreferredSize(new Dimension(0, 180));
        previewLabel.setBorder(new EmptyBorder(4, 0, 0, 0));
        panel.add(previewLabel, BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row < 0 || row >= texData.length) {
                previewLabel.setIcon(null);
                previewLabel.setText("");
                return;
            }
            String texPath = texData[row].texturePath();
            if (texPath.isEmpty()) {
                previewLabel.setIcon(null);
                previewLabel.setText("(no texture)");
                return;
            }
            BufferedImage img = GameDataSource.getInstance().loadTexture(texPath, modelDir, scanRoot);
            if (img != null) {
                int maxH = 170;
                int w = img.getWidth(), h = img.getHeight();
                if (h > maxH) { w = w * maxH / h; h = maxH; }
                previewLabel.setIcon(new javax.swing.ImageIcon(
                        img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH)));
                previewLabel.setText("");
            } else {
                previewLabel.setIcon(null);
                previewLabel.setText("Texture not found");
            }
        });

        return panel;
    }

    // ── Materials tab ────────────────────────────────────────────────────

    private JPanel buildMaterialsTab() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel("Geoset Materials");
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(new EmptyBorder(0, 0, 6, 0));
        panel.add(header, BorderLayout.NORTH);

        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        StringBuilder sb = new StringBuilder();
        GeosetTexData[] texData = parsedModel.texData();
        ModelAnimData animData = parsedModel.animData();
        NumberFormat fmt = NumberFormat.getIntegerInstance();

        int vertOffset = 0;
        int gi = 0;
        for (GeosetSkinData skin : animData.geosets()) {
            int vc = skin.vertexCount();
            if (vc == 0) continue;
            if (gi >= texData.length) break;

            sb.append("─── Geoset ").append(gi).append(" ───\n");
            sb.append("  Vertices:    ").append(fmt.format(vc)).append('\n');
            sb.append("  Has UVs:     ").append(texData[gi].hasUvs() ? "Yes" : "No").append('\n');
            String texPath = texData[gi].texturePath();
            sb.append("  Texture:     ").append(texPath.isEmpty() ? "(none)" : texPath).append('\n');
            sb.append("  Filter Mode: ").append(filterModeName(texData[gi].filterMode())).append('\n');
            int replId = texData[gi].replaceableId();
            if (replId > 0) {
                sb.append("  Replaceable: ").append(replId == 1 ? "TeamColor" : replId == 2 ? "TeamGlow" : String.valueOf(replId)).append('\n');
            }
            sb.append("  Skinning:    ").append(skin.hasSkinning() ? "Yes" : "No").append('\n');
            sb.append('\n');

            vertOffset += vc;
            gi++;
        }

        if (sb.isEmpty()) {
            sb.append("No geoset data available.");
        }

        area.setText(sb.toString());
        area.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(area);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static JComboBox<String> buildSequenceCombo(List<SequenceInfo> sequences) {
        if (sequences.isEmpty()) {
            return new JComboBox<>(new String[]{"(No sequences)"});
        }
        String[] labels = sequences.stream()
                .map(SequenceInfo::displayLabel)
                .toArray(String[]::new);
        return new JComboBox<>(labels);
    }

    private static JPanel flowRow(String... labelTexts) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        for (String text : labelTexts) {
            row.add(new JLabel(text));
        }
        return row;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void showTexturePopup(int startIndex) {
        GeosetTexData[] texData = parsedModel.texData();
        if (texData.length == 0) return;

        // Collect all non-empty texture paths with their indices
        List<Integer> texIndices = new java.util.ArrayList<>();
        for (int i = 0; i < texData.length; i++) {
            if (!texData[i].texturePath().isBlank()) texIndices.add(i);
        }
        if (texIndices.isEmpty()) return;

        // Find starting position in the filtered list
        int[] currentPos = {0};
        for (int i = 0; i < texIndices.size(); i++) {
            if (texIndices.get(i) == startIndex) { currentPos[0] = i; break; }
        }

        JDialog popup = new JDialog(this, "Texture Preview", true);
        popup.setLayout(new BorderLayout(4, 4));

        // State
        boolean[] showAlpha = {false};
        boolean[] showCheckerboard = {true};

        JLabel infoLabel = new JLabel("", SwingConstants.CENTER);
        infoLabel.setBorder(new EmptyBorder(6, 12, 2, 12));
        infoLabel.setFont(infoLabel.getFont().deriveFont(Font.PLAIN, 11f));

        JLabel imgLabel = new JLabel("", SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                if (showCheckerboard[0] && getIcon() != null) {
                    drawCheckerboard(g, getWidth(), getHeight());
                }
                super.paintComponent(g);
            }
        };
        imgLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imgLabel.setBorder(new EmptyBorder(4, 12, 4, 12));

        Runnable refreshImage = () -> {
            int idx = texIndices.get(currentPos[0]);
            String texPath = texData[idx].texturePath();
            popup.setTitle("Texture – " + texPath + " (" + (currentPos[0] + 1) + "/" + texIndices.size() + ")");

            Path modelDir = asset.path().getParent();
            BufferedImage img = GameDataSource.getInstance().loadTexture(texPath, modelDir, scanRoot);
            if (img == null) {
                infoLabel.setText("Texture not found: " + texPath);
                imgLabel.setIcon(null);
                return;
            }

            BufferedImage display = showAlpha[0] ? extractAlphaChannel(img) : img;

            infoLabel.setText(String.format("%s    %d x %d    %s",
                    texPath, img.getWidth(), img.getHeight(), imageTypeName(img.getType())));

            int maxDim = 512;
            int dispW = display.getWidth(), dispH = display.getHeight();
            if (dispW > maxDim || dispH > maxDim) {
                double sc = Math.min((double) maxDim / dispW, (double) maxDim / dispH);
                dispW = (int) (dispW * sc);
                dispH = (int) (dispH * sc);
            }
            imgLabel.setIcon(new ImageIcon(display.getScaledInstance(dispW, dispH, java.awt.Image.SCALE_SMOOTH)));
            imgLabel.repaint();
        };

        // Navigation + toggles toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton prevBtn = new JButton("<");
        JButton nextBtn = new JButton(">");
        JCheckBox alphaCb = new JCheckBox("Alpha");
        JCheckBox checkerCb = new JCheckBox("Checkerboard", true);

        prevBtn.addActionListener(e -> {
            currentPos[0] = (currentPos[0] - 1 + texIndices.size()) % texIndices.size();
            refreshImage.run();
        });
        nextBtn.addActionListener(e -> {
            currentPos[0] = (currentPos[0] + 1) % texIndices.size();
            refreshImage.run();
        });
        alphaCb.addActionListener(e -> { showAlpha[0] = alphaCb.isSelected(); refreshImage.run(); });
        checkerCb.addActionListener(e -> { showCheckerboard[0] = checkerCb.isSelected(); imgLabel.repaint(); });

        prevBtn.setEnabled(texIndices.size() > 1);
        nextBtn.setEnabled(texIndices.size() > 1);
        toolbar.add(prevBtn);
        toolbar.add(nextBtn);
        toolbar.add(alphaCb);
        toolbar.add(checkerCb);

        popup.add(infoLabel, BorderLayout.NORTH);
        popup.add(imgLabel, BorderLayout.CENTER);
        popup.add(toolbar, BorderLayout.SOUTH);
        popup.setSize(new Dimension(560, 620));
        refreshImage.run();
        popup.setLocationRelativeTo(this);
        popup.setVisible(true);
    }

    /** Draws a checkerboard pattern (transparency indicator). */
    private static void drawCheckerboard(Graphics g, int w, int h) {
        int sz = 12;
        for (int y = 0; y < h; y += sz) {
            for (int x = 0; x < w; x += sz) {
                g.setColor(((x / sz + y / sz) % 2 == 0) ? new Color(204, 204, 204) : Color.WHITE);
                g.fillRect(x, y, sz, sz);
            }
        }
    }

    /** Extracts the alpha channel as a grayscale image. */
    private static BufferedImage extractAlphaChannel(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage alpha = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = (src.getRGB(x, y) >> 24) & 0xFF;
                alpha.setRGB(x, y, (a << 16) | (a << 8) | a);
            }
        }
        return alpha;
    }

    private static String filterModeName(int mode) {
        return switch (mode) {
            case 0 -> "None";
            case 1 -> "Transparent";
            case 2 -> "Blend";
            case 3 -> "Additive";
            case 4 -> "AddAlpha";
            case 5 -> "Modulate";
            case 6 -> "Modulate2x";
            default -> "Unknown (" + mode + ")";
        };
    }

    private static String imageTypeName(int type) {
        return switch (type) {
            case BufferedImage.TYPE_INT_ARGB   -> "ARGB";
            case BufferedImage.TYPE_INT_RGB    -> "RGB";
            case BufferedImage.TYPE_4BYTE_ABGR -> "4B ABGR";
            case BufferedImage.TYPE_3BYTE_BGR  -> "3B BGR";
            case BufferedImage.TYPE_BYTE_INDEXED -> "Indexed";
            default -> "Type " + type;
        };
    }
}
