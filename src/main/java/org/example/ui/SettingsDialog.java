package org.example.ui;

import org.example.model.*;
import org.example.model.ExternalProgram;
import org.example.model.ThumbnailQuality;
import org.example.parser.*;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import javax.swing.JColorChooser;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SettingsDialog extends JDialog {

    // ── Theme entries ─────────────────────────────────────────────────────────

    private record ThemeEntry(String displayName, String className) {
        @Override public String toString() { return displayName; }
    }

    private static final ThemeEntry[] THEMES = {
        new ThemeEntry("Metal (Default)",  "javax.swing.plaf.metal.MetalLookAndFeel"),
        new ThemeEntry("Nimbus",           "javax.swing.plaf.nimbus.NimbusLookAndFeel"),
        new ThemeEntry("System",           UIManager.getSystemLookAndFeelClassName()),
        new ThemeEntry("FlatLaf Light",    "com.formdev.flatlaf.FlatLightLaf"),
        new ThemeEntry("FlatLaf Dark",     "com.formdev.flatlaf.FlatDarkLaf"),
        new ThemeEntry("FlatLaf IntelliJ", "com.formdev.flatlaf.FlatIntelliJLaf"),
        new ThemeEntry("FlatLaf Darcula",  "com.formdev.flatlaf.FlatDarculaLaf"),
    };

    // ── Fields ────────────────────────────────────────────────────────────────

    private final AppSettings settings;
    private final JTextField cascPathField = new JTextField();
    private final DefaultListModel<String> mpqListModel = new DefaultListModel<>();
    private final JList<String> mpqList = new JList<>(mpqListModel);
    private final JComboBox<ThemeEntry> themeCombo = new JComboBox<>(THEMES);
    private final JButton bgColorButton = new JButton();
    private Color bgColor = new Color(0x0F, 0x14, 0x19);
    private final JSlider yawSlider   = new JSlider(0, 360, (int) AppSettings.DEFAULT_CAMERA_YAW);
    private final JSlider pitchSlider = new JSlider(-89, 89, (int) AppSettings.DEFAULT_CAMERA_PITCH);
    private final JLabel  yawLabel    = new JLabel(yawSlider.getValue() + "\u00B0");
    private final JLabel  pitchLabel  = new JLabel(pitchSlider.getValue() + "\u00B0");
    private final JTextField animNameField = new JTextField("Stand", 12);
    private final JComboBox<ThumbnailQuality> qualityCombo = new JComboBox<>(ThumbnailQuality.values());
    private final DefaultListModel<ExternalProgram> extProgListModel = new DefaultListModel<>();
    private final JList<ExternalProgram> extProgList = new JList<>(extProgListModel);

    public SettingsDialog(JFrame owner, AppSettings settings) {
        super(owner, "Settings", true);
        this.settings = settings;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(new Dimension(580, 420));
        setMinimumSize(new Dimension(480, 360));
        setResizable(true);
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Data Sources",      buildDataSourcesPanel());
        tabs.addTab("Theme",             buildThemePanel());
        tabs.addTab("Camera",            buildCameraPanel());
        tabs.addTab("External Programs", buildExternalProgramsPanel());

        setLayout(new BorderLayout(8, 8));
        add(tabs,            BorderLayout.CENTER);
        add(buildButtonBar(), BorderLayout.SOUTH);

        loadFromSettings();
    }

    // ── Panel builders ────────────────────────────────────────────────────────

    private JPanel buildDataSourcesPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 12));
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        // CASC row
        JPanel cascPanel = new JPanel(new GridBagLayout());
        cascPanel.setBorder(BorderFactory.createTitledBorder("CASC Archive (Warcraft III Reforged installation)"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        cascPanel.add(new JLabel("Path:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        cascPanel.add(cascPathField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JButton browseCascBtn = new JButton("Browse…");
        browseCascBtn.addActionListener(e -> browseCascDirectory());
        cascPanel.add(browseCascBtn, gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.gridwidth = 2;
        JLabel hint = new JLabel("<html><font color='gray'>Point to the WC3 install folder that contains the _retail_ or Data sub-folder</font></html>");
        cascPanel.add(hint, gbc);

        // MPQ panel
        JPanel mpqPanel = new JPanel(new BorderLayout(6, 6));
        mpqPanel.setBorder(BorderFactory.createTitledBorder("MPQ Archives (classic Warcraft III)"));

        mpqList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        mpqPanel.add(new JScrollPane(mpqList), BorderLayout.CENTER);

        JPanel mpqButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addMpqBtn    = new JButton("Add…");
        JButton removeMpqBtn = new JButton("Remove");
        addMpqBtn.addActionListener(e -> addMpqFile());
        removeMpqBtn.addActionListener(e -> removeSelectedMpq());
        mpqButtons.add(addMpqBtn);
        mpqButtons.add(removeMpqBtn);
        mpqPanel.add(mpqButtons, BorderLayout.SOUTH);

        outer.add(cascPanel, BorderLayout.NORTH);
        outer.add(mpqPanel,  BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildThemePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        panel.add(new JLabel("Look & Feel:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(themeCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        panel.add(new JLabel("<html><font color='gray'>Theme change takes effect after clicking Apply or OK.</font></html>"), gbc);

        // Background color row
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("3D Background:"), gbc);

        gbc.gridx = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        bgColorButton.setPreferredSize(new Dimension(80, 26));
        bgColorButton.setBackground(bgColor);
        bgColorButton.setOpaque(true);
        bgColorButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(this, "Choose Background Color", bgColor);
            if (chosen != null) {
                bgColor = chosen;
                bgColorButton.setBackground(bgColor);
            }
        });
        panel.add(bgColorButton, gbc);

        // Push everything to the top
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);

        return panel;
    }

    private JPanel buildCameraPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;

        // Description
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3; gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("<html>Default camera angles for the model viewer and thumbnail generation.</html>"), gbc);

        // Yaw (azimuth) slider
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Azimuth (yaw):"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        yawSlider.setMajorTickSpacing(90);
        yawSlider.setMinorTickSpacing(15);
        yawSlider.setPaintTicks(true);
        yawSlider.setPaintLabels(true);
        yawSlider.addChangeListener(e -> yawLabel.setText(yawSlider.getValue() + "\u00B0"));
        panel.add(yawSlider, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        yawLabel.setPreferredSize(new Dimension(40, 20));
        panel.add(yawLabel, gbc);

        // Pitch (elevation) slider
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        panel.add(new JLabel("Elevation (pitch):"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        pitchSlider.setMajorTickSpacing(30);
        pitchSlider.setMinorTickSpacing(10);
        pitchSlider.setPaintTicks(true);
        pitchSlider.setPaintLabels(true);
        pitchSlider.addChangeListener(e -> pitchLabel.setText(pitchSlider.getValue() + "\u00B0"));
        panel.add(pitchSlider, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        pitchLabel.setPreferredSize(new Dimension(40, 20));
        panel.add(pitchLabel, gbc);

        // Thumbnail animation name
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Thumbnail animation:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(animNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3; gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("<html><font color='gray'>Animation name used for thumbnail pose (e.g. Stand, Attack). Leave empty for bind pose.</font></html>"), gbc);

        // Thumbnail quality
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 1; gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Thumbnail quality:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(qualityCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 3; gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("<html><font color='gray'>Higher quality renders at a larger resolution then downscales. Uses more GPU time.</font></html>"), gbc);

        // Push to top
        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 3; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);

        return panel;
    }

    private JPanel buildExternalProgramsPanel() {
        JPanel outer = new JPanel(new BorderLayout(8, 12));
        outer.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        outer.add(new JLabel("<html>Configure external 3D programs to open models with (right-click a thumbnail).</html>"),
                BorderLayout.NORTH);

        extProgList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        extProgList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                if (value instanceof ExternalProgram p) {
                    setText(p.name() + "  —  " + p.command());
                }
                return this;
            }
        });
        outer.add(new JScrollPane(extProgList), BorderLayout.CENTER);

        extProgList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) editSelectedExternalProgram();
            }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton("Add…");
        JButton editBtn = new JButton("Edit…");
        JButton removeBtn = new JButton("Remove");
        addBtn.addActionListener(e -> addExternalProgram());
        editBtn.addActionListener(e -> editSelectedExternalProgram());
        removeBtn.addActionListener(e -> {
            int idx = extProgList.getSelectedIndex();
            if (idx >= 0) extProgListModel.remove(idx);
        });
        buttons.add(addBtn);
        buttons.add(editBtn);
        buttons.add(removeBtn);
        outer.add(buttons, BorderLayout.SOUTH);

        return outer;
    }

    private void addExternalProgram() {
        ExternalProgram result = showExternalProgramDialog("Add External Program", "", "");
        if (result != null) {
            extProgListModel.addElement(result);
        }
    }

    private void editSelectedExternalProgram() {
        int idx = extProgList.getSelectedIndex();
        if (idx < 0) return;
        ExternalProgram existing = extProgListModel.get(idx);
        ExternalProgram result = showExternalProgramDialog("Edit External Program", existing.name(), existing.command());
        if (result != null) {
            extProgListModel.set(idx, result);
        }
    }

    private ExternalProgram showExternalProgramDialog(String title, String initialName, String initialCmd) {
        JTextField nameField = new JTextField(initialName, 15);
        JTextField cmdField = new JTextField(initialCmd, 30);
        JButton browseBtn = new JButton("Browse…");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setFileFilter(new FileNameExtensionFilter("Executables (*.exe, *.jar, *.bat)", "exe", "jar", "bat"));
            if (MainWindow.lastChooserDir != null) fc.setCurrentDirectory(MainWindow.lastChooserDir);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                MainWindow.lastChooserDir = fc.getCurrentDirectory();
                String path = fc.getSelectedFile().getAbsolutePath();
                String quoted = path.contains(" ") ? "\"" + path + "\"" : path;
                cmdField.setText(quoted + " {file}");
                if (nameField.getText().isBlank()) {
                    String fname = fc.getSelectedFile().getName();
                    int dot = fname.lastIndexOf('.');
                    nameField.setText(dot > 0 ? fname.substring(0, dot) : fname);
                }
            }
        });

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Command:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(cmdField, gbc);
        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        panel.add(browseBtn, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("<html><font color='gray'>Use {file} as placeholder for the model path. "
                + "E.g.: blender.exe {file}</font></html>"), gbc);

        int result = JOptionPane.showConfirmDialog(this, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String name = nameField.getText().trim();
            String cmd = cmdField.getText().trim();
            if (!name.isEmpty() && !cmd.isEmpty()) {
                return new ExternalProgram(name, cmd);
            }
        }
        return null;
    }

    private JPanel buildButtonBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));

        JButton applyBtn  = new JButton("Apply");
        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");

        applyBtn.addActionListener(e -> applyAndReload());
        okBtn.addActionListener(e -> { applyAndReload(); dispose(); });
        cancelBtn.addActionListener(e -> dispose());

        bar.add(applyBtn);
        bar.add(okBtn);
        bar.add(cancelBtn);
        return bar;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void browseCascDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setDialogTitle("Select Warcraft III installation folder");
        String current = cascPathField.getText().trim();
        if (!current.isEmpty()) {
            try { chooser.setCurrentDirectory(Path.of(current).toFile()); }
            catch (InvalidPathException ignored) {}
        } else if (MainWindow.lastChooserDir != null) {
            chooser.setCurrentDirectory(MainWindow.lastChooserDir);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            MainWindow.lastChooserDir = chooser.getSelectedFile();
            cascPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void addMpqFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("MPQ archives (*.mpq, *.w3x, *.w3m)", "mpq", "w3x", "w3m"));
        if (MainWindow.lastChooserDir != null) chooser.setCurrentDirectory(MainWindow.lastChooserDir);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            MainWindow.lastChooserDir = chooser.getCurrentDirectory();
            for (var file : chooser.getSelectedFiles()) {
                String path = file.getAbsolutePath();
                if (!mpqListModel.contains(path)) {
                    mpqListModel.addElement(path);
                }
            }
        }
    }

    private void removeSelectedMpq() {
        int[] selected = mpqList.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) {
            mpqListModel.remove(selected[i]);
        }
    }

    private void applyAndReload() {
        settings.setCascPath(cascPathField.getText().trim());
        List<String> mpqs = new ArrayList<>();
        for (int i = 0; i < mpqListModel.size(); i++) mpqs.add(mpqListModel.get(i));
        settings.setMpqPaths(mpqs);

        ThemeEntry selected = (ThemeEntry) themeCombo.getSelectedItem();
        if (selected != null) {
            settings.setTheme(selected.className());
            applyTheme(selected.className());
        }

        String hex = String.format("%02X%02X%02X", bgColor.getRed(), bgColor.getGreen(), bgColor.getBlue());
        settings.setBgColor(hex);

        settings.setCameraYaw(yawSlider.getValue());
        settings.setCameraPitch(pitchSlider.getValue());
        settings.setThumbnailAnimName(animNameField.getText());
        settings.setThumbnailQuality((ThumbnailQuality) qualityCombo.getSelectedItem());

        List<ExternalProgram> programs = new ArrayList<>();
        for (int i = 0; i < extProgListModel.size(); i++) programs.add(extProgListModel.get(i));
        settings.setExternalPrograms(programs);

        settings.save();

        // Reload GameDataSource off the EDT to avoid blocking the UI
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                GameDataSource.getInstance().refresh(settings);
                return null;
            }
            @Override protected void done() {
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Settings applied.", "Settings", JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    private static void applyTheme(String className) {
        try {
            UIManager.setLookAndFeel(className);
            for (Window w : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(w);
                w.pack();
            }
        } catch (Exception ex) {
            System.err.println("[Theme] Failed to apply look and feel: " + ex.getMessage());
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void loadFromSettings() {
        cascPathField.setText(settings.cascPath());
        mpqListModel.clear();
        for (String p : settings.mpqPaths()) {
            if (p != null && !p.isBlank()) mpqListModel.addElement(p);
        }

        String savedTheme = settings.theme();
        for (int i = 0; i < THEMES.length; i++) {
            if (THEMES[i].className().equals(savedTheme)) {
                themeCombo.setSelectedIndex(i);
                break;
            }
        }

        try {
            bgColor = Color.decode("#" + settings.bgColor());
        } catch (NumberFormatException ignored) {
            bgColor = new Color(0x0F, 0x14, 0x19);
        }
        bgColorButton.setBackground(bgColor);

        yawSlider.setValue((int) settings.cameraYaw());
        yawLabel.setText(yawSlider.getValue() + "\u00B0");
        pitchSlider.setValue((int) settings.cameraPitch());
        pitchLabel.setText(pitchSlider.getValue() + "\u00B0");
        animNameField.setText(settings.thumbnailAnimName());
        qualityCombo.setSelectedItem(settings.thumbnailQuality());

        extProgListModel.clear();
        for (ExternalProgram p : settings.externalPrograms()) {
            extProgListModel.addElement(p);
        }
    }
}
