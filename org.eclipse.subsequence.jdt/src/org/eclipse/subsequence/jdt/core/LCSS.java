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

import java.util.List;

/**
 * Longest Common Subsequence (LCSS) algorithm for matching user-typed prefixes
 * against completion proposals using subsequence matching.
 */
public final class LCSS {

    private LCSS() {
        // Not meant to be instantiated
    }

    private static final int[] EMPTY_SEQUENCE = new int[0];

    /**
     * Returns the best, i.e, the longest continuous sequence - or the empty sequence if no subsequence could be found.
     */
    public static int[] bestSubsequence(String completion, String token) {
        int bestScore = -1;
        int[] bestSequence = EMPTY_SEQUENCE;
        for (int[] s1 : findSequences(completion, token)) {
            int score = scoreSubsequence(s1);
            if (score > bestScore) {
                bestScore = score;
                bestSequence = s1;
            }
        }
        return bestSequence;
    }

    /**
     * Scores a subsequence by counting the number of consecutive character matches.
     */
    public static int scoreSubsequence(int[] s1) {
        int score = 0;
        for (int i = 0; i < s1.length - 1; i++) {
            if (s1[i] + 1 == s1[i + 1]) {
                score++;
            }
        }
        return score;
    }

    /**
     * Finds all possible subsequence matches of {@code token} within {@code completion}.
     */
    public static List<int[]> findSequences(String completion, String token) {
        return new SequenceFinder(completion, token).findSeqeuences();
    }

    /**
     * Returns {@code true} if {@code token} is a subsequence of {@code completion}.
     */
    public static boolean containsSubsequence(String completion, String token) {
        return !findSequences(completion, token).isEmpty();
    }
}
