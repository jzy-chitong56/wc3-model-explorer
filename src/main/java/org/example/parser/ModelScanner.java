package org.example.parser;

import org.example.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class ModelScanner {
    private static final Map<Path, List<ModelAsset>> CACHE = new ConcurrentHashMap<>();

    private ModelScanner() {
    }

    public static List<ModelAsset> scan(Path root) throws IOException {
        return scan(root, false);
    }

    public static List<ModelAsset> scan(Path root, boolean forceRefresh) throws IOException {
        if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!forceRefresh) {
            List<ModelAsset> cached = CACHE.get(normalizedRoot);
            if (cached != null) {
                return cached;
            }
        } else {
            ReterasModelParser.clearCache();
        }

        List<ModelAsset> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(ModelScanner::isModelFile)
                    .forEach(path -> {
                        try {
                            results.add(new ModelAsset(path, Files.size(path), ModelMetadataExtractor.extract(path)));
                        } catch (IOException ignored) {
                            // Skip unreadable files.
                        }
                    });
        }
        results.sort(Comparator.comparing(ModelAsset::fileName, String.CASE_INSENSITIVE_ORDER));
        List<ModelAsset> immutableResults = List.copyOf(results);
        CACHE.put(normalizedRoot, immutableResults);
        return immutableResults;
    }

    public static void invalidate(Path root) {
        if (root == null) {
            return;
        }
        CACHE.remove(root.toAbsolutePath().normalize());
    }

    public static void clearCache() {
        CACHE.clear();
        ReterasModelParser.clearCache();
    }

    private static boolean isModelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mdx") || name.endsWith(".mdl");
    }
}
