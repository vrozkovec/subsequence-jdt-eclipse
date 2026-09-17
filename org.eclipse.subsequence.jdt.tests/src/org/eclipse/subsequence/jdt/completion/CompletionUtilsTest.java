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
 * Tests for the {@link CompletionUtils} utility class.
 */
class CompletionUtilsTest {

    @Test
    void testSimpleMethodName() {
        assertEquals("add", CompletionUtils.getPrefixMatchingArea("add(Object o)"));
    }

    @Test
    void testConstructor() {
        assertEquals("ArrayList", CompletionUtils.getPrefixMatchingArea("ArrayList(Collection c)"));
    }

    @Test
    void testPackageName() {
        assertEquals("org.eclipse.other", CompletionUtils.getPrefixMatchingArea("org.eclipse.other"));
    }

    @Test
    void testHtmlOpenTag() {
        assertEquals("blockquote", CompletionUtils.getPrefixMatchingArea("<blockquote>"));
    }

    @Test
    void testHtmlCloseTag() {
        assertEquals("blockquote", CompletionUtils.getPrefixMatchingArea("</blockquote>"));
    }

    @Test
    void testJavadocLink() {
        assertEquals("Example", CompletionUtils.getPrefixMatchingArea("{@link Example}"));
    }

    @Test
    void testJavadocLinkWithMethod() {
        assertEquals("method", CompletionUtils.getPrefixMatchingArea("{@link Example#method()}"));
    }

    @Test
    void testJavadocValue() {
        assertEquals("EMPTY_LIST", CompletionUtils.getPrefixMatchingArea("{@value Collections#EMPTY_LIST}"));
    }

    @Test
    void testJavadocValueWithoutHash() {
        assertEquals("Example", CompletionUtils.getPrefixMatchingArea("{@value Example}"));
    }

    @Test
    void testPlainIdentifier() {
        assertEquals("HashMap", CompletionUtils.getPrefixMatchingArea("HashMap"));
    }

    // --- findIdentifierEnd ---

    @Test
    void testFindIdentifierEndAtEndOfIdentifier() {
        IDocument doc = new Document("JpaDb.deleteAll(Foo.class);");
        int cursor = "JpaDb.deleteAll".length();
        assertEquals(cursor, CompletionUtils.findIdentifierEnd(doc, cursor));
    }

    @Test
    void testFindIdentifierEndInsideIdentifier() {
        IDocument doc = new Document("JpaDb.deleteAllJoin(Foo.class);");
        int cursor = "JpaDb.deleteAl".length();
        assertEquals("JpaDb.deleteAllJoin".length(), CompletionUtils.findIdentifierEnd(doc, cursor));
    }

    @Test
    void testFindIdentifierEndAtDocumentEnd() {
        IDocument doc = new Document("JpaDb.deleteAll");
        assertEquals(doc.getLength(), CompletionUtils.findIdentifierEnd(doc, "JpaDb.de".length()));
        assertEquals(doc.getLength(), CompletionUtils.findIdentifierEnd(doc, doc.getLength()));
    }

    @Test
    void testFindIdentifierEndAtNonIdentifierChar() {
        IDocument doc = new Document("foo (bar)");
        assertEquals(3, CompletionUtils.findIdentifierEnd(doc, 3)); // at ' '
        assertEquals(4, CompletionUtils.findIdentifierEnd(doc, 4)); // at '('
    }

    // --- parenFollowsIdentifier ---

    @Test
    void testParenFollowsIdentifierAtEndOfName() {
        IDocument doc = new Document("JpaDb.deleteAll(Foo.class);");
        assertTrue(CompletionUtils.parenFollowsIdentifier(doc, "JpaDb.deleteAll".length()));
    }

    @Test
    void testParenFollowsIdentifierInsideName() {
        IDocument doc = new Document("JpaDb.deleteAllJoin(Foo.class);");
        assertTrue(CompletionUtils.parenFollowsIdentifier(doc, "JpaDb.deleteAl".length()));
    }

    @Test
    void testParenFollowsIdentifierAtParen() {
        IDocument doc = new Document("foo(bar)");
        assertTrue(CompletionUtils.parenFollowsIdentifier(doc, 3));
    }

    @Test
    void testParenDoesNotFollowWhenStatementEnds() {
        IDocument doc = new Document("JpaDb.deleteAll;");
        assertFalse(CompletionUtils.parenFollowsIdentifier(doc, "JpaDb.deleteAll".length()));
    }

    @Test
    void testParenDoesNotFollowAcrossWhitespace() {
        IDocument doc = new Document("JpaDb.deleteAll (Foo.class);");
        assertFalse(CompletionUtils.parenFollowsIdentifier(doc, "JpaDb.deleteAll".length()));
    }

    @Test
    void testParenDoesNotFollowAtDocumentEnd() {
        IDocument doc = new Document("JpaDb.deleteAll");
        assertFalse(CompletionUtils.parenFollowsIdentifier(doc, "JpaDb.de".length()));
        assertFalse(CompletionUtils.parenFollowsIdentifier(doc, doc.getLength()));
    }
}
