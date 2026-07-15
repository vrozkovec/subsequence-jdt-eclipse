/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CallModelIndex#mergeMax(Map, Map)} — the merge used both for
 * combining the call and statics models and for layering user data on top.
 */
class CallModelIndexMergeTest {

    @Test
    void testBothEmpty() {
        Map<String, Double> merged = CallModelIndex.mergeMax(Map.of(), Map.of());
        assertTrue(merged.isEmpty());
    }

    @Test
    void testOneSideEmptyReturnsOtherDirectly() {
        Map<String, Double> calls = Map.of("get#1", 0.8);
        assertSame(calls, CallModelIndex.mergeMax(calls, Map.of()));
        assertSame(calls, CallModelIndex.mergeMax(Map.of(), calls));
    }

    @Test
    void testDisjointKeysAreUnioned() {
        // A type present in both the call model (instance methods) and the statics
        // model (static methods) must expose both — this was previously broken:
        // statics were consulted only when the call model had no entry at all.
        Map<String, Double> calls = Map.of("charAt#1", 0.9, "length#0", 0.7);
        Map<String, Double> statics = Map.of("valueOf#1", 0.95, "format#2", 0.6);

        Map<String, Double> merged = CallModelIndex.mergeMax(calls, statics);

        assertEquals(4, merged.size());
        assertEquals(0.9, merged.get("charAt#1"));
        assertEquals(0.95, merged.get("valueOf#1"));
    }

    @Test
    void testOverlappingKeysTakeMax() {
        Map<String, Double> model = Map.of("put#2", 0.4, "get#1", 0.9);
        Map<String, Double> user = Map.of("put#2", 0.8, "remove#1", 0.3);

        Map<String, Double> merged = CallModelIndex.mergeMax(model, user);

        assertEquals(3, merged.size());
        assertEquals(0.8, merged.get("put#2")); // user probability wins
        assertEquals(0.9, merged.get("get#1")); // model-only key kept
        assertEquals(0.3, merged.get("remove#1")); // user-only key kept
    }

    @Test
    void testInputsAreNotMutated() {
        Map<String, Double> a = Map.of("m#0", 0.1); // immutable — mergeMax must copy
        Map<String, Double> b = Map.of("n#0", 0.2);

        Map<String, Double> merged = CallModelIndex.mergeMax(a, b);

        assertEquals(2, merged.size());
        assertEquals(1, a.size());
        assertEquals(1, b.size());
    }
}
