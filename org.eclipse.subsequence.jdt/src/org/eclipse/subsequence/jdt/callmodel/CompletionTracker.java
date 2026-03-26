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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

/**
 * Unified tracker for method usage data from two sources: workspace analysis and
 * accepted completion proposals.
 * <p>
 * Each (type, method) record stores two independent counters:
 * <ul>
 *   <li><b>workspace</b> — from source code analysis (replaced on re-analysis)</li>
 *   <li><b>user</b> — from accepted completions since the last analysis</li>
 * </ul>
 * Workspace re-analysis replaces workspace counts and resets user counts to 0,
 * since analysis captures the full codebase including previously accepted completions.
 * <p>
 * Thread-safe via {@link ConcurrentHashMap} and {@link AtomicInteger}.
 * All operations are wrapped in try/catch — must never break code completion.
 */
public final class CompletionTracker {

	private static final ILog LOG = Platform.getLog(CompletionTracker.class);

	/** Save to disk every N-th acceptance. */
	private static final int SAVE_INTERVAL = 10;

	private static final CompletionTracker INSTANCE = new CompletionTracker();

	private final ConcurrentHashMap<String, ConcurrentHashMap<String, MethodCounts>> data = new ConcurrentHashMap<>();
	private final AtomicInteger acceptanceCounter = new AtomicInteger(0);

	/**
	 * Dual counters for a single (type, method) pair.
	 *
	 * @param workspace count from workspace analysis
	 * @param user      count from accepted completions since last analysis
	 */
	record MethodCounts(AtomicInteger workspace, AtomicInteger user) {

		/**
		 * Creates a new instance with the given initial workspace count and zero user count.
		 *
		 * @param workspaceCount initial workspace count
		 * @return new {@code MethodCounts} instance
		 */
		static MethodCounts ofWorkspace(int workspaceCount) {
			return new MethodCounts(new AtomicInteger(workspaceCount), new AtomicInteger(0));
		}

		/**
		 * Creates a new instance with zero workspace count and the given initial user count.
		 *
		 * @param userCount initial user count
		 * @return new {@code MethodCounts} instance
		 */
		static MethodCounts ofUser(int userCount) {
			return new MethodCounts(new AtomicInteger(0), new AtomicInteger(userCount));
		}

		/**
		 * Returns the combined total of workspace and user counts.
		 *
		 * @return workspace + user
		 */
		int total() {
			return workspace.get() + user.get();
		}
	}

	private CompletionTracker() {
		loadFromDisk();
		Runtime.getRuntime().addShutdownHook(new Thread(this::saveToDisk, "CompletionTracker-shutdown")); //$NON-NLS-1$
	}

	/**
	 * Returns the singleton instance.
	 *
	 * @return the tracker instance, never {@code null}
	 */
	public static CompletionTracker getInstance() {
		return INSTANCE;
	}

