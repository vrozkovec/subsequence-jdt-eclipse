/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void testPlainIdentifier() {
        assertEquals("HashMap", CompletionUtils.getPrefixMatchingArea("HashMap"));
    }
}
