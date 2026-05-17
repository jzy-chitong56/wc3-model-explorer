package com.hiveworkshop;

import com.hiveworkshop.i18n.Messages;
import com.hiveworkshop.parser.AppLogBuffer;
import com.hiveworkshop.parser.AppSettings;
import com.hiveworkshop.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Locale;

public final class Main {

    private static final String[] SUPPORTED_LOCALES = { "en", "fr", "zh-CN" };

    private Main() {
    }

    public static void main(String[] args) {
        AppLogBuffer.install();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
            System.err.println("[UNCAUGHT " + thread.getName() + "] " + throwable)
        );
        AppSettings settings = AppSettings.loadDefault();
        String localeTag = settings.locale();
        if (localeTag == null || localeTag.isBlank()) {
            localeTag = resolveOsLocale();
            settings.setLocale(localeTag);
            settings.save();
        }
        Messages.setLocale(Locale.forLanguageTag(localeTag));
        String savedTheme = settings.theme();
        if (!savedTheme.isBlank()) {
            try { UIManager.setLookAndFeel(savedTheme); }
            catch (Exception ex) { System.err.println("[Theme] " + ex.getMessage()); }
        }

        SwingUtilities.invokeLater(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> {
                System.err.println("[UNCAUGHT EDT] " + throwable);
                throwable.printStackTrace();
            });
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });
    }

    private static String resolveOsLocale() {
        Locale os = Locale.getDefault();
        String tag = os.toLanguageTag();
        for (String supported : SUPPORTED_LOCALES) {
            if (supported.equalsIgnoreCase(tag)) return supported;
        }
        String lang = os.getLanguage();
        for (String supported : SUPPORTED_LOCALES) {
            if (supported.equalsIgnoreCase(lang)) return supported;
        }
        return "en";
    }
}
