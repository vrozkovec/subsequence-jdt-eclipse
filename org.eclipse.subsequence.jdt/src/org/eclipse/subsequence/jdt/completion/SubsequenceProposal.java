/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

/**
 * A wrapper around a standard {@link IJavaCompletionProposal} that overrides relevance and
 * display string to add subsequence match highlighting.
 */
public class SubsequenceProposal implements IJavaCompletionProposal, ICompletionProposalExtension,
        ICompletionProposalExtension2, ICompletionProposalExtension3, ICompletionProposalExtension5,
        ICompletionProposalExtension6 {

    private final IJavaCompletionProposal delegate;
    private final int adjustedRelevance;
    private final int[] matchedIndices;
    private final Styler highlightStyler;
    private final int highlightAdjustment;

    /**
     * Creates a new subsequence proposal wrapping the given delegate.
     *
     * @param delegate            the original JDT proposal
     * @param adjustedRelevance   the adjusted relevance score
     * @param matchedIndices      indices of matched characters in the display string
     * @param highlightStyler     styler for highlighting matched characters
     * @param highlightAdjustment offset adjustment for highlighting (e.g. for javadoc type refs)
     */
    public SubsequenceProposal(IJavaCompletionProposal delegate, int adjustedRelevance, int[] matchedIndices,
            Styler highlightStyler, int highlightAdjustment) {
        this.delegate = delegate;
        this.adjustedRelevance = adjustedRelevance;
        this.matchedIndices = matchedIndices;
        this.highlightStyler = highlightStyler;
        this.highlightAdjustment = highlightAdjustment;
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

        // Apply bold highlighting to matched character positions
        if (matchedIndices != null && highlightStyler != null) {
            for (int index : matchedIndices) {
                int adjusted = index + highlightAdjustment;
                if (adjusted >= 0 && adjusted < original.length()) {
                    original.setStyle(adjusted, 1, highlightStyler);
                }
            }
        }

        return original;
    }

    // --- Delegation methods ---

    @Override
    public void apply(IDocument document) {
        delegate.apply(document);
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
        if (delegate instanceof ICompletionProposalExtension2 ext2) {
            return ext2.validate(document, offset, event);
        }
        if (delegate instanceof ICompletionProposalExtension ext) {
            return ext.isValidFor(document, offset);
        }
        return false;
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
     * Returns the wrapped delegate proposal.
     */
    public IJavaCompletionProposal getDelegate() {
        return delegate;
    }
}
