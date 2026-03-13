/**
 * Copyright (c) 2010, 2013 Darmstadt University of Technology.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marcel Bruch - initial API and implementation.
 */
package org.eclipse.subsequence.jdt.completion;

import static java.lang.Character.isJavaIdentifierPart;

/**
 * Utility methods for extracting the prefix-matching area from completion display strings.
 * <p>
 * Extracted from the original {@code CompletionContexts} class in Eclipse Recommenders.
 */
public final class CompletionUtils {

    private CompletionUtils() {
        // Not meant to be instantiated
    }

    /**
     * Given a completion's display string like {@code "ArrayList()"}, returns the substring used to match a
     * user-entered prefix against the completion, i.e., {@code "ArrayList"} without brackets.
     * <p>
     * Examples:
     *
     * <pre>
     * add(Object o)                  --> add
     * ArrayList(Collection c)        --> ArrayList
     * org.eclipse.other              --> org.eclipse.other
     * {@link}                        --> {@link}
     * {@link Example}                --> Example
     * {@link Example#method()}       --> method
     * {@value Collections#EMPTY_LIST}--> EMPTY_LIST
     * &lt;blockquote&gt;            --> blockquote
     * &lt;/blockquote&gt;           --> blockquote
     * </pre>
     */
    public static String getPrefixMatchingArea(String displayString) {
        displayString = stripHtmlTagDelimiters(displayString);
        displayString = stripValueOrLinkDelimiters(displayString);

        int end = displayString.length();
        for (int i = 0; i < displayString.length(); i++) {
            char c = displayString.charAt(i);
            if (!isJavaIdentifierLike(c) && c != '.') {
                end = i;
                break;
            }
        }
        return displayString.substring(0, end);
    }

    private static String stripHtmlTagDelimiters(String string) {
        if (string.startsWith("<") && string.endsWith(">")) { //$NON-NLS-1$ //$NON-NLS-2$
            boolean isClosingTag = string.charAt(1) == '/';
            return string.substring(isClosingTag ? 2 : 1, string.length() - 1);
        }
        return string;
    }

    private static String stripValueOrLinkDelimiters(String string) {
        if (string.startsWith("{@value ") && string.endsWith("}")) { //$NON-NLS-1$ //$NON-NLS-2$
            int lastIndexOfHash = string.lastIndexOf('#');
            int start = lastIndexOfHash < 0 ? "{@value ".length() : lastIndexOfHash; //$NON-NLS-1$
            return string.substring(start + 1, string.length() - 1);
        } else if (string.startsWith("{@link ") && string.endsWith("}")) { //$NON-NLS-1$ //$NON-NLS-2$
            int lastIndexOfHash = string.lastIndexOf('#');
            int start = lastIndexOfHash < 0 ? "{@link ".length() : lastIndexOfHash + 1; //$NON-NLS-1$
            return string.substring(start, string.length() - 1);
        }
        return string;
    }

    private static boolean isJavaIdentifierLike(char c) {
        return isJavaIdentifierPart(c) || c == '#' || c == '@' || c == '{' || c == '}';
    }
}
