package org.example;

import org.example.i18n.Messages;
import org.example.parser.AppSettings;
import org.example.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Locale;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
            System.err.println("[UNCAUGHT " + thread.getName() + "] " + throwable)
        );
        AppSettings settings = AppSettings.loadDefault();
        Messages.setLocale(Locale.forLanguageTag(settings.locale()));
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
}
