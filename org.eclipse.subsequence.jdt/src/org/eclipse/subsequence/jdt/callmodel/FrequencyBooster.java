/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import java.util.Map;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.Signature;

/**
 * Computes a relevance boost for completion proposals based on method-call frequency
 * from pre-trained models.
 * <p>
 * The boost is in the range {@code [0, MAX_FREQUENCY_BOOST]}. Methods with higher
 * call probabilities (e.g. {@code HashMap.put()} at ~26%) receive a larger boost,
 * while rare or unknown methods receive zero boost. This ensures that frequently
 * used methods rank higher in content assist without disrupting proposals for
 * types that have no model.
 */
public final class FrequencyBooster {

    /** Maximum relevance boost applied based on call frequency. */
    static final int MAX_FREQUENCY_BOOST = 200;

    private FrequencyBooster() {
        // Not meant to be instantiated
    }

    /**
     * Computes a relevance boost for the given core completion proposal based on
     * pre-trained method-call frequency data.
     *
     * @param coreProposal the JDT core completion proposal
     * @return a boost value between 0 and {@link #MAX_FREQUENCY_BOOST}, or 0 if
     *         the proposal is not a method reference or no model is available
     */
    public static int computeFrequencyBoost(CompletionProposal coreProposal) {
        if (coreProposal == null || !isMethodProposal(coreProposal)) {
            return 0;
        }

        CallModelIndex index = CallModelIndex.getInstance();
        if (index == null) {
            return 0;
        }

        String typeName = extractTypeName(coreProposal);
        if (typeName == null) {
            return 0;
        }

        Map<String, Double> probs = index.getMethodProbabilities(typeName);
        if (probs.isEmpty()) {
            return 0;
        }

        String methodName = new String(coreProposal.getName());
        Double probability = probs.get(methodName);
        if (probability == null) {
            return 0;
        }

        return (int) (probability * MAX_FREQUENCY_BOOST);
    }

    /**
     * Checks whether the proposal is a method reference kind.
     */
    private static boolean isMethodProposal(CompletionProposal proposal) {
        return switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF,
                 CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER,
                 CompletionProposal.METHOD_NAME_REFERENCE -> true;
            default -> false;
        };
    }

    /**
     * Extracts the fully qualified declaring type name from a proposal's declaration signature.
     * <p>
     * Converts JVM signatures like {@code Ljava/util/HashMap;} to {@code java.util.HashMap}.
     *
     * @param proposal the completion proposal
     * @return the qualified type name, or {@code null} if it cannot be determined
     */
    private static String extractTypeName(CompletionProposal proposal) {
        char[] declSig = proposal.getDeclarationSignature();
        if (declSig == null || declSig.length == 0) {
            return null;
        }

        // Signature.toString converts "Ljava/util/HashMap;" to "java.util.HashMap"
        String sig = new String(declSig);
        try {
            return Signature.toString(sig).replace('/', '.');
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
