package org.example.ui;

import org.example.model.*;
import org.example.parser.*;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.NumberFormat;

import java.util.ArrayList;
import java.util.Collections;

import java.util.List;
import java.util.Locale;

import java.util.stream.Collectors;

public final class MainWindow extends JFrame {
    /** Shared last-used directory for all file choosers in the app. */
    static java.io.File lastChooserDir;
    private MapArchiveSource currentMapSource;

    private final JTextField rootField = new JTextField();
    private final JTextField searchField = new JTextField();
    private final JComboBox<PortraitFilter> portraitFilterCombo = new JComboBox<>(PortraitFilter.values());
    private final JComboBox<ThumbnailSize> thumbnailSizeCombo = new JComboBox<>(ThumbnailSize.values());
    private final JComboBox<String> thumbnailTeamColorCombo = new JComboBox<>(TeamColorOptions.labels());
    private final JButton browseButton = new JButton("Browse");
    private final JButton scanButton = new JButton("Scan");
    private final JButton refreshIndexButton = new JButton("Rescan");
    private final JButton settingsButton = new JButton("Settings…");
    private final JToggleButton favoritesToggle = new JToggleButton("\u2606 Favorites");
    private final JButton recentButton = new JButton("Recent");
    private final JComboBox<SortOrder> sortCombo = new JComboBox<>(SortOrder.values());
    private final JToggleButton advancedFiltersToggle = new JToggleButton("Advanced Filters");
    private final JPanel advancedFiltersPanel = new JPanel(new GridLayout(2, 3, 8, 6));
    private final JTextField animationFilterField = new JTextField();
    private final JTextField textureFilterField = new JTextField();
    private final JTextField minPolygonsField = new JTextField();
    private final JTextField maxPolygonsField = new JTextField();
    private final JTextField minSizeKbField = new JTextField();
    private final JTextField maxSizeKbField = new JTextField();
    private final JLabel statusLabel = new JLabel("Choose a directory to start");
    private final DefaultListModel<ModelAsset> listModel = new DefaultListModel<>();
    private final JList<ModelAsset> assetList = new JList<>(listModel);
    private final List<ModelAsset> allAssets = new ArrayList<>();
    private final AppSettings settings = AppSettings.loadDefault();
    private Timer progressiveLoadTimer;
    private ThumbnailRenderer thumbnailRenderer;
    private Timer shimmerTimer;
    private int pendingThumbnails;
    private int totalThumbnails;

