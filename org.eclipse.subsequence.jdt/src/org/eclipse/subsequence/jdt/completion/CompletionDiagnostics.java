/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.AbstractJavaCompletionProposal;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.subsequence.jdt.preferences.SubsequencePreferences;

/**
 * Appends one trace per accepted completion to the file named by the
 * {@code subwords_diagnostic_log_path} preference, so an intermittent misbehaviour can be inspected
 * after the fact instead of reproduced.
 * <p>
 * Switched off unless that preference holds a path. Nothing here may throw, and nothing may be
 * computed that the completion itself would not have computed anyway — in particular the delegate's
 * replacement string is only read <em>after</em> it has been applied, because reading it earlier
 * would cache it and change the very behaviour being traced.
 */
@SuppressWarnings("restriction")
final class CompletionDiagnostics {

    private static final ILog LOG = Platform.getLog(CompletionDiagnostics.class);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"); //$NON-NLS-1$

    /** One-shot guard so an unwritable log path is reported once rather than per completion. */
    private static final AtomicBoolean writeFailureLogged = new AtomicBoolean();

    private CompletionDiagnostics() {
        // Not meant to be instantiated
    }

    /**
     * Returns the trace collected before the delegate is applied, or {@code null} when diagnostic
     * logging is switched off — in which case no further work is done.
     */
    static StringBuilder beforeApply(Object delegate, AbstractJavaCompletionProposal ajcp,
            CompletionProposal coreProposal, IDocument document, int offset, boolean insertCompletion,
            boolean toggleEating, boolean nameOnly) {
        if (SubsequencePreferences.getDiagnosticLogPath().isEmpty()) {
            return null;
        }
        try {
            StringBuilder trace = new StringBuilder(512);
            trace.append("=== ").append(LocalDateTime.now().format(TIMESTAMP)) //$NON-NLS-1$
                    .append(" subsequence completion apply ===\n"); //$NON-NLS-1$
            append(trace, "caret", offset + " -> " + lineAt(document, offset)); //$NON-NLS-1$ //$NON-NLS-2$
            append(trace, "mode", (insertCompletion ? "inserts" : "overwrites") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ", toggleEating=" + toggleEating + ", nameOnly=" + nameOnly); //$NON-NLS-1$ //$NON-NLS-2$
            append(trace, "delegate", delegate.getClass().getName()); //$NON-NLS-1$
            append(trace, "core", describe(coreProposal)); //$NON-NLS-1$
            appendRequired(trace, coreProposal);
            // offsets are safe to read early; the replacement string is not
            append(trace, "range before", "offset=" + ajcp.getReplacementOffset() //$NON-NLS-1$ //$NON-NLS-2$
                    + " length=" + ajcp.getReplacementLength()); //$NON-NLS-1$
            return trace;
        } catch (RuntimeException e) {
            // diagnostics must never break completion
            return null;
        }
    }

