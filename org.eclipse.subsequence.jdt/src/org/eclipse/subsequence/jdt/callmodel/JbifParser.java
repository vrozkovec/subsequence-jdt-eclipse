/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses a single {@code .jbif} (Bayesian Network Interchange Format) file
 * and extracts marginal method-call probabilities.
 * <p>
 * The JBIF binary format uses two passes:
 * <ol>
 *   <li><b>Declarations</b>: node names and their outcomes</li>
 *   <li><b>Definitions</b>: parent links and conditional probability tables</li>
 * </ol>
 * Node 0 is always "patterns" whose priors are used to marginalize method probabilities.
 * Method nodes have names like {@code Ljava/util/HashMap.put(...)...} with outcomes
 * {@code ["true","false"]}. The marginal probability of a method being called is
 * {@code P(called) = sum(P(true|pattern_i) * P(pattern_i))}.
 */
public final class JbifParser {

    private static final int MAGIC = 0xBA7E5B1F;
    private static final int EXPECTED_VERSION = 1;

    private JbifParser() {
        // Not meant to be instantiated
    }

    /**
     * Parses a JBIF stream and returns a map of simple method names to their marginal call probabilities.
     *
     * @param in the input stream containing JBIF data
     * @return map from method name (e.g. "put") to probability (0.0-1.0), never {@code null}
     * @throws IOException if the stream cannot be read or the format is invalid
     */
    public static Map<String, Double> parse(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        // Header
        int magic = dis.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid JBIF magic: " + Integer.toHexString(magic));
        }
        int version = dis.readInt();
        if (version != EXPECTED_VERSION) {
            throw new IOException("Unsupported JBIF version: " + version);
        }
        dis.readUTF(); // network name, ignored

        // Pass 1: Declarations
        int nodeCount = dis.readInt();
        String[] nodeNames = new String[nodeCount];
        int[] outcomeCounts = new int[nodeCount];

        for (int i = 0; i < nodeCount; i++) {
            nodeNames[i] = dis.readUTF();
            int outcomeCount = dis.readInt();
            outcomeCounts[i] = outcomeCount;
            for (int j = 0; j < outcomeCount; j++) {
                dis.readUTF(); // outcome name, not needed
            }
        }

        // Pass 2: Definitions — read pattern priors from node 0
        int parentCount0 = dis.readUnsignedByte(); // single byte, not int32
        for (int p = 0; p < parentCount0; p++) {
            dis.readInt(); // parent id, skip
        }
        int probCount0 = dis.readInt();
        double[] priors = new double[probCount0];
        for (int p = 0; p < probCount0; p++) {
            priors[p] = dis.readDouble();
        }

        // Read remaining nodes and compute marginals for method nodes
        Map<String, Double> marginals = new HashMap<>();
        int patternCount = priors.length;

        for (int i = 1; i < nodeCount; i++) {
            int parentCount = dis.readUnsignedByte();
            for (int p = 0; p < parentCount; p++) {
                dis.readInt(); // parent id
            }
            int probCount = dis.readInt();
            double[] probs = new double[probCount];
            for (int p = 0; p < probCount; p++) {
                probs[p] = dis.readDouble();
            }

            String nodeName = nodeNames[i];
            if (!isMethodNode(nodeName)) {
                continue;
            }

            // Marginal P(called) = sum(P(true|pattern_i) * P(pattern_i))
            // True probs are at even indices: [P(true|p0), P(false|p0), P(true|p1), ...]
            double marginal = 0.0;
            for (int p = 0; p < patternCount; p++) {
                int trueIndex = 2 * p;
                if (trueIndex < probs.length) {
                    marginal += probs[trueIndex] * priors[p];
                }
            }

            String methodName = extractMethodName(nodeName);
            if (methodName != null && !"nothing".equals(methodName)) {
                marginals.merge(methodName, marginal, Math::max);
            }
        }

        return marginals;
    }

    /**
     * Checks whether a node name represents a method (contains a parenthesis).
     */
    private static boolean isMethodNode(String nodeName) {
        return nodeName.indexOf('(') > 0;
    }

    /**
     * Extracts the simple method name from a JBIF node name.
     * <p>
     * Example: {@code Ljava/util/HashMap.put(Ljava/lang/Object;)V} yields {@code "put"}.
     *
     * @param nodeName the full JBIF node name
     * @return the simple method name, or {@code null} if it cannot be extracted
     */
    private static String extractMethodName(String nodeName) {
        int parenIndex = nodeName.indexOf('(');
        if (parenIndex <= 0) {
            return null;
        }
        int dotIndex = nodeName.lastIndexOf('.', parenIndex);
        if (dotIndex < 0 || dotIndex >= parenIndex - 1) {
            return null;
        }
        return nodeName.substring(dotIndex + 1, parenIndex);
    }
}
