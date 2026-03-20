package org.example.parser;

import org.example.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ModelScanner {
    private static final Map<Path, List<ModelAsset>> CACHE = new ConcurrentHashMap<>();

    private ModelScanner() {
    }

    public static List<ModelAsset> scan(Path root) throws IOException {
        return scan(root, false, null);
    }

    public static List<ModelAsset> scan(Path root, boolean forceRefresh) throws IOException {
        return scan(root, forceRefresh, null);
    }

    /**
     * @param progressCallback called with (current, total) as each model is parsed; may be null
     */
    public static List<ModelAsset> scan(Path root, boolean forceRefresh,
                                        BiConsumer<Integer, Integer> progressCallback) throws IOException {
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

        // Phase 1: collect file paths (fast — no parsing)
        List<Path> modelFiles;
        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            modelFiles = stream.filter(Files::isRegularFile)
                    .filter(ModelScanner::isModelFile)
                    .collect(Collectors.toList());
        }

        int total = modelFiles.size();

        // Phase 2: parse metadata in parallel with progress reporting
        AtomicInteger counter = new AtomicInteger(0);
        List<ModelAsset> results = Collections.synchronizedList(new ArrayList<>(total));
        modelFiles.parallelStream().forEach(path -> {
            try {
                ModelMetadata meta = ModelMetadataExtractor.extract(path);
                results.add(new ModelAsset(path, Files.size(path), meta));
            } catch (Exception ignored) {
                // Skip unreadable files.
            }
            int done = counter.incrementAndGet();
            if (progressCallback != null) {
                progressCallback.accept(done, total);
            }
        });

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
