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
 * Manages method-call and constructor frequency data from multiple sources:
 * <ol>
 *   <li><b>Pre-trained models</b> — JBIF/JSON files lazily loaded from ZIP archives (standard library types)</li>
 *   <li><b>Workspace analysis</b> — method call counts from the user's own code, stored as JSON</li>
 * </ol>
 * Supports three model types:
 * <ul>
 *   <li>{@code *-call.zip} — instance method call frequency (JBIF format)</li>
 *   <li>{@code *-statics.zip} — static method call frequency (JBIF format)</li>
 *   <li>{@code *-ctor.zip} — constructor call counts (JSON format)</li>
 * </ul>
 * Workspace data takes priority over pre-trained models when both exist for a type.
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

    private volatile Map<String, Map<String, Double>> workspaceData = Collections.emptyMap();

    private CallModelIndex(Path callZipPath, Path staticsZipPath, Path ctorZipPath) {
        this.callZipPath = callZipPath;
        this.staticsZipPath = staticsZipPath;
        this.ctorZipPath = ctorZipPath;
    }

    /**
     * Returns the method call probabilities for the given fully qualified type name.
     * <p>
     * Checks workspace data first, then the call ZIP (instance methods), then the statics ZIP
     * (static methods). A type's methods are typically in one ZIP or the other, not both.
     *
     * @param qualifiedTypeName the fully qualified type name (dot-separated)
     * @return map of method name to probability, or an empty map if no data exists
     */
    public Map<String, Double> getMethodProbabilities(String qualifiedTypeName) {
        DiagnosticLog.log("[getMethodProbs] type='" + qualifiedTypeName //$NON-NLS-1$
                + "' callZip=" + callZipPath + " staticsZip=" + staticsZipPath); //$NON-NLS-1$ //$NON-NLS-2$
        if (qualifiedTypeName == null) {
            return Collections.emptyMap();
        }

        // Load pre-trained model data (call ZIP then statics ZIP)
        Map<String, Double> modelProbs = loadFromJbifZip(qualifiedTypeName, callZipPath, callCache, missingCallTypes);
        if (modelProbs.isEmpty()) {
            modelProbs = loadFromJbifZip(qualifiedTypeName, staticsZipPath, staticsCache, missingStaticsTypes);
        }

        // Load workspace analysis data
        Map<String, Double> wsProbs = workspaceData.get(qualifiedTypeName);

        // Merge: workspace data supplements model data, keeping the higher probability for each method
        if (modelProbs.isEmpty() && (wsProbs == null || wsProbs.isEmpty())) {
            DiagnosticLog.log("[getMethodProbs] no data from any source"); //$NON-NLS-1$
            return Collections.emptyMap();
        }
        if (wsProbs == null || wsProbs.isEmpty()) {
            DiagnosticLog.log("[getMethodProbs] model only, size=" + modelProbs.size()); //$NON-NLS-1$
            return modelProbs;
        }
        if (modelProbs.isEmpty()) {
            DiagnosticLog.log("[getMethodProbs] workspace only, size=" + wsProbs.size()); //$NON-NLS-1$
            return wsProbs;
        }

        // Both sources have data — merge, taking the higher probability per method
        Map<String, Double> merged = new HashMap<>(modelProbs);
        for (var entry : wsProbs.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        DiagnosticLog.log("[getMethodProbs] merged model(" + modelProbs.size() //$NON-NLS-1$
                + ")+workspace(" + wsProbs.size() + ")=" + merged.size()); //$NON-NLS-1$ //$NON-NLS-2$
        return merged;
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

        return ctorCache.computeIfAbsent(qualifiedTypeName, this::loadFromCtorZip);
    }

    /**
     * Loads and parses a JBIF model from a ZIP archive for the given type.
     */
    private static Map<String, Double> loadFromJbifZip(String qualifiedTypeName, Path zipPath,
            ConcurrentHashMap<String, Map<String, Double>> cache, Set<String> missingTypes) {
        if (zipPath == null || missingTypes.contains(qualifiedTypeName)) {
            return Collections.emptyMap();
        }

        return cache.computeIfAbsent(qualifiedTypeName, typeName -> {
            String entryPath = typeName.replace('.', '/') + ".jbif"; //$NON-NLS-1$

            try (ZipFile zf = new ZipFile(zipPath.toFile())) {
                ZipEntry entry = zf.getEntry(entryPath);
                if (entry == null) {
                    missingTypes.add(typeName);
                    return Collections.emptyMap();
                }

                try (InputStream is = zf.getInputStream(entry)) {
                    return JbifParser.parse(is);
                }
            } catch (Exception e) {
                LOG.warn("Failed to load JBIF model for " + typeName + " from " + zipPath, e); //$NON-NLS-1$ //$NON-NLS-2$
                missingTypes.add(typeName);
                return Collections.emptyMap();
            }
        });
    }

    /**
     * Loads and parses a constructor model from the ctor ZIP archive for the given type.
     */
    private Map<String, Double> loadFromCtorZip(String qualifiedTypeName) {
        String entryPath = qualifiedTypeName.replace('.', '/') + ".json"; //$NON-NLS-1$

        try (ZipFile zf = new ZipFile(ctorZipPath.toFile())) {
            ZipEntry entry = zf.getEntry(entryPath);
            if (entry == null) {
                missingCtorTypes.add(qualifiedTypeName);
                return Collections.emptyMap();
            }

            try (InputStream is = zf.getInputStream(entry)) {
                return CtorModelParser.parse(is);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load ctor model for " + qualifiedTypeName, e); //$NON-NLS-1$
            missingCtorTypes.add(qualifiedTypeName);
            return Collections.emptyMap();
        }
    }

    /**
     * Sets workspace analysis data and persists it to disk.
     *
     * @param data map of type name to (method name to probability)
     */
    public void setWorkspaceData(Map<String, Map<String, Double>> data) {
        this.workspaceData = data != null ? data : Collections.emptyMap();
        saveWorkspaceDataToDisk();
    }

    /**
     * Loads previously saved workspace data from the plugin state location.
     */
    private void loadWorkspaceDataFromDisk() {
        Path file = getWorkspaceDataPath();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            Map<String, Map<String, Double>> data = parseJson(reader);
            this.workspaceData = data;
            LOG.info("Loaded workspace call data: " + data.size() + " types"); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (Exception e) {
            LOG.warn("Failed to load workspace call data", e); //$NON-NLS-1$
        }
    }

    /**
     * Saves current workspace data to the plugin state location as JSON.
     */
    private void saveWorkspaceDataToDisk() {
        Path file = getWorkspaceDataPath();
        if (file == null) {
            return;
        }

        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                writeJson(writer, workspaceData);
            }
        } catch (IOException e) {
            LOG.warn("Failed to save workspace call data", e); //$NON-NLS-1$
        }
    }

    /**
     * Returns the path to the workspace data JSON file in the plugin state location.
     */
    private static Path getWorkspaceDataPath() {
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
            idx.loadWorkspaceDataFromDisk();
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
        DiagnosticLog.log("[createFromModelDir] prefValue='" + dirStr + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        if (dirStr == null || dirStr.isBlank()) {
            DiagnosticLog.log("[createFromModelDir] EMPTY — returning null index"); //$NON-NLS-1$
            return new CallModelIndex(null, null, null);
        }

        Path dir = Path.of(dirStr);
        if (!Files.isDirectory(dir)) {
            DiagnosticLog.log("[createFromModelDir] NOT A DIRECTORY: " + dirStr); //$NON-NLS-1$
            LOG.warn("Model directory does not exist: " + dirStr); //$NON-NLS-1$
            return new CallModelIndex(null, null, null);
        }

        Path callZip = findZipBySuffix(dir, "-call.zip"); //$NON-NLS-1$
        Path staticsZip = findZipBySuffix(dir, "-statics.zip"); //$NON-NLS-1$
        Path ctorZip = findZipBySuffix(dir, "-ctor.zip"); //$NON-NLS-1$

        DiagnosticLog.log("[createFromModelDir] call=" + callZip //$NON-NLS-1$
                + " | statics=" + staticsZip //$NON-NLS-1$
                + " | ctor=" + ctorZip); //$NON-NLS-1$

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
