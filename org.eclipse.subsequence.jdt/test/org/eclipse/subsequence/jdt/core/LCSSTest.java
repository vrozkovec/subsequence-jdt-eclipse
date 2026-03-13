/**
 * Copyright (c) 2010, 2012 Darmstadt University of Technology.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marcel Bruch - initial API and implementation.
 */
package org.eclipse.subsequence.jdt.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for the LCSS subsequence matching algorithm.
 * Ported from the original Eclipse Recommenders LCSSTest to JUnit 5.
 */
class LCSSTest {

    @Test
    void testOneWord() {
        assertEquals(1, LCSS.findSequences("", "").size());
        assertEquals(0, LCSS.findSequences("", "a").size());
        assertEquals(1, LCSS.findSequences("a", "").size());
        assertEquals(1, LCSS.findSequences("a", "a").size());
        assertEquals(0, LCSS.findSequences("a", "b").size());

        assertEquals(2, LCSS.findSequences("aa", "a").size());
        assertEquals(0, LCSS.findSequences("aa", "b").size());

        assertEquals(1, LCSS.findSequences("aa", "aa").size());
        assertEquals(0, LCSS.findSequences("aa", "ba").size());

        assertEquals(1, LCSS.findSequences("aaa", "aaa").size());
        assertEquals(4, LCSS.findSequences("aaaa", "aaa").size());

        assertEquals(1, LCSS.findSequences("ab", "ab").size());
        assertEquals(0, LCSS.findSequences("ab", "de").size());

        assertEquals(1, LCSS.findSequences("abcd", "ab").size());
        assertEquals(1, LCSS.findSequences("abcd", "bc").size());
        assertEquals(1, LCSS.findSequences("abcd", "cd").size());

        assertEquals(0, LCSS.findSequences("ab", "abcd").size());
        assertEquals(0, LCSS.findSequences("bc", "abcd").size());
        assertEquals(0, LCSS.findSequences("cd", "abcd").size());

        assertEquals(1, LCSS.findSequences("abcd", "ab").size());
        assertEquals(1, LCSS.findSequences("abcd", "abc").size());
        assertEquals(1, LCSS.findSequences("abcd", "bcd").size());

        assertEquals(1, LCSS.findSequences("xyz", "xy").size());
        assertEquals(0, LCSS.findSequences("xy", "xyxy").size());
        assertEquals(3, LCSS.findSequences("xyxy", "xy").size());
        assertEquals(3, LCSS.findSequences("xyzabxy", "xy").size());
    }

    @Test
    void testMultipleWords() {
        assertEquals(1, LCSS.findSequences("aaBaaCaaDaa", "caa").size());
        assertEquals(1, LCSS.findSequences("aaBaaCaaDaa", "cdaa").size());
        assertEquals(2, LCSS.findSequences("aaBaaCaaDaa", "badaa").size()); // ba_* & b_a*
        assertEquals(1, LCSS.findSequences("initializeDialogUnits", "dial").size());
        assertEquals(2, LCSS.findSequences("setDateData", "dat").size());
    }

    @Test
    void testBug001() {
        List<int[]> s = LCSS.findSequences("newLabeledStatement", "le");
        assertEquals(2, s.size());
        assertArrayEquals(new int[] { 3, 6 }, s.get(0));
        assertArrayEquals(new int[] { 3, 8 }, s.get(1));
    }

    @Test
    void testPackages() {
        List<int[]> s = LCSS.findSequences("com.apple.coxcurrent", "comcon");
        assertEquals(1, s.size());
    }

    @Test
    void testConstants() {
        List<int[]> s = LCSS.findSequences("DLM_IMG_HELP", "el");
        assertEquals(0, s.size());
    }

    @Test
    void testUnderscore() {
        List<int[]> s = LCSS.findSequences("FF_HELP", "FF_");
        assertEquals(1, s.size());
        assertArrayEquals(new int[] { 0, 1, 2 }, s.get(0));
    }

    @Test
    void testTypeNames() {
        assertEquals(1, LCSS.findSequences("StringBuilder", "sb").size());
        assertEquals(1, LCSS.findSequences("StringBuilder", "sbu").size());
        assertEquals(0, LCSS.findSequences("String", "tri").size());
        assertEquals(0, LCSS.findSequences("LinkedList", "inkedList").size());
        assertEquals(1, LCSS.findSequences("ArrayList", "list").size());
    }

    @Test
    void testSubsequence01() {
        assertEquals(1, LCSS.findSequences("createTempFile", "tmp").size());
    }

    @Test
    void testContainsSubsequence() {
        assertTrue(LCSS.containsSubsequence("HashMap", "hm"));
        assertTrue(LCSS.containsSubsequence("ArrayList", "al"));
        assertFalse(LCSS.containsSubsequence("String", "xyz"));
    }

    @Test
    void testScoreSubsequence() {
        // Consecutive matches score higher
        int[] consecutive = { 0, 1, 2 };
        int[] nonConsecutive = { 0, 2, 4 };
        assertTrue(LCSS.scoreSubsequence(consecutive) > LCSS.scoreSubsequence(nonConsecutive));
    }

    @Test
    void testBestSubsequence() {
        int[] best = LCSS.bestSubsequence("StringBuilder", "sb");
        assertEquals(2, best.length);
        // 'S' at index 0, 'b' at index 6 (String*B*uilder)
        assertEquals(0, best[0]);
    }
}
