/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses a constructor model JSON file and extracts parameter-signature-to-probability mappings.
 * <p>
 * The JSON format is:
 * <pre>
 * {"type":"Ljava/util/HashMap","calls":[
 *   {"id":"Ljava/util/HashMap.&lt;init&gt;()V","count":54411},
 *   {"id":"Ljava/util/HashMap.&lt;init&gt;(I)V","count":5521},
 *   ...
 * ]}
 * </pre>
 * Constructor IDs contain parameter signatures like {@code ()V} or {@code (I)V}.
 * Counts are normalized to probabilities by dividing by the maximum count.
 */
public final class CtorModelParser {

	private CtorModelParser() {
		// Not meant to be instantiated
	}

	/**
	 * Parses a constructor model JSON stream and returns a map of parameter signatures
	 * to normalized probabilities.
	 *
	 * @param in the input stream containing JSON data
	 * @return map from parameter signature (e.g. {@code "()V"}, {@code "(I)V"}) to probability (0.0-1.0),
	 *         never {@code null}
	 * @throws IOException if the stream cannot be read
	 */
	public static Map<String, Double> parse(InputStream in) throws IOException {
		String json = readFully(in);

		// Extract all call entries: {"id":"...","count":N}
		Map<String, Long> rawCounts = new HashMap<>();
		long maxCount = 0;

		int searchFrom = 0;
		while (true) {
			int idKeyPos = json.indexOf("\"id\"", searchFrom); //$NON-NLS-1$
			if (idKeyPos < 0) {
				break;
			}

			// Extract the id value
			int idValStart = json.indexOf('"', idKeyPos + 4);
			if (idValStart < 0) {
				break;
			}
			idValStart++; // skip opening quote
			int idValEnd = json.indexOf('"', idValStart);
			if (idValEnd < 0) {
				break;
			}
			String id = unescapeUnicode(json.substring(idValStart, idValEnd));

			// Extract parameter signature from id: "<init>(...)V" → "(...)V"
			String paramSig = extractParamSignature(id);

			// Extract the count value
			int countKeyPos = json.indexOf("\"count\"", idValEnd); //$NON-NLS-1$
			if (countKeyPos < 0) {
				break;
			}
			int colonPos = json.indexOf(':', countKeyPos + 7);
			if (colonPos < 0) {
				break;
			}
			int numStart = colonPos + 1;
			while (numStart < json.length() && json.charAt(numStart) == ' ') {
				numStart++;
			}
			int numEnd = numStart;
			while (numEnd < json.length() && Character.isDigit(json.charAt(numEnd))) {
				numEnd++;
			}

			if (paramSig != null && numEnd > numStart) {
				try {
					long count = Long.parseLong(json.substring(numStart, numEnd));
					rawCounts.merge(paramSig, count, Long::max);
					if (count > maxCount) {
						maxCount = count;
					}
				} catch (NumberFormatException e) {
					// skip malformed entry
				}
			}

			searchFrom = numEnd;
		}

		if (rawCounts.isEmpty() || maxCount == 0) {
			return Collections.emptyMap();
		}

		// Normalize counts to probabilities (0.0 - 1.0)
		Map<String, Double> result = new HashMap<>();
		for (var entry : rawCounts.entrySet()) {
			result.put(entry.getKey(), (double) entry.getValue() / maxCount);
		}
		return result;
	}

	/**
	 * Extracts the parameter signature from a constructor id.
	 * <p>
	 * Example: {@code "Ljava/util/HashMap.<init>(Ljava/util/Map;)V"} yields {@code "(Ljava/util/Map;)V"}.
	 *
	 * @param id the full constructor id
	 * @return the parameter signature including parentheses and return type, or {@code null}
	 */
	static String extractParamSignature(String id) {
		int parenIndex = id.indexOf('(');
		if (parenIndex < 0) {
			return null;
		}
		return id.substring(parenIndex);
	}

	/**
	 * Unescapes Unicode escape sequences like {@code \u003c} to their character equivalents.
	 */
	private static String unescapeUnicode(String s) {
		if (s.indexOf("\\u") < 0) { //$NON-NLS-1$
			return s;
		}
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			if (i + 5 < s.length() && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
				try {
					char c = (char) Integer.parseInt(s.substring(i + 2, i + 6), 16);
					sb.append(c);
					i += 5;
				} catch (NumberFormatException e) {
					sb.append(s.charAt(i));
				}
			} else {
				sb.append(s.charAt(i));
			}
		}
		return sb.toString();
	}

	/**
	 * Reads the entire input stream as a UTF-8 string.
	 */
	private static String readFully(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}
}
