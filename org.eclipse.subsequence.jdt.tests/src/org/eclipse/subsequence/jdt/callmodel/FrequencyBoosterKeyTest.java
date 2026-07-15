/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.jdt.core.CompletionProposal;
import org.junit.jupiter.api.Test;

/**
 * Tests for the tracker-key and type-name extraction in {@link FrequencyBooster}.
 * Uses synthetic {@link CompletionProposal}s (no workspace required).
 */
class FrequencyBoosterKeyTest {

    @Test
    void testMethodKeyIsOverloadAware() {
        CompletionProposal proposal = CompletionProposal.create(CompletionProposal.METHOD_REF, 0);
        proposal.setName("append".toCharArray());
        proposal.setSignature("(Ljava.lang.String;)Ljava.lang.StringBuilder;".toCharArray());

        assertEquals("append#1", FrequencyBooster.buildMethodKey(proposal));
    }

    @Test
    void testMethodKeyFallsBackToPlainNameWithoutSignature() {
        CompletionProposal proposal = CompletionProposal.create(CompletionProposal.METHOD_REF, 0);
        proposal.setName("toString".toCharArray());

        assertEquals("toString", FrequencyBooster.buildMethodKey(proposal));
    }

    @Test
    void testConstructorKeyUsesInitAndParamCount() {
        // Constructors must be keyed "<init>#paramCount", not by the type's simple
        // name — the boost lookup and the workspace analyzer use the same scheme.
        CompletionProposal proposal = CompletionProposal.create(CompletionProposal.CONSTRUCTOR_INVOCATION, 0);
        proposal.setName("HashMap".toCharArray());
        proposal.setSignature("(I)V".toCharArray());

        assertEquals("<init>#1", FrequencyBooster.buildMethodKey(proposal));
    }

    @Test
    void testConstructorKeyWithoutSignatureIsNull() {
        CompletionProposal proposal = CompletionProposal.create(CompletionProposal.CONSTRUCTOR_INVOCATION, 0);
        proposal.setName("HashMap".toCharArray());

        assertNull(FrequencyBooster.buildMethodKey(proposal));
    }

    @Test
    void testExtractTypeNameErasesGenerics() {
        CompletionProposal proposal = CompletionProposal.create(CompletionProposal.METHOD_REF, 0);
        proposal.setDeclarationSignature("Ljava.util.HashMap<Ljava.lang.String;Ljava.lang.String;>;".toCharArray());

        assertEquals("java.util.HashMap", FrequencyBooster.extractTypeName(proposal));
    }

    @Test
    void testExtractTypeNameWithoutDeclarationSignatureIsNull() {
        CompletionProposal proposal = CompletionProposal.create(CompletionProposal.METHOD_REF, 0);

        assertNull(FrequencyBooster.extractTypeName(proposal));
    }
}