	/**
	 * Records that the user accepted a completion proposal for the given type and method.
	 * Increments the user counter by 1.
	 *
	 * @param typeName   the fully qualified type name (dot-separated, generics erased)
	 * @param methodName the method name
	 */
	public void recordAcceptance(String typeName, String methodName) {
		try {
			if (typeName == null || typeName.isEmpty() || methodName == null || methodName.isEmpty()) {
				return;
			}

			data.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>())
					.computeIfAbsent(methodName, k -> MethodCounts.ofUser(0))
					.user().incrementAndGet();

			DiagnosticLog.log("[CompletionTracker] recorded: " + typeName + "." + methodName); //$NON-NLS-1$ //$NON-NLS-2$

			if (acceptanceCounter.incrementAndGet() % SAVE_INTERVAL == 0) {
				saveToDisk();
			}
		} catch (Exception e) {
			// must never break completion
			DiagnosticLog.log("[CompletionTracker] error recording: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Replaces all workspace counts with the given raw counts from workspace analysis,
	 * and resets all user counts to 0. Analysis captures the full codebase including
	 * previously accepted completions, so the user delta restarts from zero.
	 * <p>
	 * Types/methods not present in the new counts are removed entirely.
	 *
	 * @param rawCounts map of type name to (method name to raw invocation count)
	 */
	public void setWorkspaceCounts(Map<String, Map<String, Integer>> rawCounts) {
		try {
			// Build new data map from raw counts (all user counters start at 0)
			var newData = new ConcurrentHashMap<String, ConcurrentHashMap<String, MethodCounts>>();
			if (rawCounts != null) {
				for (var typeEntry : rawCounts.entrySet()) {
					var methods = new ConcurrentHashMap<String, MethodCounts>();
					for (var methodEntry : typeEntry.getValue().entrySet()) {
						methods.put(methodEntry.getKey(), MethodCounts.ofWorkspace(methodEntry.getValue()));
					}
					newData.put(typeEntry.getKey(), methods);
				}
			}

			// Atomically replace
			data.clear();
			data.putAll(newData);

			saveToDisk();
			DiagnosticLog.log("[CompletionTracker] setWorkspaceCounts: " + data.size() + " types"); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (Exception e) {
			DiagnosticLog.log("[CompletionTracker] error setWorkspaceCounts: " + e.getMessage()); //$NON-NLS-1$
		}
	}

	/**
	 * Returns normalized probabilities computed from combined workspace + user counts.
	 * <p>
	 * For each type, the method with the highest total (workspace + user) gets probability 1.0,
	 * and others are scaled proportionally.
	 *
	 * @return map of type name to (method name to probability), never {@code null}
	 */
	public Map<String, Map<String, Double>> getNormalizedData() {
		try {
			Map<String, Map<String, Double>> result = new HashMap<>();

			for (var typeEntry : data.entrySet()) {
				ConcurrentHashMap<String, MethodCounts> methodCounts = typeEntry.getValue();
				int maxTotal = methodCounts.values().stream()
						.mapToInt(MethodCounts::total)
						.max()
						.orElse(0);

				if (maxTotal <= 0) {
					continue;
				}

				Map<String, Double> normalized = new HashMap<>();
				for (var methodEntry : methodCounts.entrySet()) {
					normalized.put(methodEntry.getKey(), (double) methodEntry.getValue().total() / maxTotal);
				}
				result.put(typeEntry.getKey(), normalized);
			}

			return result;
		} catch (Exception e) {
			DiagnosticLog.log("[CompletionTracker] error normalizing: " + e.getMessage()); //$NON-NLS-1$
			return Collections.emptyMap();
		}
	}

	/**
	 * Loads previously saved dual-counter data from disk.
	 * Handles both new format ({@code {"w": N, "u": M}}) and old format (plain doubles).
	 */
	private void loadFromDisk() {
		try {
			Path file = CallModelIndex.getWorkspaceDataPath();
			if (file == null || !Files.isRegularFile(file)) {
				return;
			}

			String content;
			try (BufferedReader reader = Files.newBufferedReader(file)) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					sb.append(line);
				}
				content = sb.toString();
			}

			if (content.contains("\"w\"")) { //$NON-NLS-1$
				parseDualCounterJson(content);
			} else {
				// Old format: plain doubles — treat as workspace counts
				try (BufferedReader reader = Files.newBufferedReader(file)) {
					Map<String, Map<String, Double>> oldData = CallModelIndex.parseJson(reader);
					for (var typeEntry : oldData.entrySet()) {
						var methods = new ConcurrentHashMap<String, MethodCounts>();
						for (var methodEntry : typeEntry.getValue().entrySet()) {
							methods.put(methodEntry.getKey(), MethodCounts.ofWorkspace(methodEntry.getValue().intValue()));
						}
						data.put(typeEntry.getKey(), methods);
					}
				}
			}

			LOG.info("Loaded completion data: " + data.size() + " types"); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (Exception e) {
			LOG.warn("Failed to load completion data", e); //$NON-NLS-1$
		}
	}

	/**
	 * Parses the dual-counter JSON format:
	 * {@code {"type": {"method": {"w": 87, "u": 5}, ...}, ...}}.
	 */
	private void parseDualCounterJson(String json) {
		int i = json.indexOf('{');
		if (i < 0) {
			return;
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
			String typeName = json.substring(keyStart + 1, keyEnd);
			i = keyEnd + 1;

			// Find opening brace of method map
			int braceStart = json.indexOf('{', i);
			if (braceStart < 0) {
				break;
			}

			// Find matching closing brace (methods map contains nested braces)
			int braceEnd = findMatchingBrace(json, braceStart);
			if (braceEnd < 0) {
				break;
			}

			String methodsStr = json.substring(braceStart + 1, braceEnd);
			var methods = parseMethodCountsMap(methodsStr);
			if (!methods.isEmpty()) {
				data.put(typeName, methods);
			}
			i = braceEnd + 1;
		}
	}

	/**
	 * Parses the inner method map where each value is {@code {"w": N, "u": M}}.
	 */
	private static ConcurrentHashMap<String, MethodCounts> parseMethodCountsMap(String str) {
		var map = new ConcurrentHashMap<String, MethodCounts>();
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
			String methodName = str.substring(keyStart + 1, keyEnd);

			// Check if next value is an object (dual format) or a plain number (shouldn't happen but be safe)
			int colonPos = str.indexOf(':', keyEnd);
			if (colonPos < 0) {
				break;
			}

			int objStart = str.indexOf('{', colonPos);
			if (objStart < 0) {
				break;
			}
			int objEnd = str.indexOf('}', objStart);
			if (objEnd < 0) {
				break;
			}

			String objStr = str.substring(objStart + 1, objEnd);
			int w = extractIntField(objStr, "w"); //$NON-NLS-1$
			int u = extractIntField(objStr, "u"); //$NON-NLS-1$
			map.put(methodName, new MethodCounts(new AtomicInteger(w), new AtomicInteger(u)));

			i = objEnd + 1;
		}
		return map;
	}

	/**
	 * Extracts an integer field value from a simple JSON object string like {@code "w": 87, "u": 5}.
	 */
	private static int extractIntField(String objStr, String fieldName) {
		String search = "\"" + fieldName + "\""; //$NON-NLS-1$ //$NON-NLS-2$
		int idx = objStr.indexOf(search);
		if (idx < 0) {
			return 0;
		}
		int colonIdx = objStr.indexOf(':', idx + search.length());
		if (colonIdx < 0) {
			return 0;
		}
		int valStart = colonIdx + 1;
		while (valStart < objStr.length() && objStr.charAt(valStart) == ' ') {
			valStart++;
		}
		int valEnd = valStart;
		while (valEnd < objStr.length() && Character.isDigit(objStr.charAt(valEnd))) {
			valEnd++;
		}
		if (valEnd == valStart) {
			return 0;
		}
		try {
			return Integer.parseInt(objStr.substring(valStart, valEnd));
		} catch (NumberFormatException e) {
			return 0;
		}
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
	 * Finds the matching closing brace for the opening brace at {@code openPos},
	 * accounting for nested braces.
	 */
	private static int findMatchingBrace(String str, int openPos) {
		int depth = 1;
		for (int i = openPos + 1; i < str.length(); i++) {
			char c = str.charAt(i);
			if (c == '"') {
				// Skip string contents
				i = findClosingQuote(str, i + 1);
				if (i < 0) {
					return -1;
				}
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Saves current dual-counter data to disk as JSON.
	 * Format: {@code {"type": {"method": {"w": N, "u": M}, ...}, ...}}.
	 */
	void saveToDisk() {
		try {
			Path file = CallModelIndex.getWorkspaceDataPath();
			if (file == null) {
				return;
			}

			Files.createDirectories(file.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(file)) {
				writeDualCounterJson(writer);
			}

			DiagnosticLog.log("[CompletionTracker] saved " + data.size() + " types to disk"); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (IOException e) {
			LOG.warn("Failed to save completion data", e); //$NON-NLS-1$
		}
	}

	/**
	 * Writes the dual-counter data as JSON to the given writer.
	 */
	private void writeDualCounterJson(BufferedWriter writer) throws IOException {
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
				writer.newLine();
				writer.write("    \""); //$NON-NLS-1$
				writer.write(escapeJson(methodEntry.getKey()));
				writer.write("\": {\"w\": "); //$NON-NLS-1$
				writer.write(String.valueOf(methodEntry.getValue().workspace().get()));
				writer.write(", \"u\": "); //$NON-NLS-1$
				writer.write(String.valueOf(methodEntry.getValue().user().get()));
				writer.write('}');
			}
			writer.newLine();
			writer.write("  }"); //$NON-NLS-1$
		}
		writer.newLine();
		writer.write('}');
		writer.newLine();
	}

	/**
	 * Escapes a string for JSON output.
	 */
	private static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
}