    /**
     * Completes the trace started by {@link #beforeApply} with what actually landed in the document
     * and appends it to the configured log file.
     */
    static void afterApply(StringBuilder trace, AbstractJavaCompletionProposal ajcp, IDocument document) {
        if (trace == null) {
            return;
        }
        try {
            int offset = ajcp.getReplacementOffset();
            append(trace, "replacement", quote(ajcp.getReplacementString())); //$NON-NLS-1$
            append(trace, "range after", "offset=" + offset + " length=" + ajcp.getReplacementLength()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            append(trace, "result", lineAt(document, offset)); //$NON-NLS-1$
            trace.append('\n');
            write(trace.toString());
        } catch (RuntimeException e) {
            // diagnostics must never break completion
        }
    }

    /**
     * Records an apply that did not go through the {@link AbstractJavaCompletionProposal} path, so
     * that an empty log unambiguously means the accepted proposal was not one of ours.
     */
    static void logFallback(Object delegate, CompletionProposal coreProposal, IDocument document, int offset) {
        if (SubsequencePreferences.getDiagnosticLogPath().isEmpty()) {
            return;
        }
        try {
            StringBuilder trace = new StringBuilder(256);
            trace.append("=== ").append(LocalDateTime.now().format(TIMESTAMP)) //$NON-NLS-1$
                    .append(" subsequence completion apply (fallback path) ===\n"); //$NON-NLS-1$
            append(trace, "caret", offset + " -> " + lineAt(document, offset)); //$NON-NLS-1$ //$NON-NLS-2$
            append(trace, "delegate", delegate.getClass().getName()); //$NON-NLS-1$
            append(trace, "core", describe(coreProposal)); //$NON-NLS-1$
            trace.append('\n');
            write(trace.toString());
        } catch (RuntimeException e) {
            // diagnostics must never break completion
        }
    }

    private static void append(StringBuilder trace, String label, String value) {
        trace.append("  ").append(label); //$NON-NLS-1$
        for (int i = label.length(); i < 13; i++) {
            trace.append(' ');
        }
        trace.append(": ").append(value).append('\n'); //$NON-NLS-1$
    }

    private static void appendRequired(StringBuilder trace, CompletionProposal coreProposal) {
        if (coreProposal == null) {
            return;
        }
        CompletionProposal[] required = coreProposal.getRequiredProposals();
        if (required == null) {
            return;
        }
        for (int i = 0; i < required.length; i++) {
            append(trace, "required[" + i + ']', describe(required[i])); //$NON-NLS-1$
        }
    }

    private static String describe(CompletionProposal proposal) {
        if (proposal == null) {
            return "<none>"; //$NON-NLS-1$
        }
        return kindName(proposal.getKind())
                + " name=" + text(proposal.getName()) //$NON-NLS-1$
                + " completion=" + quote(text(proposal.getCompletion())) //$NON-NLS-1$
                + " replace=[" + proposal.getReplaceStart() + ',' + proposal.getReplaceEnd() + ')' //$NON-NLS-1$
                + " token=[" + proposal.getTokenStart() + ',' + proposal.getTokenEnd() + ')'; //$NON-NLS-1$
    }

    private static String kindName(int kind) {
        return switch (kind) {
            case CompletionProposal.CONSTRUCTOR_INVOCATION -> "CONSTRUCTOR_INVOCATION"; //$NON-NLS-1$
            case CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION -> "ANON_CTOR_INVOCATION"; //$NON-NLS-1$
            case CompletionProposal.ANONYMOUS_CLASS_DECLARATION -> "ANON_CLASS_DECLARATION"; //$NON-NLS-1$
            case CompletionProposal.METHOD_REF -> "METHOD_REF"; //$NON-NLS-1$
            case CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER -> "METHOD_REF_CASTED_RECEIVER"; //$NON-NLS-1$
            case CompletionProposal.METHOD_NAME_REFERENCE -> "METHOD_NAME_REFERENCE"; //$NON-NLS-1$
            case CompletionProposal.TYPE_REF -> "TYPE_REF"; //$NON-NLS-1$
            case CompletionProposal.TYPE_IMPORT -> "TYPE_IMPORT"; //$NON-NLS-1$
            case CompletionProposal.FIELD_REF -> "FIELD_REF"; //$NON-NLS-1$
            default -> "kind#" + kind; //$NON-NLS-1$
        };
    }

    /** Returns the line containing {@code offset} with a {@code |} marking the offset itself. */
    private static String lineAt(IDocument document, int offset) {
        try {
            IRegion line = document.getLineInformationOfOffset(Math.min(Math.max(offset, 0), document.getLength()));
            String text = document.get(line.getOffset(), line.getLength());
            int column = Math.min(Math.max(offset - line.getOffset(), 0), text.length());
            return quote(text.substring(0, column).stripLeading() + '|' + text.substring(column));
        } catch (BadLocationException | RuntimeException e) {
            return "<unavailable>"; //$NON-NLS-1$
        }
    }

    private static String text(char[] chars) {
        return chars == null ? "<null>" : new String(chars); //$NON-NLS-1$
    }

    private static String quote(String value) {
        return '\'' + (value == null ? "<null>" : value.replace("\n", "\\n")) + '\''; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void write(String entry) {
        String path = SubsequencePreferences.getDiagnosticLogPath();
        if (path.isEmpty()) {
            return;
        }
        try {
            Files.writeString(Path.of(path), entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            if (writeFailureLogged.compareAndSet(false, true)) {
                LOG.warn("Could not append to the subsequence diagnostic log at " + path, e); //$NON-NLS-1$
            }
        }
    }
}
