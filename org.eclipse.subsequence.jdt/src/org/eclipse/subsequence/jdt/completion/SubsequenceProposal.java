/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.subsequence.jdt.callmodel.CompletionTracker;
import org.eclipse.subsequence.jdt.callmodel.FrequencyBooster;
import org.eclipse.subsequence.jdt.core.LCSS;
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

    private final IJavaCompletionProposal delegate;
    private final int adjustedRelevance;
    private final Styler highlightStyler;
    private final int highlightAdjustment;
    private final CompletionProposal coreProposal;
    private final String matchingArea;
    private int[] matchedIndices;

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
        StyledString original;
        if (delegate instanceof ICompletionProposalExtension6 ext6) {
            original = ext6.getStyledDisplayString();
        } else {
            original = new StyledString(delegate.getDisplayString());
        }

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
        return delegate.getSelection(document);
    }

    // --- ICompletionProposalExtension delegation ---

    @Override
    public void apply(IDocument document, char trigger, int offset) {
        if (delegate instanceof ICompletionProposalExtension ext) {
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
        if (delegate instanceof ICompletionProposalExtension2 ext2) {
            ext2.apply(viewer, trigger, stateMask, offset);
        } else if (delegate instanceof ICompletionProposalExtension ext) {
            ext.apply(viewer.getDocument(), trigger, offset);
        } else {
            delegate.apply(viewer.getDocument());
        }
        recordAcceptance();
    }

    @Override
    public void selected(ITextViewer viewer, boolean smartToggle) {
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

    @Override
    public boolean validate(IDocument document, int offset, DocumentEvent event) {
        if (matchingArea != null && !matchingArea.isEmpty()) {
            // Use subsequence matching instead of prefix matching
            String currentPrefix = extractPrefix(document, offset);
            if (currentPrefix.isEmpty()) {
                return true;
            }
            int[] newSequence = LCSS.bestSubsequence(matchingArea, currentPrefix);
            if (newSequence.length > 0) {
                matchedIndices = newSequence; // update highlighting for next render
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
            char[] nameChars = coreProposal.getName();
            if (typeName == null || nameChars == null || nameChars.length == 0) {
                return;
            }

            CompletionTracker.getInstance().recordAcceptance(typeName, new String(nameChars));
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
