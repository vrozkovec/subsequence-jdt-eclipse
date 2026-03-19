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
 * Manages method-call frequency data from two sources:
 * <ol>
 *   <li><b>Pre-trained models</b> — JBIF files lazily loaded from a ZIP archive (standard library types)</li>
 *   <li><b>Workspace analysis</b> — method call counts from the user's own code, stored as JSON</li>
 * </ol>
 * Workspace data takes priority over pre-trained models when both exist for a type.
 * <p>
 * Thread-safe via {@link ConcurrentHashMap} and volatile fields.
 */
public final class CallModelIndex {

    private static final ILog LOG = Platform.getLog(CallModelIndex.class);
    static final String WORKSPACE_DATA_FILE = "workspace-call-frequencies.json"; //$NON-NLS-1$

    private static volatile CallModelIndex instance;

    private final Path zipPath; // may be null if no ZIP configured
    private final ConcurrentHashMap<String, Map<String, Double>> zipCache = new ConcurrentHashMap<>();
    private final Set<String> missingZipTypes = ConcurrentHashMap.newKeySet();
    private volatile Map<String, Map<String, Double>> workspaceData = Collections.emptyMap();

    private CallModelIndex(Path zipPath) {
        this.zipPath = zipPath;
    }

    /**
     * Returns the method call probabilities for the given fully qualified type name.
     * <p>
     * Checks workspace data first (user's actual usage patterns), then falls back to
     * pre-trained ZIP models (standard library types). If both sources have data for a type,
     * workspace data wins.
     *
     * @param qualifiedTypeName the fully qualified type name (dot-separated)
     * @return map of method probabilities, or an empty map if no data exists
     */
    public Map<String, Double> getMethodProbabilities(String qualifiedTypeName) {
        if (qualifiedTypeName == null) {
            return Collections.emptyMap();
        }

        // Workspace data takes priority
        Map<String, Double> wsProbs = workspaceData.get(qualifiedTypeName);
        if (wsProbs != null && !wsProbs.isEmpty()) {
            return wsProbs;
        }

        // Fall back to ZIP model
        if (zipPath == null || missingZipTypes.contains(qualifiedTypeName)) {
            return Collections.emptyMap();
        }

        return zipCache.computeIfAbsent(qualifiedTypeName, this::loadFromZip);
    }

    /**
     * Loads and parses a JBIF model for the given type from the ZIP archive.
     */
    private Map<String, Double> loadFromZip(String qualifiedTypeName) {
        String entryPath = qualifiedTypeName.replace('.', '/') + ".jbif"; //$NON-NLS-1$

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            ZipEntry entry = zf.getEntry(entryPath);
            if (entry == null) {
                missingZipTypes.add(qualifiedTypeName);
                return Collections.emptyMap();
            }

            try (InputStream is = zf.getInputStream(entry)) {
                return JbifParser.parse(is);
            }
        } catch (Exception e) {
            LOG.warn("Failed to load call model for " + qualifiedTypeName, e); //$NON-NLS-1$
            missingZipTypes.add(qualifiedTypeName);
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
     * The instance is always created (even without a ZIP path configured) so that
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

            Path zipPathResolved = resolveZipPath();
            idx = new CallModelIndex(zipPathResolved);
            idx.loadWorkspaceDataFromDisk();
            instance = idx;
            return idx;
        }
    }

    /**
     * Resolves the configured ZIP path, returning {@code null} if not configured or missing.
     */
    private static Path resolveZipPath() {
        String pathStr = SubsequencePreferences.getCallModelZipPath();
        if (pathStr == null || pathStr.isBlank()) {
            return null;
        }

        Path path = Path.of(pathStr);
        if (!Files.isRegularFile(path)) {
            LOG.warn("Call model ZIP does not exist: " + pathStr); //$NON-NLS-1$
            return null;
        }
        return path;
    }

    /**
     * Resets the singleton instance, forcing re-initialization on next access.
     * Called when the call model ZIP path preference changes.
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
