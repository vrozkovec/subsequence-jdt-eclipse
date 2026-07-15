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

    /** Key prefix for constructor entries in user data: {@code "<init>#" + paramCount}. */
    static final String CTOR_KEY_PREFIX = "<init>#"; //$NON-NLS-1$

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
        String typeName = extractTypeName(coreProposal);
        if (typeName == null) {
            return 0;
        }

        // Skip java.lang.Object methods — they are inherited by every type and should
        // not receive any frequency boost, keeping them at the bottom of the list.
        if ("java.lang.Object".equals(typeName)) { //$NON-NLS-1$
            return 0;
        }

        Map<String, Double> probs = CallModelIndex.getInstance().getMethodProbabilities(typeName);
        if (probs.isEmpty()) {
            return 0;
        }

        char[] nameChars = coreProposal.getName();
        if (nameChars == null) {
            return 0;
        }
        String methodName = new String(nameChars);

        // Try composite key (overload-aware) first, then fall back to plain method name
        String compositeKey = buildCompositeKey(methodName, coreProposal);
        Double probability = compositeKey != null ? probs.get(compositeKey) : null;
        if (probability == null) {
            probability = probs.get(methodName);
        }
        if (probability == null) {
            return 0;
        }

        return (int) (probability * MAX_FREQUENCY_BOOST);
    }

    /**
     * Computes boost for constructor proposals using the ctor model and user data.
     * <p>
     * Pre-trained data is matched by parameter signature from the proposal's
     * {@code getSignature()}; user data (workspace analysis + accepted completions)
     * is keyed {@code "<init>#paramCount"} in the type's method map. The higher
     * probability wins.
     */
    private static int computeConstructorBoost(CompletionProposal coreProposal) {
        String typeName = extractTypeName(coreProposal);
        if (typeName == null) {
            return 0;
        }

        char[] sig = coreProposal.getSignature();
        if (sig == null || sig.length == 0) {
            return 0;
        }
        String proposalSig = new String(sig);

        int proposalParamCount;
        try {
            proposalParamCount = Signature.getParameterCount(proposalSig);
        } catch (IllegalArgumentException e) {
            return 0;
        }

        CallModelIndex index = CallModelIndex.getInstance();
        double probability = 0.0;

        Map<String, Double> probs = index.getConstructorProbabilities(typeName);
        if (!probs.isEmpty()) {
            // The signature from CompletionProposal uses readable type names like
            // "(QMap;)V" or "(QString;I)V" — the model stores JVM-style signatures
            // like "(Ljava/util/Map;)V". Try exact match first, then fall back to
            // matching by parameter count.
            Double exact = probs.get(proposalSig);
            if (exact != null) {
                probability = exact;
            } else {
                for (var entry : probs.entrySet()) {
                    try {
                        if (Signature.getParameterCount(entry.getKey()) == proposalParamCount
                                && entry.getValue() > probability) {
                            probability = entry.getValue();
                        }
                    } catch (IllegalArgumentException e) {
                        // skip malformed signatures in the model
                    }
                }
            }
        }

        // User data (workspace analysis + accepted completions), keyed "<init>#paramCount"
        Double userProbability = index.getMethodProbabilities(typeName)
                .get(CTOR_KEY_PREFIX + proposalParamCount);
        if (userProbability != null && userProbability > probability) {
            probability = userProbability;
        }

        return (int) (probability * MAX_FREQUENCY_BOOST);
    }

    /**
     * Builds a composite key {@code "methodName#paramCount"} from a method name and proposal signature.
     *
     * @return the composite key, or {@code null} if the param count cannot be determined
     */
    private static String buildCompositeKey(String methodName, CompletionProposal proposal) {
        char[] sig = proposal.getSignature();
        if (sig == null || sig.length == 0) {
            return null;
        }
        try {
            int paramCount = Signature.getParameterCount(new String(sig));
            return methodName + '#' + paramCount;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Builds the tracker key for an accepted proposal: {@code "methodName#paramCount"}
     * for methods, {@code "<init>#paramCount"} for constructors.
     *
     * @return the key; for methods without a resolvable signature the plain method
     *         name; {@code null} if no key can be determined
     */
    public static String buildMethodKey(CompletionProposal proposal) {
        if (proposal.getKind() == CompletionProposal.CONSTRUCTOR_INVOCATION) {
            char[] sig = proposal.getSignature();
            if (sig == null || sig.length == 0) {
                return null;
            }
            try {
                return CTOR_KEY_PREFIX + Signature.getParameterCount(new String(sig));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        char[] nameChars = proposal.getName();
        if (nameChars == null) {
            return null;
        }
        String methodName = new String(nameChars);
        String composite = buildCompositeKey(methodName, proposal);
        return composite != null ? composite : methodName;
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