    public MainWindow() {
        super("WC3 Model Explorer (Swing)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        buildUi();
        wireEvents();
        restoreSettings();
        setSize(new Dimension(1000, 700));
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (thumbnailRenderer != null) thumbnailRenderer.shutdown();
                if (currentMapSource != null) {
                    currentMapSource.close();
                    currentMapSource = null;
                }
                GameDataSource.getInstance().clearMapSource();
            }
        });
    }

    private void buildUi() {
        JPanel rootPanel = new JPanel(new BorderLayout(10, 10));
        rootPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        JPanel browseRow = new JPanel(new BorderLayout(8, 8));
        browseRow.add(rootField, BorderLayout.CENTER);

        JPanel browseButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        browseButtons.add(recentButton);
        browseButtons.add(browseButton);
        browseButtons.add(scanButton);
        browseButtons.add(refreshIndexButton);
        browseButtons.add(settingsButton);
        browseRow.add(browseButtons, BorderLayout.EAST);
        topPanel.add(browseRow, BorderLayout.NORTH);

        JPanel filterRow = new JPanel(new BorderLayout(8, 0));
        filterRow.add(new JLabel("Search:"), BorderLayout.WEST);
        filterRow.add(searchField, BorderLayout.CENTER);
        JPanel filterControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filterControls.add(new JLabel("Sort:"));
        filterControls.add(sortCombo);
        filterControls.add(thumbnailSizeCombo);
        filterControls.add(new JLabel("Team:"));
        filterControls.add(thumbnailTeamColorCombo);
        filterControls.add(portraitFilterCombo);
        filterControls.add(favoritesToggle);
        filterControls.add(advancedFiltersToggle);
        filterRow.add(filterControls, BorderLayout.EAST);
        topPanel.add(filterRow, BorderLayout.SOUTH);
        buildAdvancedFiltersPanel();

        assetList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        assetList.setCellRenderer(new AssetCellRenderer());
        assetList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        assetList.setVisibleRowCount(-1);
        assetList.setDragEnabled(true);
        assetList.setTransferHandler(new AssetFileTransferHandler());
        JScrollPane listScrollPane = new JScrollPane(assetList);

        JPanel topContainer = new JPanel(new BorderLayout(0, 8));
        topContainer.add(topPanel, BorderLayout.NORTH);
        topContainer.add(advancedFiltersPanel, BorderLayout.CENTER);

        rootPanel.add(topContainer, BorderLayout.NORTH);
        rootPanel.add(listScrollPane, BorderLayout.CENTER);
        rootPanel.add(statusLabel, BorderLayout.SOUTH);
        setContentPane(rootPanel);
    }

    private void wireEvents() {
        browseButton.addActionListener(event -> chooseDirectory());
        scanButton.addActionListener(event -> startScan(false));
        refreshIndexButton.addActionListener(event -> startScan(true));
        settingsButton.addActionListener(event -> openSettings());
        recentButton.addActionListener(event -> showRecentModelsPopup());
        favoritesToggle.addActionListener(event -> applyFilter());
        rootField.addActionListener(event -> startScan(false));
        advancedFiltersToggle.addActionListener(event -> {
            advancedFiltersPanel.setVisible(advancedFiltersToggle.isSelected());
            revalidate();
            repaint();
        });
        sortCombo.addActionListener(event -> applyFilter());
        portraitFilterCombo.addActionListener(event -> {
            settings.setPortraitFilter((PortraitFilter) portraitFilterCombo.getSelectedItem());
            settings.save();
            applyFilter();
        });
        thumbnailSizeCombo.addActionListener(event -> {
            settings.setThumbnailSize((ThumbnailSize) thumbnailSizeCombo.getSelectedItem());
            settings.save();
            updateCardSizing();
            applyFilter();
        });
        thumbnailTeamColorCombo.addActionListener(event -> {
            int teamColor = thumbnailTeamColorCombo.getSelectedIndex();
            settings.setThumbnailTeamColor(teamColor);
            settings.save();
            if (thumbnailRenderer != null) {
                thumbnailRenderer.setTeamColor(teamColor);
            }
            applyFilter();
        });
        wireFieldForFiltering(animationFilterField);
        wireFieldForFiltering(textureFilterField);
        wireFieldForFiltering(minPolygonsField);
        wireFieldForFiltering(maxPolygonsField);
        wireFieldForFiltering(minSizeKbField);
        wireFieldForFiltering(maxSizeKbField);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilter();
            }
        });

        assetList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && assetList.getSelectedValue() != null) {
                    showModelDetails(assetList.getSelectedValue());
                }
            }
            @Override
            public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int idx = assetList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                if (!assetList.isSelectedIndex(idx)) {
                    assetList.setSelectedIndex(idx);
                }
                List<ModelAsset> selectedAssets = selectedAssets();
                if (selectedAssets.isEmpty()) return;
                showAssetContextMenu(selectedAssets, e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.addChoosableFileFilter(
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "WC3 Maps (*.w3x, *.w3m)", "w3x", "w3m"));
        String rootText = rootField.getText().trim();
        if (!rootText.isEmpty()) {
            try {
                Path rootPath = Path.of(rootText);
                // For map files, start in the parent directory
                if (java.nio.file.Files.isRegularFile(rootPath)) {
                    chooser.setCurrentDirectory(rootPath.getParent().toFile());
                } else {
                    chooser.setCurrentDirectory(rootPath.toFile());
                }
            } catch (InvalidPathException ignored) {}
        } else if (lastChooserDir != null) {
            chooser.setCurrentDirectory(lastChooserDir);
        }
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            lastChooserDir = selected.isDirectory() ? selected : selected.getParentFile();
            rootField.setText(selected.toPath().toString());
            saveCurrentRootDirectory();
            startScan(false);
        }
    }

    private void startScan(boolean forceRefresh) {
        String rootText = rootField.getText().trim();
        if (rootText.isEmpty()) {
            statusLabel.setText("Please choose a directory or map file");
            return;
        }

        final Path root;
        try {
            root = Path.of(rootText);
        } catch (InvalidPathException ex) {
            statusLabel.setText("Invalid path");
            return;
        }
        saveCurrentRootDirectory();

        statusLabel.setText("Scanning...");
        scanButton.setEnabled(false);
        refreshIndexButton.setEnabled(false);
        browseButton.setEnabled(false);
        listModel.clear();
        allAssets.clear();
        stopProgressiveLoader();
        if (thumbnailRenderer != null) {
            applySettingsToThumbnailRenderer();
            if (forceRefresh) thumbnailRenderer.clearCache();
        }

        final boolean isMap = MapArchiveSource.isMapFile(root);

        SwingWorker<List<ModelAsset>, String> worker = new SwingWorker<>() {
            @Override
            protected List<ModelAsset> doInBackground() throws Exception {
                if (isMap) {
                    publish("Opening map archive...");
                    // Close previous map source
                    if (currentMapSource != null) {
                        currentMapSource.close();
                        currentMapSource = null;
                    }
                    GameDataSource.getInstance().clearMapSource();

                    MapArchiveSource mapSource = MapArchiveSource.open(root);
                    currentMapSource = mapSource;
                    GameDataSource.getInstance().setMapSource(mapSource.getDataSource());

                    return ModelScanner.scan(mapSource.getTempDir(), true, (done, total) -> {
                        if (done % 5 == 0 || done == total) {
                            publish("Parsing models... " + done + " / " + total);
                        }
                    });
                } else {
                    // Directory mode — clear any previous map source
                    if (currentMapSource != null) {
                        currentMapSource.close();
                        currentMapSource = null;
                    }
                    GameDataSource.getInstance().clearMapSource();

                    return ModelScanner.scan(root, forceRefresh, (done, total) -> {
                        if (done % 5 == 0 || done == total) {
                            publish("Parsing models... " + done + " / " + total);
                        }
                    });
                }
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                scanButton.setEnabled(true);
                refreshIndexButton.setEnabled(true);
                browseButton.setEnabled(true);
                try {
                    allAssets.addAll(get());
                    applyFilter();
                } catch (Exception ex) {
                    statusLabel.setText("Scan failed");
                    showErrorDialog(MainWindow.this,
                            "Failed to scan:\n" + ex.getMessage(), "Scan Error");
                }
            }
        };
        worker.execute();
    }

    private void applyFilter() {
        String needle = searchField.getText().trim().toLowerCase(Locale.ROOT);
        PortraitFilter portraitFilter = (PortraitFilter) portraitFilterCombo.getSelectedItem();
        if (portraitFilter == null) {
            portraitFilter = PortraitFilter.MODELS_ONLY;
        }
        String animationNeedle = animationFilterField.getText().trim().toLowerCase(Locale.ROOT);
        String textureNeedle = textureFilterField.getText().trim().toLowerCase(Locale.ROOT);
        Integer minPolygons = parseNonNegativeInt(minPolygonsField.getText());
        Integer maxPolygons = parseNonNegativeInt(maxPolygonsField.getText());
        Long minSizeBytes = parseKilobytesToBytes(minSizeKbField.getText());
        Long maxSizeBytes = parseKilobytesToBytes(maxSizeKbField.getText());

        boolean showFavOnly = favoritesToggle.isSelected();
        List<ModelAsset> filtered = allAssets.stream()
                .filter(asset -> !showFavOnly || settings.isFavorite(asset.path().toAbsolutePath().toString()))
                .filter(asset -> needle.isEmpty()
                        || asset.fileName().toLowerCase(Locale.ROOT).contains(needle)
                        || asset.metadata().modelName().toLowerCase(Locale.ROOT).contains(needle))
                .filter(portraitFilter::allows)
                .filter(asset -> asset.metadata().hasAnimationContaining(animationNeedle))
                .filter(asset -> asset.metadata().hasTextureContaining(textureNeedle))
                .filter(asset -> polygonRangeMatches(asset, minPolygons, maxPolygons))
                .filter(asset -> minSizeBytes == null || asset.fileSizeBytes() >= minSizeBytes)
                .filter(asset -> maxSizeBytes == null || asset.fileSizeBytes() <= maxSizeBytes)
                .sorted(currentSortOrder().comparator())
                .collect(Collectors.toList());
        renderAssetsProgressively(filtered);
    }

    private SortOrder currentSortOrder() {
        SortOrder selected = (SortOrder) sortCombo.getSelectedItem();
        return selected != null ? selected : SortOrder.NAME_ASC;
    }

    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(this, settings);
        dialog.setVisible(true);
    }

    private void restoreSettings() {
        rootField.setText(settings.lastRootDirectory());
        portraitFilterCombo.setSelectedItem(settings.portraitFilter());
        thumbnailSizeCombo.setSelectedItem(settings.thumbnailSize());
        thumbnailTeamColorCombo.setRenderer(new TeamColorComboRenderer(thumbnailTeamColorCombo, idx -> {
            int[] rgb = GameDataSource.getInstance().loadTeamColorRgb(idx, null, currentScanRoot());
            return rgb != null ? rgb : TeamColorOptions.fallbackRgb(idx);
        }));
        thumbnailTeamColorCombo.setSelectedIndex(settings.thumbnailTeamColor());
        thumbnailTeamColorCombo.setToolTipText("Thumbnail team color");
        updateCardSizing();
        // Initialise data sources in background so startup is not blocked
        new Thread(() -> GameDataSource.getInstance().refresh(settings), "DataSource-Init").start();
        String rootText = rootField.getText().trim();
        if (!rootText.isEmpty()) {
            startScan(false);
        }
    }

    private void saveCurrentRootDirectory() {
        settings.setLastRootDirectory(rootField.getText().trim());
        settings.save();
        thumbnailTeamColorCombo.repaint();
    }

    private Path currentScanRoot() {
        if (currentMapSource != null) return currentMapSource.getTempDir();
        String rootText = rootField.getText().trim();
        if (rootText.isEmpty()) {
            return null;
        }
        try {
            return Path.of(rootText);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private void showAssetContextMenu(List<ModelAsset> assets, java.awt.Component comp, int x, int y) {
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        boolean multiple = assets.size() > 1;

        // Copy path
        javax.swing.JMenuItem copyPathItem = new javax.swing.JMenuItem(multiple ? "Copy Paths" : "Copy Path");
        copyPathItem.addActionListener(e -> {
            String path = assets.stream()
                    .map(a -> a.path().toAbsolutePath().toString())
                    .collect(Collectors.joining(System.lineSeparator()));
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(path), null);
        });
        popup.add(copyPathItem);

        javax.swing.JMenuItem copyFileItem = new javax.swing.JMenuItem(multiple ? "Copy Files" : "Copy File");
        copyFileItem.addActionListener(e -> copyAssetFilesToClipboard(assets));
        popup.add(copyFileItem);

        // Open file location
        if (!multiple) {
            JMenuItem openLocItem = new JMenuItem("Open File Location");
            openLocItem.addActionListener(e -> {
                try {
                    java.awt.Desktop.getDesktop().open(assets.get(0).path().getParent().toFile());
                } catch (Exception ex) {
                    // Fallback for systems where Desktop.open doesn't work
                    try {
                        new ProcessBuilder("explorer", "/select,", assets.get(0).path().toAbsolutePath().toString()).start();
                    } catch (Exception ignored) {}
                }
            });
            popup.add(openLocItem);
        }

        // Toggle favorite
        if (!multiple) {
            ModelAsset singleAsset = assets.get(0);
            String absPath = singleAsset.path().toAbsolutePath().toString();
            boolean isFav = settings.isFavorite(absPath);
            JMenuItem favItem = new JMenuItem(isFav ? "\u2605 Remove Favorite" : "\u2606 Add Favorite");
            favItem.addActionListener(e -> {
                settings.toggleFavorite(absPath);
                settings.save();
                assetList.repaint();
                if (favoritesToggle.isSelected()) applyFilter();
            });
            popup.add(favItem);
            popup.addSeparator();
        }

        // External programs
        List<ExternalProgram> programs = settings.externalPrograms();
        if (!multiple && !programs.isEmpty()) {
            ModelAsset asset = assets.get(0);
            popup.addSeparator();
            if (programs.size() == 1) {
                ExternalProgram p = programs.get(0);
                javax.swing.JMenuItem item = new javax.swing.JMenuItem("Open in " + p.name());
                item.addActionListener(e -> openInExternalProgram(p, asset));
                popup.add(item);
            } else {
                javax.swing.JMenu submenu = new javax.swing.JMenu("Open in…");
                for (ExternalProgram p : programs) {
                    javax.swing.JMenuItem item = new javax.swing.JMenuItem(p.name());
                    item.addActionListener(e -> openInExternalProgram(p, asset));
                    submenu.add(item);
                }
                popup.add(submenu);
            }
        }
        popup.show(comp, x, y);
    }

    private void openInExternalProgram(ExternalProgram program, ModelAsset asset) {
        try {
            String modelPath = asset.path().toAbsolutePath().toString();
            String cmd = program.command();
            String expanded = cmd.contains("{file}")
                    ? cmd.replace("{file}", modelPath)
                    : cmd + " \"" + modelPath + "\"";
            List<String> args = parseCommand(expanded);
            // Auto-detect .jar files and prepend "java -jar"
            if (!args.isEmpty() && args.get(0).toLowerCase(Locale.ROOT).endsWith(".jar")) {
                args.add(0, "-jar");
                args.add(0, "java");
            }
            new ProcessBuilder(args).start();
        } catch (Exception ex) {
            showErrorDialog(this,
                    "Failed to launch " + program.name() + ":\n" + ex.getMessage(), "Error");
        }
    }

    private void copyAssetFilesToClipboard(List<ModelAsset> assets) {
        List<File> files = assets.stream()
                .map(asset -> asset.path().toFile())
                .toList();
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new FileListTransferable(files), null);
    }

    private List<ModelAsset> selectedAssets() {
        List<ModelAsset> selected = assetList.getSelectedValuesList();
        return selected.isEmpty() ? Collections.emptyList() : selected;
    }

    /** Splits a command string into tokens, respecting double-quoted segments. */
    private static List<String> parseCommand(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }

    private final class AssetFileTransferHandler extends TransferHandler {
        @Override
        protected Transferable createTransferable(JComponent c) {
            List<ModelAsset> assets = selectedAssets();
            if (assets.isEmpty()) {
                return null;
            }
            return new FileListTransferable(assets.stream()
                    .map(asset -> asset.path().toFile())
                    .toList());
        }

        @Override
        public int getSourceActions(JComponent c) {
            return COPY;
        }
    }

    private static final class FileListTransferable implements Transferable {
        private final List<File> files;

        private FileListTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }

    private void showModelDetails(ModelAsset asset) {
        Path scanRoot = currentScanRoot();
        // Record as recent (skip temp paths from map archives)
        if (currentMapSource == null) {
            settings.addRecentModel(asset.path().toAbsolutePath().toString());
            settings.save();
        }

        ModelViewerDialog dialog = new ModelViewerDialog(this, asset, scanRoot);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showRecentModelsPopup() {
        List<String> recent = settings.recentModels();
        if (recent.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No recent models yet.", "Recent Models", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JPopupMenu popup = new JPopupMenu();
        for (String absPath : recent) {
            Path p = Path.of(absPath);
            String name = p.getFileName().toString();
            JMenuItem item = new JMenuItem(name);
            item.setToolTipText(absPath);
            item.addActionListener(e -> {
                try {
                    if (!java.nio.file.Files.exists(p)) {
                        showErrorDialog(this, "File not found:\n" + absPath, "Error");
                        return;
                    }
                    long size = java.nio.file.Files.size(p);
                    ModelMetadata meta = ModelMetadataExtractor.extract(p);
                    ModelAsset asset = new ModelAsset(p, size, meta);
                    showModelDetails(asset);
                } catch (Exception ex) {
                    showErrorDialog(this, "Failed to open model:\n" + ex.getMessage(), "Error");
                }
            });
            popup.add(item);
        }
        popup.show(recentButton, 0, recentButton.getHeight());
    }

    private void renderAssetsProgressively(List<ModelAsset> filtered) {
        stopProgressiveLoader();
        listModel.clear();
        if (filtered.isEmpty()) {
            statusLabel.setText(String.format("Showing 0 / %d model files", allAssets.size()));
            return;
        }

        // Ensure thumbnail renderer exists
        ensureThumbnailRenderer();
        thumbnailRenderer.cancelPending();

        final Path root = currentScanRoot();

        pendingThumbnails = 0;
        totalThumbnails = 0;
        final int batchSize = 3;
        final int[] index = {0};
        progressiveLoadTimer = new Timer(16, event -> {
            int remaining = filtered.size() - index[0];
            int toAdd = Math.min(batchSize, remaining);
            for (int i = 0; i < toAdd; i++) {
                ModelAsset asset = filtered.get(index[0]++);
                listModel.addElement(asset);
                // Queue thumbnail if not cached
                if (thumbnailRenderer.getThumbnail(asset.path()) == null) {
                    pendingThumbnails++;
                    totalThumbnails++;
                    thumbnailRenderer.request(asset, root, img -> {
                        pendingThumbnails--;
                        assetList.repaint();
                        updateThumbnailProgress();
                        if (pendingThumbnails <= 0) stopShimmerTimer();
                    });
                }
            }
            updateStatusLabel();
            if (index[0] >= filtered.size()) {
                stopProgressiveLoader();
            }
        });
        progressiveLoadTimer.setRepeats(true);
        progressiveLoadTimer.start();

        // Start shimmer repaint timer for loading animation
        startShimmerTimer();
    }

    private void ensureThumbnailRenderer() {
        if (thumbnailRenderer != null) return;
        thumbnailRenderer = new ThumbnailRenderer();
        applySettingsToThumbnailRenderer();
    }

    private void applySettingsToThumbnailRenderer() {
        if (thumbnailRenderer == null) return;
        String bgHex = settings.bgColor();
        if (bgHex != null && bgHex.length() == 6) {
            try {
                int r = Integer.parseInt(bgHex.substring(0, 2), 16);
                int g = Integer.parseInt(bgHex.substring(2, 4), 16);
                int b = Integer.parseInt(bgHex.substring(4, 6), 16);
                thumbnailRenderer.setBackgroundColor(r, g, b);
            } catch (NumberFormatException ignored) {}
        }
        thumbnailRenderer.setCameraAngles(settings.cameraYaw(), settings.cameraPitch());
        thumbnailRenderer.setAnimationName(settings.thumbnailAnimName());
        thumbnailRenderer.setTeamColor(settings.thumbnailTeamColor());
        ThumbnailQuality quality = settings.thumbnailQuality();
        thumbnailRenderer.setQuality(quality.renderSize(), quality.thumbSize());
    }

    private void startShimmerTimer() {
        if (shimmerTimer != null && shimmerTimer.isRunning()) return;
        shimmerTimer = new Timer(33, e -> assetList.repaint()); // ~30fps for shimmer animation
        shimmerTimer.setRepeats(true);
        shimmerTimer.start();
    }

    private void stopShimmerTimer() {
        if (shimmerTimer != null && shimmerTimer.isRunning()) shimmerTimer.stop();
        updateStatusLabel();
    }

    private void updateThumbnailProgress() {
        updateStatusLabel();
    }

    private void updateStatusLabel() {
        String text = String.format("Showing %d / %d model files", listModel.getSize(), allAssets.size());
        if (totalThumbnails > 0 && pendingThumbnails > 0) {
            int done = totalThumbnails - pendingThumbnails;
            text += String.format(" — Thumbnails: %d / %d", done, totalThumbnails);
        }
        statusLabel.setText(text);
    }

    private void stopProgressiveLoader() {
        if (progressiveLoadTimer != null && progressiveLoadTimer.isRunning()) {
            progressiveLoadTimer.stop();
        }
        progressiveLoadTimer = null;
    }

    private void updateCardSizing() {
        ThumbnailSize selectedSize = (ThumbnailSize) thumbnailSizeCombo.getSelectedItem();
        if (selectedSize == null) {
            selectedSize = ThumbnailSize.MEDIUM;
        }
        int cardSize = selectedSize.cardSize();
        assetList.setFixedCellWidth(cardSize + 36);
        assetList.setFixedCellHeight(cardSize + 78);
    }

    private void buildAdvancedFiltersPanel() {
        advancedFiltersPanel.setBorder(BorderFactory.createTitledBorder("Advanced Filters"));
        advancedFiltersPanel.add(labeledField("Animation name", animationFilterField));
        advancedFiltersPanel.add(labeledField("Texture path", textureFilterField));
        advancedFiltersPanel.add(labeledField("Min polygons", minPolygonsField));
        advancedFiltersPanel.add(labeledField("Max polygons", maxPolygonsField));
        advancedFiltersPanel.add(labeledField("Min size (KB)", minSizeKbField));
        advancedFiltersPanel.add(labeledField("Max size (KB)", maxSizeKbField));
        advancedFiltersPanel.setVisible(false);
        textureFilterField.setToolTipText("Matches textures indexed from MDL and MDX content");
        animationFilterField.setToolTipText("Matches animation names indexed from MDL and MDX content");
    }

    private JPanel labeledField(String label, JTextField field) {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        return panel;
    }

    private void wireFieldForFiltering(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilter();
            }
        });
    }

    private Long parseKilobytesToBytes(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            long kb = Long.parseLong(trimmed);
            if (kb < 0) {
                return null;
            }
            return kb * 1024L;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseNonNegativeInt(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean polygonRangeMatches(ModelAsset asset, Integer minPolygons, Integer maxPolygons) {
        if (minPolygons == null && maxPolygons == null) {
            return true;
        }
        int polygonCount = asset.metadata().polygonCount();
        if (polygonCount < 0) {
            return false;
        }
        boolean meetsMin = minPolygons == null || polygonCount >= minPolygons;
        boolean meetsMax = maxPolygons == null || polygonCount <= maxPolygons;
        return meetsMin && meetsMax;
    }

    private String formatPolygonCount(int polygonCount) {
        if (polygonCount < 0) {
            return "N/A";
        }
        return NumberFormat.getIntegerInstance().format(polygonCount);
    }

    private final class AssetCellRenderer extends DefaultListCellRenderer {
        private final CardPanel panel = new CardPanel();
        private final ShimmerPreviewLabel previewLabel = new ShimmerPreviewLabel();
        private final JLabel titleLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();
        private final JLabel starLabel = new JLabel();

        private AssetCellRenderer() {
            panel.setLayout(new BorderLayout(0, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

            previewLabel.setOpaque(true);
            previewLabel.setBackground(new Color(24, 28, 34));
            previewLabel.setForeground(new Color(228, 233, 239));
            previewLabel.setFont(previewLabel.getFont().deriveFont(Font.BOLD, 14f));
            previewLabel.setPreferredSize(new Dimension(180, 180));
            previewLabel.setHorizontalAlignment(JLabel.CENTER);
            panel.add(previewLabel, BorderLayout.CENTER);

            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12.5f));
            titleLabel.setForeground(new Color(36, 42, 50));

            metaLabel.setFont(metaLabel.getFont().deriveFont(11f));
            metaLabel.setForeground(new Color(104, 112, 124));

            starLabel.setFont(starLabel.getFont().deriveFont(Font.BOLD, 16f));
            starLabel.setForeground(new Color(255, 200, 50));

            JPanel bottomPanel = new JPanel(new BorderLayout(4, 0));
            bottomPanel.setOpaque(false);
            JPanel textPanel = new JPanel();
            textPanel.setLayout(new javax.swing.BoxLayout(textPanel, javax.swing.BoxLayout.Y_AXIS));
            textPanel.setOpaque(false);
            textPanel.add(titleLabel);
            textPanel.add(javax.swing.Box.createVerticalStrut(2));
            textPanel.add(metaLabel);
            bottomPanel.add(textPanel, BorderLayout.CENTER);
            bottomPanel.add(starLabel, BorderLayout.EAST);
            panel.add(bottomPanel, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            int cellWidth = list.getFixedCellWidth() > 0 ? list.getFixedCellWidth() : 216;
            int previewSize = Math.max(72, cellWidth - 56);
            previewLabel.setPreferredSize(new Dimension(previewSize, previewSize));
            panel.setPreferredSize(new Dimension(cellWidth, previewSize + 82));

            if (value instanceof ModelAsset asset) {
                boolean isFav = settings.isFavorite(asset.path().toAbsolutePath().toString());
                starLabel.setText(isFav ? "\u2605" : "");
                NumberFormat format = NumberFormat.getIntegerInstance();
                String displayName = asset.fileName();
                String mName = asset.metadata().modelName();
                if (!mName.isEmpty() && !mName.equalsIgnoreCase(
                        displayName.replaceFirst("\\.[^.]+$", ""))) {
                    displayName = displayName + " (" + mName + ")";
                }
                titleLabel.setText(displayName);
                String polygonInfo = asset.metadata().hasKnownPolygonCount()
                        ? formatPolygonText(asset.metadata().polygonCount(), format)
                        : "poly N/A";
                metaLabel.setText(formatFileSize(asset.fileSizeBytes()) + " | " + polygonInfo);

                // Show thumbnail if available, otherwise shimmer
                BufferedImage thumb = thumbnailRenderer != null
                        ? thumbnailRenderer.getThumbnail(asset.path()) : null;
                if (thumb != null) {
                    previewLabel.setThumbnail(thumb, previewSize);
                    previewLabel.setText("");
                } else {
                    previewLabel.setThumbnail(null, previewSize);
                    previewLabel.setText(asset.fileExtension().toUpperCase(Locale.ROOT));
                }

                ModelMetadata meta = asset.metadata();
                NumberFormat fmt = NumberFormat.getIntegerInstance();
                String tooltipName = asset.fileName();
                String mn = meta.modelName();
                if (!mn.isEmpty()) tooltipName += " (" + mn + ")";
                String tooltip = "<html><b>" + tooltipName + "</b><br>"
                        + "Polygons: " + (meta.hasKnownPolygonCount() ? fmt.format(meta.polygonCount()) : "N/A") + "<br>"
                        + "Vertices: " + fmt.format(meta.vertexCount()) + "<br>"
                        + "Bones: " + fmt.format(meta.boneCount()) + "<br>"
                        + "Sequences: " + fmt.format(meta.sequenceCount()) + "<br>"
                        + "Size: " + formatFileSize(asset.fileSizeBytes()) + "<br>"
                        + "<font color='gray'>" + asset.path() + "</font></html>";
                panel.setToolTipText(tooltip);
            }
            panel.setCardStyle(isSelected);
            return panel;
        }

        private String formatPolygonText(int polygonCount, NumberFormat format) {
            return format.format(polygonCount) + " poly";
        }

        private String formatFileSize(long bytes) {
            if (bytes >= 1_000_000) {
                return String.format("%.1f MB", bytes / 1_000_000.0);
            } else if (bytes >= 1_000) {
                return String.format("%.1f KB", bytes / 1_000.0);
            } else {
                return bytes + " B";
            }
        }
    }

    /**
     * Custom JLabel that shows a thumbnail image (scaled to fit) or an animated
     * shimmer gradient when no thumbnail is available yet.
     */
    private static final class ShimmerPreviewLabel extends JLabel {
        private static final Color SHIMMER_BASE = new Color(44, 49, 56);
        private static final Color SHIMMER_HIGHLIGHT = new Color(64, 69, 76);
        private static final Color LOADING_TEXT_COLOR = new Color(140, 150, 160);
        private static final Color SPINNER_COLOR = new Color(100, 140, 200);
        private static final Color PREVIEW_FRAME = new Color(255, 255, 255, 24);
        private static final int IMAGE_OVERSCAN = 2;
        private BufferedImage scaledThumb;
        private BufferedImage lastSource;
        private int lastSize;

        void setThumbnail(BufferedImage thumb, int displaySize) {
            if (thumb != null) {
                // Only rescale if source or size changed
                if (thumb != lastSource || displaySize != lastSize) {
                    lastSource = thumb;
                    lastSize = displaySize;
                    scaledThumb = scaleThumbnail(thumb, displaySize);
                }
            } else {
                scaledThumb = null;
                lastSource = null;
                lastSize = displaySize;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, w, h, 18, 18);

            if (scaledThumb == null) {
                // Draw shimmer animation
                float phase = (System.currentTimeMillis() % 1500) / 1500f;
                int bandWidth = w / 3;
                int bandX = (int) ((phase * (w + bandWidth)) - bandWidth);

                Shape clip = g2.getClip();
                g2.clip(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
                g2.setPaint(new GradientPaint(
                        bandX, 0, SHIMMER_BASE,
                        bandX + bandWidth / 2, 0, SHIMMER_HIGHLIGHT));
                g2.fillRect(bandX, 0, bandWidth / 2, h);
                g2.setPaint(new GradientPaint(
                        bandX + bandWidth / 2, 0, SHIMMER_HIGHLIGHT,
                        bandX + bandWidth, 0, SHIMMER_BASE));
                g2.fillRect(bandX + bandWidth / 2, 0, bandWidth / 2, h);
                g2.setClip(clip);

                // Draw spinning arc
                int spinnerSize = Math.min(w, h) / 4;
                int sx = (w - spinnerSize) / 2;
                int sy = (h - spinnerSize) / 2 - 8;
                float spinAngle = (System.currentTimeMillis() % 1000) / 1000f * 360f;
                g2.setColor(SPINNER_COLOR);
                g2.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                g2.drawArc(sx, sy, spinnerSize, spinnerSize, (int) spinAngle, 270);

                // "Loading..." text below spinner
                g2.setFont(getFont().deriveFont(Font.PLAIN, 10f));
                g2.setColor(LOADING_TEXT_COLOR);
                java.awt.FontMetrics fm = g2.getFontMetrics();
                String loadText = "Loading...";
                int tx = (w - fm.stringWidth(loadText)) / 2;
                int ty = sy + spinnerSize + fm.getHeight() + 4;
                g2.drawString(loadText, tx, ty);
            } else {
                Shape clip = g2.getClip();
                g2.clip(new RoundRectangle2D.Float(0, 0, w, h, 18, 18));
                g2.drawImage(scaledThumb,
                        -IMAGE_OVERSCAN, -IMAGE_OVERSCAN,
                        w + IMAGE_OVERSCAN * 2, h + IMAGE_OVERSCAN * 2,
                        null);
                g2.setClip(clip);
            }
            String text = getText();
            if (text != null && !text.isBlank()) {
                g2.setFont(getFont());
                g2.setColor(getForeground());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(text)) / 2;
                int ty = h - 14;
                g2.drawString(text, tx, ty);
            }
            g2.setColor(PREVIEW_FRAME);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 18, 18);
            g2.dispose();
        }

        private static BufferedImage scaleThumbnail(BufferedImage thumb, int displaySize) {
            BufferedImage scaled = new BufferedImage(displaySize, displaySize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(thumb, 0, 0, displaySize, displaySize, null);
            g2.dispose();
            return scaled;
        }
    }

    private static final class CardPanel extends JPanel {
        private static final int CARD_MARGIN = 6;
        private Color fill = new Color(249, 250, 252);
        private Color stroke = new Color(220, 225, 232);

        private CardPanel() {
            setOpaque(false);
        }

        void setCardStyle(boolean selected) {
            if (selected) {
                fill = new Color(231, 240, 255);
                stroke = new Color(94, 146, 235);
            } else {
                fill = new Color(249, 250, 252);
                stroke = new Color(220, 225, 232);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(CARD_MARGIN, CARD_MARGIN,
                    getWidth() - CARD_MARGIN * 2, getHeight() - CARD_MARGIN * 2, 22, 22);
            g2.setColor(stroke);
            g2.drawRoundRect(CARD_MARGIN, CARD_MARGIN,
                    getWidth() - CARD_MARGIN * 2 - 1, getHeight() - CARD_MARGIN * 2 - 1, 22, 22);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Shows an error dialog with selectable/copyable message text. */
    static void showErrorDialog(Component parent, String message, String title) {
        javax.swing.JTextArea textArea = new javax.swing.JTextArea(message);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBackground(javax.swing.UIManager.getColor("Panel.background"));
        textArea.setFont(javax.swing.UIManager.getFont("Label.font"));
        textArea.setBorder(null);
        textArea.setColumns(40);
        // Let the text area size itself, then cap height
        textArea.setSize(textArea.getPreferredSize());
        int rows = Math.min(textArea.getLineCount(), 20);
        textArea.setRows(rows);
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(450,
                Math.min(textArea.getPreferredSize().height + 10, 400)));
        JOptionPane.showMessageDialog(parent, scroll, title, JOptionPane.ERROR_MESSAGE);
    }
}
