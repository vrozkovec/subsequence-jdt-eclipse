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
 * Computes a relevance boost for completion proposals based on method-call and
 * constructor frequency from pre-trained models.
 * <p>
 * The boost is in the range {@code [0, MAX_FREQUENCY_BOOST]}. Methods/constructors
 * with higher call probabilities receive a larger boost, while rare or unknown ones
 * receive zero boost. This ensures that frequently used APIs rank higher in content
 * assist without disrupting proposals for types that have no model.
 */
public final class FrequencyBooster {

    /** Maximum relevance boost applied based on call frequency. */
    static final int MAX_FREQUENCY_BOOST = 200;

    private FrequencyBooster() {
        // Not meant to be instantiated
    }

    /**
     * Computes a relevance boost for the given core completion proposal based on
     * pre-trained frequency data.
     *
     * @param coreProposal the JDT core completion proposal
     * @return a boost value between 0 and {@link #MAX_FREQUENCY_BOOST}, or 0 if
     *         no model data is available for the proposal
     */
    public static int computeFrequencyBoost(CompletionProposal coreProposal) {
        if (coreProposal == null) {
            return 0;
        }

        return switch (coreProposal.getKind()) {
            case CompletionProposal.METHOD_REF,
                 CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER,
                 CompletionProposal.METHOD_NAME_REFERENCE
                -> computeMethodBoost(coreProposal);
            case CompletionProposal.CONSTRUCTOR_INVOCATION
                -> computeConstructorBoost(coreProposal);
            default -> 0;
        };
    }

    /**
     * Computes boost for method proposals (instance and static) using call and statics models.
     */
    private static int computeMethodBoost(CompletionProposal coreProposal) {
        CallModelIndex index = CallModelIndex.getInstance();
        if (index == null) {
            DiagnosticLog.log("[methodBoost] index is NULL"); //$NON-NLS-1$
            return 0;
        }

        String typeName = extractTypeName(coreProposal);
        DiagnosticLog.log("[methodBoost] kind=" + coreProposal.getKind() //$NON-NLS-1$
                + " declSig=" + charArrayToString(coreProposal.getDeclarationSignature()) //$NON-NLS-1$
                + " typeName=" + typeName //$NON-NLS-1$
                + " name=" + charArrayToString(coreProposal.getName())); //$NON-NLS-1$
        if (typeName == null) {
            return 0;
        }

        // Skip java.lang.Object methods — they are inherited by every type and should
        // not receive any frequency boost, keeping them at the bottom of the list.
        if ("java.lang.Object".equals(typeName)) { //$NON-NLS-1$
            DiagnosticLog.log("[methodBoost] skip Object method: " //$NON-NLS-1$
                    + charArrayToString(coreProposal.getName()));
            return 0;
        }

        Map<String, Double> probs = index.getMethodProbabilities(typeName);
        if (probs.isEmpty()) {
            DiagnosticLog.log("[methodBoost] no probs for " + typeName); //$NON-NLS-1$
            return 0;
        }

        String methodName = new String(coreProposal.getName());
        Double probability = probs.get(methodName);
        int boost = probability != null ? (int) (probability * MAX_FREQUENCY_BOOST) : 0;
        DiagnosticLog.log("[methodBoost] method='" + methodName + "' prob=" + probability //$NON-NLS-1$ //$NON-NLS-2$
                + " boost=" + boost); //$NON-NLS-1$
        if (probability == null) {
            return 0;
        }

        return boost;
    }

    /**
     * Null-safe conversion of a char array to a string for diagnostic logging.
     */
    private static String charArrayToString(char[] chars) {
        return chars != null ? new String(chars) : "null"; //$NON-NLS-1$
    }

    /**
     * Computes boost for constructor proposals using the ctor model.
     * <p>
     * Matches by parameter signature extracted from the proposal's {@code getSignature()},
     * which returns strings like {@code "(I)V"} or {@code "(Ljava/util/Map;)V"}.
     */
    private static int computeConstructorBoost(CompletionProposal coreProposal) {
        CallModelIndex index = CallModelIndex.getInstance();
        if (index == null) {
            return 0;
        }

        String typeName = extractTypeName(coreProposal);
        if (typeName == null) {
            return 0;
        }

        Map<String, Double> probs = index.getConstructorProbabilities(typeName);
        if (probs.isEmpty()) {
            return 0;
        }

        char[] sig = coreProposal.getSignature();
        if (sig == null || sig.length == 0) {
            return 0;
        }

        // The signature from CompletionProposal uses readable type names like
        // "(QMap;)V" or "(QString;I)V" — we need to match against the model's
        // JVM-style signatures like "(Ljava/util/Map;)V".
        // Try exact match first, then fall back to matching by parameter count.
        String proposalSig = new String(sig);
        Double probability = probs.get(proposalSig);
        if (probability != null) {
            return (int) (probability * MAX_FREQUENCY_BOOST);
        }

        // Fall back: match by parameter count
        int proposalParamCount = Signature.getParameterCount(proposalSig);
        Double bestMatch = null;
        for (var entry : probs.entrySet()) {
            try {
                if (Signature.getParameterCount(entry.getKey()) == proposalParamCount) {
                    if (bestMatch == null || entry.getValue() > bestMatch) {
                        bestMatch = entry.getValue();
                    }
                }
            } catch (IllegalArgumentException e) {
                // skip malformed signatures in the model
            }
        }

        if (bestMatch != null) {
            return (int) (bestMatch * MAX_FREQUENCY_BOOST);
        }
        return 0;
    }

    /**
     * Extracts the fully qualified declaring type name from a proposal's declaration signature.
     * <p>
     * Converts JVM signatures like {@code Ljava/util/HashMap;} to {@code java.util.HashMap}.
     * Erases generic type parameters before resolving.
     *
     * @param proposal the completion proposal
     * @return the qualified type name, or {@code null} if it cannot be determined
     */
    public static String extractTypeName(CompletionProposal proposal) {
        char[] declSig = proposal.getDeclarationSignature();
        if (declSig == null || declSig.length == 0) {
            return null;
        }

        // Erase generic type parameters before resolving — the JBIF entries use raw type names.
        // E.g. "Ljava.util.HashMap<Ljava.lang.Object;Ljava.lang.Object;>;" → "Ljava.util.HashMap;"
        String sig = Signature.getTypeErasure(new String(declSig));
        try {
            return Signature.toString(sig).replace('/', '.');
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
