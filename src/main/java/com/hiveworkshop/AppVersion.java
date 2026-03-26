package com.hiveworkshop;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppVersion {
    private static final String VERSION;

    static {
        String v = "unknown";
        try (InputStream is = AppVersion.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                v = p.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {
        }
        VERSION = v;
    }

    private AppVersion() {}

    public static String get() {
        return VERSION;
    }
}
