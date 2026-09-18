/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.junit.jupiter.api.Test;

/**
 * Tests the recovery that finishes a completion JDT abandoned half-way.
 * <p>
 * When JDT's parameter guesser throws a runtime exception out of
 * {@code AbstractJavaCompletionProposal.apply}, the required type proposal has already inserted the
 * type name but the argument list never arrives. The recovery inserts it — and must never insert it
 * twice when apply actually got far enough.
 */
class RecoverReplacementTest {

    private static final String NAME = "new ListProjektBudgetRealizatorPanel";

    @Test
    void insertsTheMissingArgumentList() throws Exception {
        IDocument document = new Document("return " + NAME + ";");
        int start = document.get().indexOf(';');

        assertTrue(SubsequenceProposal.applyMissingReplacement(document, start, 0, "(id)"));
        assertEquals("return " + NAME + "(id);", document.get());
    }

    @Test
    void doesNotInsertTwiceWhenApplyAlreadySucceeded() throws Exception {
        IDocument document = new Document("return " + NAME + "(id);");
        int start = document.get().indexOf("(id)");

        // JDT leaves the replacement offset at the start of the text it inserted
        assertFalse(SubsequenceProposal.applyMissingReplacement(document, start, 0, "(id)"));
        assertEquals("return " + NAME + "(id);", document.get());
    }

    @Test
    void replacesOverANonEmptyRange() throws Exception {
        IDocument document = new Document("return " + NAME + "xx;");
        int start = document.get().indexOf("xx");

        assertTrue(SubsequenceProposal.applyMissingReplacement(document, start, 2, "(id)"));
        assertEquals("return " + NAME + "(id);", document.get());
    }

    @Test
    void ignoresRangesOutsideTheDocument() throws Exception {
        IDocument document = new Document("return " + NAME + ";");
        String before = document.get();

        assertFalse(SubsequenceProposal.applyMissingReplacement(document, document.getLength() + 5, 0, "(id)"));
        assertFalse(SubsequenceProposal.applyMissingReplacement(document, 0, document.getLength() + 5, "(id)"));
        assertFalse(SubsequenceProposal.applyMissingReplacement(document, -1, 0, "(id)"));
        assertEquals(before, document.get());
    }

    @Test
    void ignoresNothingToInsert() throws Exception {
        IDocument document = new Document("return " + NAME + ";");
        String before = document.get();

        assertFalse(SubsequenceProposal.applyMissingReplacement(document, 0, 0, null));
        assertFalse(SubsequenceProposal.applyMissingReplacement(document, 0, 0, ""));
        assertEquals(before, document.get());
    }

    /** A replacement running past the end of the document must still be insertable. */
    @Test
    void insertsAtTheVeryEnd() throws Exception {
        IDocument document = new Document("return " + NAME);
        int start = document.getLength();

        assertTrue(SubsequenceProposal.applyMissingReplacement(document, start, 0, "(id)"));
        assertEquals("return " + NAME + "(id)", document.get());
    }
}
