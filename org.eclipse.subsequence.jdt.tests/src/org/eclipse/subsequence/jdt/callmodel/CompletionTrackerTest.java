/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CompletionTracker} normalization and memoization.
 * <p>
 * The tracker is a singleton shared by all tests, so each test uses unique
 * synthetic type names and asserts only on its own entries.
 */
class CompletionTrackerTest {

    @Test
    void testNormalizationScalesToMaxTotal() {
        CompletionTracker tracker = CompletionTracker.getInstance();
        String type = "test.tracker.NormalizationType";

        tracker.recordAcceptance(type, "often#0");
        tracker.recordAcceptance(type, "often#0");
        tracker.recordAcceptance(type, "rare#1");

        Map<String, Double> normalized = tracker.getNormalizedData().get(type);
        assertNotNull(normalized);
        assertEquals(1.0, normalized.get("often#0"));
        assertEquals(0.5, normalized.get("rare#1"));
    }

    @Test
    void testNormalizedDataIsMemoizedUntilChange() {
        CompletionTracker tracker = CompletionTracker.getInstance();
        String type = "test.tracker.MemoizationType";
        tracker.recordAcceptance(type, "method#0");

        Map<String, Map<String, Double>> first = tracker.getNormalizedData();
        Map<String, Map<String, Double>> second = tracker.getNormalizedData();
        assertSame(first, second, "expected the memoized instance while data is unchanged");

        tracker.recordAcceptance(type, "method#0");
        Map<String, Map<String, Double>> third = tracker.getNormalizedData();
        assertNotNull(third.get(type));
        assertTrue(first != third, "expected a rebuilt map after a data change");
    }

    @Test
    void testWorkspaceCountsReplaceDataAndSetFlag() {
        CompletionTracker tracker = CompletionTracker.getInstance();
        String type = "test.tracker.WorkspaceType";

        tracker.setWorkspaceCounts(Map.of(type, Map.of("scan#1", 3, "<init>#0", 1)));
        assertTrue(tracker.hasWorkspaceData());

        Map<String, Double> normalized = tracker.getNormalizedData().get(type);
        assertNotNull(normalized);
        assertEquals(1.0, normalized.get("scan#1"));
        assertEquals(1.0 / 3.0, normalized.get("<init>#0"), 1e-9);

        // Replacing with empty counts clears everything, including the flag
        tracker.setWorkspaceCounts(Map.of());
        assertFalse(tracker.hasWorkspaceData());
        assertFalse(tracker.getNormalizedData().containsKey(type));
    }
}
