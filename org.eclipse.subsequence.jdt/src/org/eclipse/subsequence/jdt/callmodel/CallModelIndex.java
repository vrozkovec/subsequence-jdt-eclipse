/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.subsequence.jdt.preferences.SubsequencePreferences;
import org.osgi.framework.FrameworkUtil;

/**
 * Manages method-call and constructor frequency data from two sources:
 * <ol>
 *   <li><b>Pre-trained models</b> — JBIF/JSON files lazily loaded from ZIP archives (standard library types)</li>
 *   <li><b>User data</b> — unified workspace analysis + completion acceptance counts via {@link CompletionTracker}</li>
 * </ol>
 * Supports three model types:
 * <ul>
 *   <li>{@code *-call.zip} — instance method call frequency (JBIF format)</li>
 *   <li>{@code *-statics.zip} — static method call frequency (JBIF format)</li>
 *   <li>{@code *-ctor.zip} — constructor call counts (JSON format)</li>
 * </ul>
 * <p>
 * Thread-safe via {@link ConcurrentHashMap} and volatile fields.
 */
public final class CallModelIndex {

    private static final ILog LOG = Platform.getLog(CallModelIndex.class);
    static final String WORKSPACE_DATA_FILE = "workspace-call-frequencies.json"; //$NON-NLS-1$

    private static volatile CallModelIndex instance;

    private final Path callZipPath;    // *-call.zip (instance methods, JBIF)
    private final Path staticsZipPath; // *-statics.zip (static methods, JBIF)
    private final Path ctorZipPath;    // *-ctor.zip (constructors, JSON)

    private final ConcurrentHashMap<String, Map<String, Double>> callCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Double>> staticsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Double>> ctorCache = new ConcurrentHashMap<>();

    private final Set<String> missingCallTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> missingStaticsTypes = ConcurrentHashMap.newKeySet();
    private final Set<String> missingCtorTypes = ConcurrentHashMap.newKeySet();

    /** Reverse index: simple class name → set of fully qualified names. */
    private final ConcurrentHashMap<String, Set<String>> simpleNameIndex = new ConcurrentHashMap<>();
    private volatile boolean trackerDataIndexed;

    private CallModelIndex(Path callZipPath, Path staticsZipPath, Path ctorZipPath) {
        this.callZipPath = callZipPath;
        this.staticsZipPath = staticsZipPath;
        this.ctorZipPath = ctorZipPath;
    }

    /**
     * Returns the method call probabilities for the given fully qualified type name.
     * <p>
     * Merges data from two sources (max probability per method wins):
     * <ol>
     *   <li>Pre-trained JBIF models (call ZIP then statics ZIP)</li>
     *   <li>User data — unified workspace analysis + completion acceptance via {@link CompletionTracker}</li>
     * </ol>
     *
     * @param qualifiedTypeName the fully qualified type name (dot-separated)
     * @return map of method name to probability, or an empty map if no data exists
     */
    public Map<String, Double> getMethodProbabilities(String qualifiedTypeName) {
        if (qualifiedTypeName == null) {
            return Collections.emptyMap();
        }

        // Unresolved type name (no dots) — resolve via reverse index
        if (qualifiedTypeName.indexOf('.') < 0) {
            return resolveAndGetMethodProbabilities(qualifiedTypeName);
        }

        return getMethodProbabilitiesForQualified(qualifiedTypeName);
    }

    /**
     * Core lookup for a fully qualified type name.
     */
    private Map<String, Double> getMethodProbabilitiesForQualified(String qualifiedTypeName) {
        // Source 1: Pre-trained model data. Instance (call ZIP) and static (statics ZIP)
        // methods are independent models — merge them so a type present in both exposes
        // both its instance and its static methods.
        Map<String, Double> callProbs = loadFromJbifZip(qualifiedTypeName, callZipPath, callCache, missingCallTypes);
        Map<String, Double> staticsProbs = loadFromJbifZip(qualifiedTypeName, staticsZipPath, staticsCache,
                missingStaticsTypes);
        Map<String, Double> modelProbs = mergeMax(callProbs, staticsProbs);
        if (!modelProbs.isEmpty()) {
            indexSimpleName(qualifiedTypeName);
        }

        // Source 2: User data (unified workspace + completion tracker)
        Map<String, Double> userProbs = Collections.emptyMap();
        try {
            Map<String, Double> tracked = CompletionTracker.getInstance().getNormalizedData().get(qualifiedTypeName);
            if (tracked != null && !tracked.isEmpty()) {
                userProbs = tracked;
                indexSimpleName(qualifiedTypeName);
            }
        } catch (Exception e) {
            // must never break completion
        }

        // Merge both sources — max probability per method wins
        return mergeMax(modelProbs, userProbs);
    }

