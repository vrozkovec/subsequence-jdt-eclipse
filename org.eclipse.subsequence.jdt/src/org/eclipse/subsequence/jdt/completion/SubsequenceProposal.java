/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.text.java.AbstractJavaCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.JavaMethodCompletionProposal;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.LinkedModeUI;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.link.LinkedPositionGroup;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.subsequence.jdt.callmodel.CallModelIndex;
import org.eclipse.subsequence.jdt.callmodel.CompletionTracker;
import org.eclipse.subsequence.jdt.callmodel.FrequencyBooster;
import org.eclipse.subsequence.jdt.core.LCSS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.TextStyle;

/**
 * A wrapper around a standard {@link IJavaCompletionProposal} that overrides relevance and
 * display string to add subsequence match highlighting.
 */
public class SubsequenceProposal implements IJavaCompletionProposal, ICompletionProposalExtension,
        ICompletionProposalExtension2, ICompletionProposalExtension3, ICompletionProposalExtension5,
        ICompletionProposalExtension6 {

    private static final ILog LOG = Platform.getLog(SubsequenceProposal.class);

    private final IJavaCompletionProposal delegate;
    private final int adjustedRelevance;
    private final Styler highlightStyler;
    private final int highlightAdjustment;
    private final CompletionProposal coreProposal;
    private final String matchingArea;
    private int[] matchedIndices;

    /**
     * Caret position to use when a half-applied completion had to be finished by
     * {@link #recoverReplacement}; {@code null} when apply ran normally.
     */
    private Point recoveredSelection;

    /** Cached reflective handle to {@code AbstractJavaCompletionProposal.fTextViewer}. */
    private static volatile Field fTextViewerField;

    /**
     * Injects the {@link ITextViewer} into the delegate's private {@code fTextViewer}
     * field so that linked-mode setup (parameter placeholders, cursor positioning)
     * works when we bypass {@code ICompletionProposalExtension2.apply()}.
     */
    private static void injectTextViewer(AbstractJavaCompletionProposal target, ITextViewer viewer) {
        try {
            Field f = fTextViewerField;
            if (f == null) {
                f = AbstractJavaCompletionProposal.class.getDeclaredField("fTextViewer"); //$NON-NLS-1$
                f.setAccessible(true);
                fTextViewerField = f;
            }
            if (f.get(target) == null) {
                f.set(target, viewer);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Best effort — linked mode won't work but completion still applies
        }
    }

    /** Cached reflective handle to {@code AbstractJavaCompletionProposal.fToggleEating}. */
    private static volatile Field fToggleEatingField;

    /**
     * Sets the delegate's private {@code fToggleEating} flag. JDT only sets it inside the
     * viewer-level {@code apply()} that this wrapper bypasses, yet the flag feeds
     * {@code isToggleEating()} / {@code isInsertModeToggled()}, i.e. the delegate's own
     * insert-vs-overwrite decisions such as whether a method proposal appends an argument list.
     *
     * @return {@code true} if the flag was set, {@code false} if the field is not accessible
     */
    private static boolean setToggleEating(AbstractJavaCompletionProposal target, boolean toggleEating) {
        try {
            Field f = fToggleEatingField;
            if (f == null) {
                f = AbstractJavaCompletionProposal.class.getDeclaredField("fToggleEating"); //$NON-NLS-1$
                f.setAccessible(true);
                fToggleEatingField = f;
            }
            f.setBoolean(target, toggleEating);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Best effort — the delegate falls back to its own (preference-only) decision
            return false;
        }
    }

    /**
     * Returns {@code true} if the "Completion inserts" preference is active,
     * {@code false} if "Completion overwrites" is active.
     * Mirrors {@code AbstractJavaCompletionProposal.insertCompletion()}.
     */
    private static boolean insertCompletion() {
        IPreferenceStore preference = JavaPlugin.getDefault().getPreferenceStore();
        return preference.getBoolean(PreferenceConstants.CODEASSIST_INSERT_COMPLETION);
    }

    /**
     * Creates a new subsequence proposal wrapping the given delegate.
     *
     * @param delegate            the original JDT proposal
     * @param adjustedRelevance   the adjusted relevance score
     * @param matchedIndices      indices of matched characters in the display string
     * @param highlightStyler     styler for highlighting matched characters
     * @param highlightAdjustment offset adjustment for highlighting (e.g. for javadoc type refs)
     * @param coreProposal        the JDT core completion proposal (nullable — some proposals don't have one)
     * @param matchingArea        the normalized text used for subsequence matching (e.g. "getRecords")
     */
    public SubsequenceProposal(IJavaCompletionProposal delegate, int adjustedRelevance, int[] matchedIndices,
            Styler highlightStyler, int highlightAdjustment, CompletionProposal coreProposal, String matchingArea) {
        this.delegate = delegate;
        this.adjustedRelevance = adjustedRelevance;
        this.matchedIndices = matchedIndices;
        this.highlightStyler = highlightStyler;
        this.highlightAdjustment = highlightAdjustment;
        this.coreProposal = coreProposal;
        this.matchingArea = matchingArea;
    }

    @Override
    public int getRelevance() {
        return adjustedRelevance;
    }

    @Override
    public StyledString getStyledDisplayString() {
        StyledString original = delegate instanceof ICompletionProposalExtension6 ext6
                ? ext6.getStyledDisplayString()
                : null;
        if (original == null) {
            original = new StyledString(delegate.getDisplayString());
        }

        // Deep copy to avoid mutating the delegate's cached instance
        StyledString result = copyStyledString(original);

        // Apply bold highlighting to matched character positions on the copy
        if (matchedIndices != null && highlightStyler != null) {
            for (int index : matchedIndices) {
                int adjusted = index + highlightAdjustment;
                if (adjusted >= 0 && adjusted < result.length()) {
                    result.setStyle(adjusted, 1, highlightStyler);
                }
            }
        }

        return result;
    }

    /**
     * Creates a deep copy of a {@link StyledString} to avoid mutating cached instances.
     */
    private static StyledString copyStyledString(StyledString source) {
        StyledString copy = new StyledString(source.getString());
        for (StyleRange range : source.getStyleRanges()) {
            copy.setStyle(range.start, range.length, new Styler() {

                @Override
                public void applyStyles(TextStyle textStyle) {
                    textStyle.background = range.background;
                    textStyle.foreground = range.foreground;
                    textStyle.font = range.font;
                    textStyle.borderColor = range.borderColor;
                    textStyle.borderStyle = range.borderStyle;
                }
            });
        }
        return copy;
    }

    // --- Delegation methods ---

    @Override
    public void apply(IDocument document) {
        delegate.apply(document);
        recordAcceptance();
    }

    @Override
    public String getAdditionalProposalInfo() {
        return delegate.getAdditionalProposalInfo();
    }

    @Override
    public IContextInformation getContextInformation() {
        return delegate.getContextInformation();
    }

    @Override
    public String getDisplayString() {
        return delegate.getDisplayString();
    }

    @Override
    public Image getImage() {
        return delegate.getImage();
    }

    @Override
    public Point getSelection(IDocument document) {
        if (recoveredSelection != null) {
            // the delegate never reached the point where it sets its own selection, and its
            // fallback puts the caret in front of the text the recovery inserted
            return recoveredSelection;
        }
        return delegate.getSelection(document);
    }

    // --- ICompletionProposalExtension delegation ---

    @Override
    public void apply(IDocument document, char trigger, int offset) {
        if (delegate instanceof AbstractJavaCompletionProposal ajcp
                && delegate instanceof ICompletionProposalExtension ext) {
            fixReplacementLength(offset);
            applyDelegate(ajcp, ext, null, document, trigger, offset, false);
        } else if (delegate instanceof ICompletionProposalExtension ext) {
            ext.apply(document, trigger, offset);
        } else {
            delegate.apply(document);
        }
        recordAcceptance();
    }

    @Override
    public int getContextInformationPosition() {
        if (delegate instanceof ICompletionProposalExtension ext) {
            return ext.getContextInformationPosition();
        }
        return -1;
    }

    @Override
    public char[] getTriggerCharacters() {
        if (delegate instanceof ICompletionProposalExtension ext) {
            return ext.getTriggerCharacters();
        }
        return null;
    }

    @Override
    public boolean isValidFor(IDocument document, int offset) {
        if (delegate instanceof ICompletionProposalExtension ext) {
            return ext.isValidFor(document, offset);
        }
        return false;
    }

    // --- ICompletionProposalExtension2 delegation ---

    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {
        if (delegate instanceof AbstractJavaCompletionProposal ajcp
                && delegate instanceof ICompletionProposalExtension ext) {
            IDocument document = viewer.getDocument();
            // Ctrl toggles between insert and overwrite — same as
            // AbstractJavaCompletionProposal.MODIFIER_TOGGLE_COMPLETION_MODE
            boolean toggleEating = (stateMask & SWT.CTRL) != 0;

            // Compute replacement length respecting insert/overwrite mode.
            // Mirrors the logic in AbstractJavaCompletionProposal.apply(ITextViewer,...):
            //   insert mode  → replace prefix only (up to cursor)
            //   overwrite mode → extend past cursor to end of identifier
            fixReplacementLengthForViewer(ajcp, document, offset, toggleEating);

            // Inject the ITextViewer so linked mode (parameter placeholders) works.
            // The normal ICompletionProposalExtension2.apply() does this, but we can't
            // use that path because its validate() gate rejects subsequence-only matches.
            injectTextViewer(ajcp, viewer);
            applyDelegate(ajcp, ext, viewer, document, trigger, offset, toggleEating);
        } else {
            CompletionDiagnostics.logFallback(delegate, coreProposal, viewer.getDocument(), offset);
            fixReplacementLength(offset);
            if (delegate instanceof ICompletionProposalExtension2 ext2) {
                ext2.apply(viewer, trigger, stateMask, offset);
            } else if (delegate instanceof ICompletionProposalExtension ext) {
                ext.apply(viewer.getDocument(), trigger, offset);
            } else {
                delegate.apply(viewer.getDocument());
            }
        }
        recordAcceptance();
    }

    @Override
    public void selected(ITextViewer viewer, boolean smartToggle) {
        if (delegate instanceof AbstractJavaCompletionProposal ajcp) {
            // The delegate paints its overwrite-mode preview from its replacement length,
            // which may still reflect the core proposal's replace range (newer JDT core
            // parsers report a range up to the statement end). Sync it with the length
            // apply() will actually use so the preview covers only the identifier.
            Point selection = viewer.getSelectedRange();
            fixReplacementLengthForViewer(ajcp, viewer.getDocument(), selection.x, smartToggle);
        }
        if (delegate instanceof ICompletionProposalExtension2 ext2) {
            ext2.selected(viewer, smartToggle);
        }
    }

    @Override
    public void unselected(ITextViewer viewer) {
        if (delegate instanceof ICompletionProposalExtension2 ext2) {
            ext2.unselected(viewer);
        }
    }

    /**
     * Fixes the delegate's replacement length to cover text from its replacement
     * offset to the given cursor offset (insert-mode semantics).
     * <p>
     * Used by the {@code apply(IDocument, char, int)} path where no viewer or
     * stateMask is available.
     */
    private void fixReplacementLength(int offset) {
        if (delegate instanceof AbstractJavaCompletionProposal ajcp) {
            int replacementOffset = ajcp.getReplacementOffset();
            if (offset > replacementOffset) {
                ajcp.setReplacementLength(offset - replacementOffset);
            }
        }
    }

    /**
     * Sets the delegate's replacement length accounting for the Eclipse
     * "Completion inserts / Completion overwrites" preference and the Ctrl toggle.
     * <p>
     * In <em>insert</em> mode the replacement covers only the typed prefix
     * (from replacement offset to cursor). In <em>overwrite</em> mode it extends
     * past the cursor to the end of the Java identifier, replacing the suffix
     * that follows the cursor (e.g. "Enabled" in {@code setReq|Enabled}).
     *
     * @param toggleEating whether Ctrl is held, which toggles between the two modes
     */
    private static void fixReplacementLengthForViewer(
            AbstractJavaCompletionProposal ajcp, IDocument document, int offset, boolean toggleEating) {
        int replacementOffset = ajcp.getReplacementOffset();
        if (offset < replacementOffset) {
            return;
        }
        int end = offset;
        if (!(insertCompletion() ^ toggleEating)) {
            // Overwrite mode: extend replacement to end of identifier after cursor
            end = CompletionUtils.findIdentifierEnd(document, offset);
        }
        ajcp.setReplacementLength(end - replacementOffset);
    }

    /**
     * Applies the delegate through its document-level {@code apply()} after settling how it
     * must treat the argument list and the insert/overwrite toggle.
     * <p>
     * When the completed identifier is immediately followed by {@code (}, the user is
     * completing the name of an existing call, so a method or constructor proposal must insert
     * the bare name and leave the existing argument list alone. JDT's
     * {@code JavaMethodCompletionProposal.hasArgumentList()} only does that when it believes
     * it is in overwrite mode <em>and</em> the core completion carries no parentheses. Newer
     * JDT core parsers (which parse past the cursor) report a range up to the statement end
     * and therefore keep the parentheses, so both inputs are fixed up here: the trailing
     * {@code ()} is stripped from the core completion and the delegate's toggle-eating flag is
     * set so that its decision comes out as "overwrite" regardless of the preference.
     * <p>
     * Otherwise the Ctrl toggle is forwarded exactly as
     * {@code AbstractJavaCompletionProposal.apply(ITextViewer, ...)} would do; that method is
     * bypassed because its prefix validation rejects subsequence matches.
     */
    private void applyDelegate(AbstractJavaCompletionProposal ajcp, ICompletionProposalExtension ext,
            ITextViewer viewer, IDocument document, char trigger, int offset, boolean toggleEating) {
        boolean nameOnly = delegate instanceof JavaMethodCompletionProposal
                && isMethodInvocationKind(coreProposal)
                && CompletionUtils.parenFollowsIdentifier(document, offset);

        // captured before any of the fix-ups below, so the trace shows what JDT core handed us
        StringBuilder trace = CompletionDiagnostics.beforeApply(delegate, ajcp, coreProposal, document, offset,
                insertCompletion(), toggleEating, nameOnly);

        boolean toggle = toggleEating;
        if (nameOnly) {
            stripTrailingParentheses(coreProposal);
            // hasArgumentList() is (insertPreference ^ toggleEating) || completionEndsWithParen;
            // make the first term false whatever the preference is
            toggle = insertCompletion();
        }
        boolean toggleApplied = setToggleEating(ajcp, toggle);

        if (nameOnly) {
            String bare = String.valueOf(coreProposal.getCompletion());
            if (!toggleApplied) {
                // Reflection failed: at least insert the right text (the delegate may still
                // set up a degenerate linked position)
                ajcp.setReplacementString(bare);
            } else {
                // Guard against a replacement string cached with an argument list before
                // this call (the args variant ends with ')' or, for void methods, ';')
                String replacement = ajcp.getReplacementString();
                if (replacement.endsWith(")") || replacement.endsWith(";")) { //$NON-NLS-1$ //$NON-NLS-2$
                    ajcp.setReplacementString(bare);
                }
            }
        }

        try {
            ext.apply(document, trigger, offset);
        } catch (RuntimeException e) {
            // JDT's parameter guesser can hit a stale classpath jar and throw
            // after the required type proposal has already inserted the name;
            // finish the insertion rather than leaving a bare constructor
            recoverReplacement(ajcp, viewer, document, trace, e);
        } finally {
            // JDT resets the flag after applying as well
            setToggleEating(ajcp, false);
            CompletionDiagnostics.afterApply(trace, ajcp, document);
        }
    }

    /**
     * Finishes an apply that JDT abandoned half-way with a runtime exception.
     * <p>
     * {@code AbstractJavaCompletionProposal.apply} applies the required {@code TYPE_REF} proposal
     * <em>before</em> it asks for the replacement string, so when computing that string throws the
     * type name is already in the document but its argument list never arrives. Neither JDT's own
     * {@code catch (BadLocationException)} nor {@code ParameterGuessingProposal}'s
     * {@code catch (BadLocationException | BadPositionCategoryException)} covers a runtime
     * exception, so the completion is left silently half applied — observed as
     * {@code IllegalStateException: zip file closed} raised by the parameter guesser walking a
     * classpath jar that a Maven build had replaced underneath it.
     * <p>
     * Asking for the replacement string again normally succeeds, so insert it here.
     */
    private void recoverReplacement(AbstractJavaCompletionProposal ajcp, ITextViewer viewer, IDocument document,
            StringBuilder trace, RuntimeException failure) {
        CompletionDiagnostics.noteFailure(trace, failure);
        try {
            int start = ajcp.getReplacementOffset();
            String replacement = ajcp.getReplacementString();
            if (applyMissingReplacement(document, start, ajcp.getReplacementLength(), replacement)) {
                recoveredSelection = selectionAfterRecovery(start, replacement);
                enterLinkedMode(viewer, document, recoveredSelection, start + replacement.length());
                LOG.warn("Completion was abandoned mid-apply; inserted the missing replacement text", failure); //$NON-NLS-1$
            }
        } catch (BadLocationException | RuntimeException e) {
            // the retry failed too — leave the document as JDT left it rather than corrupt it
            LOG.warn("Completion was abandoned mid-apply and could not be recovered", failure); //$NON-NLS-1$
        }
    }

    /**
     * Puts the recovered argument into linked mode, the way the delegate would have.
     * <p>
     * Without it the argument would merely be <em>selected</em>, so Enter would replace it with a
     * newline instead of leaving the call and moving past the closing parenthesis.
     *
     * @param selection the argument region to link, empty when there is nothing to overtype
     * @param exit      offset to jump to when the user leaves linked mode
     */
    private void enterLinkedMode(ITextViewer viewer, IDocument document, Point selection, int exit) {
        if (viewer == null || selection == null || selection.y <= 0) {
            return;
        }
        try {
            LinkedPositionGroup group = new LinkedPositionGroup();
            group.addPosition(new LinkedPosition(document, selection.x, selection.y, LinkedPositionGroup.NO_STOP));

            LinkedModeModel model = new LinkedModeModel();
            model.addGroup(group);
            model.forceInstall();

            LinkedModeUI ui = new LinkedModeUI(model, viewer);
            ui.setExitPosition(viewer, exit, 0, Integer.MAX_VALUE);
            ui.setCyclingMode(LinkedModeUI.CYCLE_WHEN_NO_PARENT);
            ui.enter();

            IRegion selected = ui.getSelectedRegion();
            if (selected != null) {
                recoveredSelection = new Point(selected.getOffset(), selected.getLength());
            }
        } catch (BadLocationException | RuntimeException e) {
            // linked mode is a convenience — the text is already correct without it
            recoveredSelection = selection;
        }
    }

    /**
     * Returns the selection to leave behind once a half-applied completion has been finished by
     * hand.
     * <p>
     * When apply succeeds, JDT hands the guessed argument to linked mode with it selected so that
     * overtyping replaces it. Linked mode is exactly what the aborted apply never reached, so
     * approximate it: select the first argument inside the inserted argument list, put the caret
     * between empty parentheses, or fall back to the end of the inserted text when there is no
     * argument list at all.
     */
    static Point selectionAfterRecovery(int start, String replacement) {
        if (replacement == null || replacement.isEmpty()) {
            return new Point(start, 0);
        }
        int open = replacement.indexOf('(');
        int close = replacement.lastIndexOf(')');
        if (open < 0 || close < open) {
            return new Point(start + replacement.length(), 0);
        }
        int argumentStart = open + 1;
        int argumentEnd = close;
        int comma = replacement.indexOf(',', argumentStart);
        if (comma >= 0 && comma < close) {
            // several arguments: select the first, as linked mode would have
            argumentEnd = comma;
        }
        return new Point(start + argumentStart, argumentEnd - argumentStart);
    }

    /**
     * Inserts {@code replacement} over {@code [start, start + length)} unless it is already there,
     * so that recovering a half-applied completion cannot insert the text twice.
     *
     * @return whether the document was changed
     */
    static boolean applyMissingReplacement(IDocument document, int start, int length, String replacement)
            throws BadLocationException {
        if (replacement == null || replacement.isEmpty() || start < 0 || length < 0
                || start + length > document.getLength()) {
            return false;
        }
        // JDT leaves the replacement offset at the start of the text it inserted, so a replacement
        // that already sits there means apply got far enough and must not be repeated
        if (start + replacement.length() <= document.getLength()
                && document.get(start, replacement.length()).equals(replacement)) {
            return false;
        }
        document.replace(start, length, replacement);
        return true;
    }

    /**
     * Returns whether the proposal inserts a method or constructor invocation, i.e. one of the
     * kinds for which JDT may synthesize an argument list.
     */
    private static boolean isMethodInvocationKind(CompletionProposal proposal) {
        if (proposal == null) {
            return false;
        }
        return switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER,
                    CompletionProposal.CONSTRUCTOR_INVOCATION -> true;
            default -> false;
        };
    }

    /**
     * Removes a trailing {@code ()} from the core completion string, yielding what JDT core
     * itself proposes when it sees the following {@code (} (the bare selector, or an empty
     * completion for constructor invocations).
     */
    private static void stripTrailingParentheses(CompletionProposal proposal) {
        char[] completion = proposal.getCompletion();
        int length = completion.length;
        if (length >= 2 && completion[length - 2] == '(' && completion[length - 1] == ')') {
            proposal.setCompletion(Arrays.copyOf(completion, length - 2));
        }
    }

    @Override
    public boolean validate(IDocument document, int offset, DocumentEvent event) {
        if (matchingArea != null && !matchingArea.isEmpty()) {
            // Use subsequence matching instead of prefix matching
            String currentPrefix = extractPrefix(document, offset);
            if (currentPrefix.isEmpty()) {
                // Empty prefix is valid when user deletes all typed chars (event text is empty/null)
                // but NOT when a non-identifier character was just typed (e.g., '(', ';')
                if (event != null && event.getText() != null && !event.getText().isEmpty()) {
                    return false;
                }
                return true;
            }
            int[] newSequence = LCSS.bestSubsequence(matchingArea, currentPrefix);
            if (newSequence.length > 0) {
                matchedIndices = newSequence; // update highlighting for next render
                // Track document changes to keep replacement length in sync, mirroring
                // AbstractJavaCompletionProposal.validate() — needed so the delegate's
                // selected() can show the correct overwrite-mode highlight.
                if (event != null && delegate instanceof AbstractJavaCompletionProposal ajcp) {
                    int delta = (event.getText() == null ? 0 : event.getText().length()) - event.getLength();
                    ajcp.setReplacementLength(Math.max(ajcp.getReplacementLength() + delta, 0));
                }
                return true;
            }
            return false;
        }
        // Fall back to delegate for proposals without a matching area
        if (delegate instanceof ICompletionProposalExtension2 ext2) {
            return ext2.validate(document, offset, event);
        }
        return false;
    }

    /**
     * Extracts the current Java identifier prefix by scanning backwards from the given offset.
     */
    private String extractPrefix(IDocument document, int offset) {
        try {
            int start = offset;
            while (start > 0 && Character.isJavaIdentifierPart(document.getChar(start - 1))) {
                start--;
            }
            return document.get(start, offset - start);
        } catch (BadLocationException e) {
            return ""; //$NON-NLS-1$
        }
    }

    // --- ICompletionProposalExtension3 delegation ---

    @Override
    public IInformationControlCreator getInformationControlCreator() {
        if (delegate instanceof ICompletionProposalExtension3 ext3) {
            return ext3.getInformationControlCreator();
        }
        return null;
    }

    @Override
    public CharSequence getPrefixCompletionText(IDocument document, int completionOffset) {
        if (delegate instanceof ICompletionProposalExtension3 ext3) {
            return ext3.getPrefixCompletionText(document, completionOffset);
        }
        return null;
    }

    @Override
    public int getPrefixCompletionStart(IDocument document, int completionOffset) {
        if (delegate instanceof ICompletionProposalExtension3 ext3) {
            return ext3.getPrefixCompletionStart(document, completionOffset);
        }
        return completionOffset;
    }

    // --- ICompletionProposalExtension5 delegation ---

    @Override
    public Object getAdditionalProposalInfo(org.eclipse.core.runtime.IProgressMonitor monitor) {
        if (delegate instanceof ICompletionProposalExtension5 ext5) {
            return ext5.getAdditionalProposalInfo(monitor);
        }
        return delegate.getAdditionalProposalInfo();
    }

    /**
     * Records the acceptance of this proposal with the {@link CompletionTracker}.
     * <p>
     * Only records method and constructor proposals that have a valid core proposal
     * with a declaration signature and name.
     */
    private void recordAcceptance() {
        try {
            if (coreProposal == null) {
                return;
            }

            int kind = coreProposal.getKind();
            if (kind != CompletionProposal.METHOD_REF
                    && kind != CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER
                    && kind != CompletionProposal.METHOD_NAME_REFERENCE
                    && kind != CompletionProposal.CONSTRUCTOR_INVOCATION) {
                return;
            }

            String typeName = FrequencyBooster.extractTypeName(coreProposal);
            if (typeName == null) {
                return;
            }

            // Resolve unqualified type names via the reverse index
            if (typeName.indexOf('.') < 0) {
                String resolved = CallModelIndex.getInstance().resolveSimpleName(typeName);
                if (resolved == null) {
                    return;
                }
                typeName = resolved;
            }

            String methodKey = FrequencyBooster.buildMethodKey(coreProposal);
            CompletionTracker.getInstance().recordAcceptance(typeName, methodKey);
        } catch (Exception e) {
            // must never break completion
        }
    }

    /**
     * Returns the wrapped delegate proposal.
     */
    public IJavaCompletionProposal getDelegate() {
        return delegate;
    }
}
