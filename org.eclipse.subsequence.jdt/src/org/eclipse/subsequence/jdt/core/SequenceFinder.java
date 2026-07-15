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

import static java.lang.Character.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Finds all subsequence matches of a token within a completion string,
 * respecting camelCase word boundaries and constant naming conventions.
 */
public class SequenceFinder {

    private static final int[] EMPTY_SEQUENCE = new int[0];

    /**
     * Safety cap on the number of candidate sequences tracked per token character.
     * Pathological inputs (long runs of repeated characters) otherwise multiply the
     * candidate set; beyond this bound additional alternatives are dropped, which can
     * only affect tie-breaking between equally valid matches of the same token.
     */
    private static final int MAX_ACTIVE_SEQUENCES = 64;

    private List<int[]> curSequences = new ArrayList<>();
    private List<int[]> nextSequences = new ArrayList<>();

    private int pCompletion, pToken;
    private String completion, token;

    public SequenceFinder(String completion, String token) {
        this.completion = completion;
        this.token = token;
    }

    public List<int[]> findSequences() {

        if (isConstantName(completion)) {
            rewriteCompletion();
        }

        if (!containsInOrderIgnoreCase()) {
            // Cheap O(n) pre-check: if the token's characters don't even occur in
            // order (ignoring case and word-boundary rules), no sequence can exist
            // and the exhaustive enumeration below can be skipped entirely.
            return curSequences;
        }

        int[] start = EMPTY_SEQUENCE;
        curSequences.add(start);

        for (pToken = 0; pToken < token.length(); pToken++) {
            char t = token.charAt(pToken);

            for (int[] activeSequence : curSequences) {
                boolean mustmatch = false;
                int startIndex = activeSequence.length == 0 ? 0 : activeSequence[activeSequence.length - 1] + 1;

                for (pCompletion = startIndex; pCompletion < completion.length(); pCompletion++) {
                    char c = completion.charAt(pCompletion);
                    if (!Character.isLetter(c)) {
                        if (c == t) {
                            addNewSubsequenceForNext(activeSequence);
                            continue;
                        }
                        mustmatch = true;
                        continue;
                    } else if (Character.isUpperCase(c)) {
                        mustmatch = true;
                    }

                    if (mustmatch && !isSameIgnoreCase(c, t)) {
                        jumpToEndOfWord();
                    } else if (isSameIgnoreCase(c, t)) {
                        addNewSubsequenceForNext(activeSequence);
                    }
                }
            }
            curSequences = nextSequences;
            nextSequences = new ArrayList<>();
        }

        // filter
        for (Iterator<int[]> it = curSequences.iterator(); it.hasNext();) {
            int[] candidate = it.next();
            if (candidate.length < token.length()) {
                it.remove();
                continue;
            }
        }

        return curSequences;
    }

    private void addNewSubsequenceForNext(int[] activeSequence) {
        if (nextSequences.size() >= MAX_ACTIVE_SEQUENCES) {
            return;
        }
        int[] copy = Arrays.copyOf(activeSequence, activeSequence.length + 1);
        copy[pToken] = pCompletion;
        nextSequences.add(copy);
    }

    /**
     * Returns whether the token's characters occur in order within the completion,
     * ignoring case. This is a necessary condition for any subsequence match, so a
     * negative result lets {@link #findSequences()} skip the full enumeration.
     */
    private boolean containsInOrderIgnoreCase() {
        int t = 0;
        for (int c = 0; c < completion.length() && t < token.length(); c++) {
            if (isSameIgnoreCase(completion.charAt(c), token.charAt(t))) {
                t++;
            }
        }
        return t == token.length();
    }

    private void rewriteCompletion() {
        StringBuilder sb = new StringBuilder();

        boolean toUpperCase = false;
        for (char c : completion.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(toUpperCase ? Character.toUpperCase(c) : Character.toLowerCase(c));
                toUpperCase = false;
            } else {
                sb.append(c);
                toUpperCase = true;
            }
        }
        completion = sb.toString();
    }

    private void jumpToEndOfWord() {
        for (pCompletion++; pCompletion < completion.length(); pCompletion++) {
            char next = completion.charAt(pCompletion);

            if (!isLetter(next)) {
                // . or _ word boundary found:
                break;
            }

            if (isUpperCase(next)) {
                pCompletion--;
                break;
            }
        }
    }

    private boolean isConstantName(String completion) {
        for (char c : completion.toCharArray()) {
            if (Character.isLetter(c) && Character.isLowerCase(c)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSameIgnoreCase(char c1, char c2) {
        if (c1 == c2) {
            return true;
        }
        c2 = isLowerCase(c2) ? toUpperCase(c2) : toLowerCase(c2);
        return c1 == c2;
    }

}