    /**
     * Merges two probability maps, taking the higher probability per key.
     * <p>
     * When one map is empty the other is returned directly (callers treat results
     * as read-only), avoiding an allocation on the completion hot path.
     *
     * @param a the first map
     * @param b the second map
     * @return the merged map, never {@code null}
     */
    static Map<String, Double> mergeMax(Map<String, Double> a, Map<String, Double> b) {
        if (b.isEmpty()) {
            return a;
        }
        if (a.isEmpty()) {
            return b;
        }
        Map<String, Double> merged = new HashMap<>(a);
        for (var entry : b.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        return merged;
    }

    /**
     * Resolves an unqualified type name via the reverse index and returns merged probabilities.
     */
    private Map<String, Double> resolveAndGetMethodProbabilities(String simpleName) {
        ensureTrackerDataIndexed();

        Set<String> candidates = simpleNameIndex.get(simpleName);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }

        if (candidates.size() == 1) {
            return getMethodProbabilitiesForQualified(candidates.iterator().next());
        }

        // Ambiguous: merge results from all candidates
        Map<String, Double> merged = new HashMap<>();
        for (String candidate : candidates) {
            for (var entry : getMethodProbabilitiesForQualified(candidate).entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        return merged;
    }

    /**
     * Registers a fully qualified type name in the simple-name reverse index.
     */
    private void indexSimpleName(String qualifiedTypeName) {
        int lastDot = qualifiedTypeName.lastIndexOf('.');
        if (lastDot > 0) {
            String simpleName = qualifiedTypeName.substring(lastDot + 1);
            simpleNameIndex.computeIfAbsent(simpleName, k -> ConcurrentHashMap.newKeySet())
                    .add(qualifiedTypeName);
        }
    }

    /**
     * Indexes all type names from CompletionTracker data (called lazily on first unresolved lookup).
     */
    private void ensureTrackerDataIndexed() {
        if (trackerDataIndexed) {
            return;
        }
        try {
            Map<String, Map<String, Double>> userData = CompletionTracker.getInstance().getNormalizedData();
            for (String typeName : userData.keySet()) {
                indexSimpleName(typeName);
            }
        } catch (Exception e) {
            // must never break completion
        }
        trackerDataIndexed = true;
    }

    /**
     * Resolves an unqualified type name to a fully qualified name, if unambiguous.
     *
     * @param simpleName the simple class name (no dots)
     * @return the fully qualified name, or {@code null} if ambiguous or unknown
     */
    public String resolveSimpleName(String simpleName) {
        if (simpleName == null || simpleName.indexOf('.') >= 0) {
            return simpleName;
        }
        ensureTrackerDataIndexed();
        Set<String> candidates = simpleNameIndex.get(simpleName);
        if (candidates != null && candidates.size() == 1) {
            return candidates.iterator().next();
        }
        return null;
    }

    /**
     * Clears the simple-name reverse index (e.g. after workspace re-analysis).
     */
    void invalidateSimpleNameIndex() {
        simpleNameIndex.clear();
        trackerDataIndexed = false;
    }

    /**
     * Returns the constructor call probabilities for the given fully qualified type name.
     * <p>
     * Keys in the returned map are parameter signatures like {@code "()V"} or {@code "(I)V"}.
     * Values are normalized probabilities (0.0-1.0) where the most common constructor gets 1.0.
     *
     * @param qualifiedTypeName the fully qualified type name (dot-separated)
     * @return map of parameter signature to probability, or an empty map if no data exists
     */
    public Map<String, Double> getConstructorProbabilities(String qualifiedTypeName) {
        if (qualifiedTypeName == null || ctorZipPath == null || missingCtorTypes.contains(qualifiedTypeName)) {
            return Collections.emptyMap();
        }

        Map<String, Double> cached = ctorCache.get(qualifiedTypeName);
        if (cached != null) {
            return cached;
        }

        // Parse outside the map lock — a concurrent duplicate parse is harmless.
        Map<String, Double> parsed = loadFromCtorZip(qualifiedTypeName);
        if (parsed.isEmpty()) {
            missingCtorTypes.add(qualifiedTypeName);
            return Collections.emptyMap();
        }
        Map<String, Double> previous = ctorCache.putIfAbsent(qualifiedTypeName, parsed);
        return previous != null ? previous : parsed;
    }

    /**
     * Loads and parses a JBIF model from a ZIP archive for the given type.
     * <p>
     * Successful non-empty results are cached in {@code cache}; types without an
     * entry (or failing to parse) are remembered in {@code missingTypes} only, so
     * the positive cache holds no empty maps.
     */
    private static Map<String, Double> loadFromJbifZip(String qualifiedTypeName, Path zipPath,
            ConcurrentHashMap<String, Map<String, Double>> cache, Set<String> missingTypes) {
        if (zipPath == null || missingTypes.contains(qualifiedTypeName)) {
            return Collections.emptyMap();
        }

        Map<String, Double> cached = cache.get(qualifiedTypeName);
        if (cached != null) {
            return cached;
        }

        // Parse outside the map lock — a concurrent duplicate parse is harmless.
        Map<String, Double> parsed = parseJbifEntry(qualifiedTypeName, zipPath);
        if (parsed.isEmpty()) {
            missingTypes.add(qualifiedTypeName);
            return Collections.emptyMap();
        }
        Map<String, Double> previous = cache.putIfAbsent(qualifiedTypeName, parsed);
        return previous != null ? previous : parsed;
    }

    /**
     * Reads and parses the JBIF entry for the given type from the given ZIP.
     *
     * @return the parsed probabilities, or an empty map if the entry is missing or unreadable
     */
    private static Map<String, Double> parseJbifEntry(String qualifiedTypeName, Path zipPath) {
        String entryPath = qualifiedTypeName.replace('.', '/') + ".jbif"; //$NON-NLS-1$

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zf.getEntry(entryPath);
            if (entry == null) {
                return Collections.emptyMap();
            }

            try (InputStream is = zf.getInputStream(entry)) {
                return JbifParser.parse(is);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load JBIF model for " + qualifiedTypeName + " from " + zipPath, e); //$NON-NLS-1$ //$NON-NLS-2$
            return Collections.emptyMap();
        }
    }

    /**
     * Loads and parses a constructor model from the ctor ZIP archive for the given type.
     *
     * @return the parsed probabilities, or an empty map if the entry is missing or unreadable
     */
    private Map<String, Double> loadFromCtorZip(String qualifiedTypeName) {
        String entryPath = qualifiedTypeName.replace('.', '/') + ".json"; //$NON-NLS-1$

        try (ZipFile zf = new ZipFile(ctorZipPath.toFile())) {
            ZipEntry entry = zf.getEntry(entryPath);
            if (entry == null) {
                return Collections.emptyMap();
            }

            try (InputStream is = zf.getInputStream(entry)) {
                return CtorModelParser.parse(is);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load ctor model for " + qualifiedTypeName, e); //$NON-NLS-1$
            return Collections.emptyMap();
        }
    }

    /**
     * Returns the path to the workspace data JSON file in the plugin state location.
     * Package-private so {@link CompletionTracker} can reuse the same file path.
     *
     * @return the file path, or {@code null} if the state location cannot be determined
     */
    static Path getWorkspaceDataPath() {
        try {
            var bundle = FrameworkUtil.getBundle(CallModelIndex.class);
            if (bundle == null) {
                return null;
            }
            return Platform.getStateLocation(bundle).append(WORKSPACE_DATA_FILE).toFile().toPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the singleton instance, creating it if needed.
     * <p>
     * The instance is always created (even without model ZIPs configured) so that
     * workspace analysis data can still be used.
     *
     * @return the index instance, never {@code null}
     */
    public static CallModelIndex getInstance() {
        CallModelIndex idx = instance;
        if (idx != null) {
            return idx;
        }

        synchronized (CallModelIndex.class) {
            idx = instance;
            if (idx != null) {
                return idx;
            }

            idx = createFromModelDir();
            instance = idx;
            return idx;
        }
    }

    /**
     * Creates a new index by auto-discovering ZIP files in the configured model directory.
     * <p>
     * Looks for files matching {@code *-call.zip}, {@code *-statics.zip}, and {@code *-ctor.zip}.
     */
    private static CallModelIndex createFromModelDir() {
        String dirStr = SubsequencePreferences.getModelDirPath();
        if (dirStr == null || dirStr.isBlank()) {
            return new CallModelIndex(null, null, null);
        }

        Path dir = Path.of(dirStr);
        if (!Files.isDirectory(dir)) {
            LOG.warn("Model directory does not exist: " + dirStr); //$NON-NLS-1$
            return new CallModelIndex(null, null, null);
        }

        Path callZip = findZipBySuffix(dir, "-call.zip"); //$NON-NLS-1$
        Path staticsZip = findZipBySuffix(dir, "-statics.zip"); //$NON-NLS-1$
        Path ctorZip = findZipBySuffix(dir, "-ctor.zip"); //$NON-NLS-1$

        LOG.info("Model directory: " + dir //$NON-NLS-1$
                + " | call=" + (callZip != null ? callZip.getFileName() : "none") //$NON-NLS-1$ //$NON-NLS-2$
                + " | statics=" + (staticsZip != null ? staticsZip.getFileName() : "none") //$NON-NLS-1$ //$NON-NLS-2$
                + " | ctor=" + (ctorZip != null ? ctorZip.getFileName() : "none")); //$NON-NLS-1$ //$NON-NLS-2$

        return new CallModelIndex(callZip, staticsZip, ctorZip);
    }

    /**
     * Finds the first ZIP file in the directory whose name ends with the given suffix.
     *
     * @param dir    the directory to search
     * @param suffix the filename suffix to match (e.g. {@code "-call.zip"})
     * @return the path to the matching ZIP, or {@code null} if not found
     */
    private static Path findZipBySuffix(Path dir, String suffix) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + suffix)) { //$NON-NLS-1$
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan model directory for " + suffix, e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Resets the singleton instance, forcing re-initialization on next access.
     * Called when the model directory preference changes.
     */
    public static void reset() {
        instance = null;
    }

    /**
     * Returns a short human-readable status of the given model directory for display
     * in the preference UI: which model ZIPs were found, or why none are used.
     *
     * @param dirStr the configured model directory path (may be {@code null} or blank)
     * @return a one-line status, never {@code null}
     */
    public static String describeModelDir(String dirStr) {
        if (dirStr == null || dirStr.isBlank()) {
            return "No model directory configured — frequency ranking uses workspace data only.";
        }

        Path dir;
        try {
            dir = Path.of(dirStr);
        } catch (java.nio.file.InvalidPathException e) {
            return "Invalid directory path.";
        }
        if (!Files.isDirectory(dir)) {
            return "Directory does not exist — frequency ranking uses workspace data only.";
        }

        Path callZip = findZipBySuffix(dir, "-call.zip"); //$NON-NLS-1$
        Path staticsZip = findZipBySuffix(dir, "-statics.zip"); //$NON-NLS-1$
        Path ctorZip = findZipBySuffix(dir, "-ctor.zip"); //$NON-NLS-1$

        if (callZip == null && staticsZip == null && ctorZip == null) {
            return "No model ZIPs found (expected *-call.zip, *-statics.zip, *-ctor.zip).";
        }
        return "Models found — call: " + zipName(callZip) + ", statics: " + zipName(staticsZip)
                + ", ctor: " + zipName(ctorZip);
    }

    /**
     * Returns the file name of the given ZIP path, or {@code "none"} when absent.
     */
    private static String zipName(Path zip) {
        return zip != null ? zip.getFileName().toString() : "none";
    }

    // --- Minimal JSON serialization (no external dependencies) ---

    /**
     * Writes the workspace data as JSON: {@code {"type": {"method": prob, ...}, ...}}.
     */
    static void writeJson(BufferedWriter writer, Map<String, Map<String, Double>> data) throws IOException {
        writer.write('{');
        boolean firstType = true;
        for (var typeEntry : data.entrySet()) {
            if (!firstType) {
                writer.write(',');
            }
            firstType = false;
            writer.newLine();
            writer.write("  \""); //$NON-NLS-1$
            writer.write(escapeJson(typeEntry.getKey()));
            writer.write("\": {"); //$NON-NLS-1$
            boolean firstMethod = true;
            for (var methodEntry : typeEntry.getValue().entrySet()) {
                if (!firstMethod) {
                    writer.write(',');
                }
                firstMethod = false;
                writer.write(' ');
                writer.write('"');
                writer.write(escapeJson(methodEntry.getKey()));
                writer.write("\": "); //$NON-NLS-1$
                writer.write(String.valueOf(methodEntry.getValue()));
            }
            writer.write(" }"); //$NON-NLS-1$
        }
        writer.newLine();
        writer.write('}');
        writer.newLine();
    }

    /**
     * Parses JSON written by {@link #writeJson} back into a map.
     * <p>
     * Simple state-machine parser — handles the exact format we produce.
     */
    static Map<String, Map<String, Double>> parseJson(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return parseJsonString(sb.toString());
    }

    /**
     * Parses a JSON object string into a nested map structure.
     */
    private static Map<String, Map<String, Double>> parseJsonString(String json) {
        Map<String, Map<String, Double>> result = new HashMap<>();
        int i = json.indexOf('{');
        if (i < 0) {
            return result;
        }
        i++;

        while (i < json.length()) {
            // Find next type key
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = findClosingQuote(json, keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            String typeName = unescapeJson(json.substring(keyStart + 1, keyEnd));
            i = keyEnd + 1;

            // Find opening brace of method map
            int braceStart = json.indexOf('{', i);
            if (braceStart < 0) {
                break;
            }
            int braceEnd = json.indexOf('}', braceStart);
            if (braceEnd < 0) {
                break;
            }

            String methodsStr = json.substring(braceStart + 1, braceEnd);
            Map<String, Double> methods = parseMethodMap(methodsStr);
            result.put(typeName, methods);
            i = braceEnd + 1;
        }
        return result;
    }

    /**
     * Parses the inner method map: {@code "methodName": 0.85, "other": 1.0}.
     */
    private static Map<String, Double> parseMethodMap(String str) {
        Map<String, Double> map = new HashMap<>();
        int i = 0;
        while (i < str.length()) {
            int keyStart = str.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = findClosingQuote(str, keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            String methodName = unescapeJson(str.substring(keyStart + 1, keyEnd));

            int colonPos = str.indexOf(':', keyEnd);
            if (colonPos < 0) {
                break;
            }

            // Find the value (number) — ends at comma, whitespace, or end of string
            int valStart = colonPos + 1;
            while (valStart < str.length() && str.charAt(valStart) == ' ') {
                valStart++;
            }
            int valEnd = valStart;
            while (valEnd < str.length() && str.charAt(valEnd) != ',' && str.charAt(valEnd) != ' ') {
                valEnd++;
            }

            try {
                double value = Double.parseDouble(str.substring(valStart, valEnd));
                map.put(methodName, value);
            } catch (NumberFormatException e) {
                // skip malformed entry
            }
            i = valEnd;
        }
        return map;
    }

    /**
     * Finds the closing quote, skipping escaped characters.
     */
    private static int findClosingQuote(String str, int from) {
        for (int i = from; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\\') {
                i++; // skip escaped char
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Escapes a string for JSON output.
     */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Unescapes a JSON string.
     */
    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
