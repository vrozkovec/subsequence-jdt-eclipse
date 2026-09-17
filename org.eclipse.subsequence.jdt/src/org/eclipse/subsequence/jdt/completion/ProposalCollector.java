/**
 * Copyright (c) 2010 Darmstadt University of Technology.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marcel Bruch - initial API and implementation.
 */
package org.eclipse.subsequence.jdt.completion;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.ui.text.java.FillArgumentNamesCompletionProposalCollector;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.java.CompletionProposalCollector;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.graphics.Point;

/**
 * Collects JDT completion proposals by delegating to a {@link CompletionProposalCollector} and
 * extracting proposals via reflection on the {@code fJavaProposals} field.
 * <p>
 * Adapted from the original {@code ProposalCollectingCompletionRequestor} in Eclipse Recommenders,
 * with all Recommenders dependencies removed.
 */
@SuppressWarnings("restriction")
public class ProposalCollector extends CompletionRequestor {

    private static final ILog LOG = Platform.getLog(ProposalCollector.class);

    /** Proposal kinds the delegate collector accepts; it starts out ignoring every kind. */
    private static final int[] ACCEPTED_KINDS = {
            CompletionProposal.ANNOTATION_ATTRIBUTE_REF,
            CompletionProposal.ANONYMOUS_CLASS_DECLARATION,
            CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION,
            CompletionProposal.FIELD_REF,
            CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER,
            CompletionProposal.KEYWORD,
            CompletionProposal.LABEL_REF,
            CompletionProposal.LOCAL_VARIABLE_REF,
            CompletionProposal.METHOD_DECLARATION,
            CompletionProposal.METHOD_NAME_REFERENCE,
            CompletionProposal.METHOD_REF,
            CompletionProposal.CONSTRUCTOR_INVOCATION,
            CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER,
            CompletionProposal.PACKAGE_REF,
            CompletionProposal.POTENTIAL_METHOD_DECLARATION,
            CompletionProposal.VARIABLE_DECLARATION,
            CompletionProposal.MODULE_DECLARATION,
            CompletionProposal.MODULE_REF,
            CompletionProposal.TYPE_REF,
            CompletionProposal.JAVADOC_BLOCK_TAG,
            CompletionProposal.JAVADOC_FIELD_REF,
            CompletionProposal.JAVADOC_INLINE_TAG,
            CompletionProposal.JAVADOC_METHOD_REF,
            CompletionProposal.JAVADOC_PARAM_REF,
            CompletionProposal.JAVADOC_TYPE_REF,
            CompletionProposal.JAVADOC_VALUE_REF,
    };

    /** {@code {proposal kind, required proposal kind}} pairs the delegate collector allows. */
    private static final int[][] ALLOWED_REQUIRED_PROPOSALS = {
            { CompletionProposal.FIELD_REF, CompletionProposal.TYPE_REF },
            { CompletionProposal.FIELD_REF, CompletionProposal.TYPE_IMPORT },
            { CompletionProposal.FIELD_REF, CompletionProposal.FIELD_IMPORT },
            { CompletionProposal.METHOD_REF, CompletionProposal.TYPE_REF },
            { CompletionProposal.METHOD_REF, CompletionProposal.TYPE_IMPORT },
            { CompletionProposal.METHOD_REF, CompletionProposal.METHOD_IMPORT },
            { CompletionProposal.CONSTRUCTOR_INVOCATION, CompletionProposal.TYPE_REF },
            { CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION, CompletionProposal.TYPE_REF },
            { CompletionProposal.ANONYMOUS_CLASS_DECLARATION, CompletionProposal.TYPE_REF },
            { CompletionProposal.TYPE_REF, CompletionProposal.TYPE_REF },
    };

    private static final Field F_PROPOSALS;

    static {
        Field field = null;
        try {
            field = CompletionProposalCollector.class.getDeclaredField("fJavaProposals"); //$NON-NLS-1$
            field.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException e) {
            LOG.warn("Could not access CompletionProposalCollector.fJavaProposals", e); //$NON-NLS-1$
        }
        F_PROPOSALS = field;
    }

    private final Map<IJavaCompletionProposal, CompletionProposal> proposals = new IdentityHashMap<>();

    private final JavaContentAssistInvocationContext jdtuiContext;

    private CompletionProposalCollector collector;
    private InternalCompletionContext compilerContext;

    /**
     * Creates a new proposal collector for the given invocation context.
     */
    public ProposalCollector(JavaContentAssistInvocationContext ctx) {
        super(false);
        this.jdtuiContext = Objects.requireNonNull(ctx);
        initializeCollector();
    }

    private void initializeCollector() {
        if (shouldFillArgumentNames()) {
            collector = new FillArgumentNamesCompletionProposalCollector(jdtuiContext);
        } else {
            collector = new CompletionProposalCollector(jdtuiContext.getCompilationUnit(), true);
        }
        configureInterestedProposalTypes();
        adjustProposalReplacementLength();
    }

    private void configureInterestedProposalTypes() {
        for (int kind : ACCEPTED_KINDS) {
            collector.setIgnored(kind, false);
        }
        for (int[] pair : ALLOWED_REQUIRED_PROPOSALS) {
            collector.setAllowsRequiredProposals(pair[0], pair[1], true);
        }
        collector.setFavoriteReferences(getFavoriteStaticMembers());
        collector.setRequireExtendedContext(true);
    }

    @Override
    public boolean isIgnored(int completionProposalKind) {
        return collector.isIgnored(completionProposalKind);
    }

    @Override
    public void setIgnored(int completionProposalKind, boolean ignore) {
        collector.setIgnored(completionProposalKind, ignore);
    }

    @Override
    public boolean isIgnored(char[] fullTypeName) {
        return collector.isIgnored(fullTypeName);
    }

    @Override
    public boolean isAllowingRequiredProposals(int proposalKind, int requiredProposalKind) {
        return collector.isAllowingRequiredProposals(proposalKind, requiredProposalKind);
    }

    @Override
    public void setAllowsRequiredProposals(int proposalKind, int requiredProposalKind, boolean allow) {
        collector.setAllowsRequiredProposals(proposalKind, requiredProposalKind, allow);
    }

    @Override
    public boolean isExtendedContextRequired() {
        return collector.isExtendedContextRequired();
    }

    @Override
    public String[] getFavoriteReferences() {
        return collector.getFavoriteReferences();
    }

    private void adjustProposalReplacementLength() {
        ITextViewer viewer = jdtuiContext.getViewer();
        Point selection = viewer.getSelectedRange();
        if (selection.y > 0) {
            collector.setReplacementLength(selection.y);
        }
    }

    private boolean shouldFillArgumentNames() {
        try {
            return PreferenceConstants.getPreferenceStore()
                    .getBoolean(PreferenceConstants.CODEASSIST_FILL_ARGUMENT_NAMES);
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void acceptContext(CompletionContext context) {
        compilerContext = (InternalCompletionContext) context;
        collector.acceptContext(context);
    }

    private String[] getFavoriteStaticMembers() {
        String serializedFavorites = PreferenceConstants.getPreferenceStore()
                .getString(PreferenceConstants.CODEASSIST_FAVORITE_STATIC_MEMBERS);
        if (serializedFavorites != null && !serializedFavorites.isEmpty()) {
            return serializedFavorites.split(";"); //$NON-NLS-1$
        }
        return CharOperation.NO_STRINGS;
    }

    @Override
    public void accept(CompletionProposal compilerProposal) {
        for (IJavaCompletionProposal uiProposal : createJdtProposals(compilerProposal)) {
            proposals.put(uiProposal, compilerProposal);
        }
    }

    @SuppressWarnings("unchecked")
    private IJavaCompletionProposal[] createJdtProposals(CompletionProposal proposal) {
        if (F_PROPOSALS != null) {
            try {
                List<IJavaCompletionProposal> list = (List<IJavaCompletionProposal>) F_PROPOSALS.get(collector);
                int oldSize = list.size();
                collector.accept(proposal);
                int newSize = list.size();
                List<IJavaCompletionProposal> res = list.subList(oldSize, newSize);
                return res.toArray(new IJavaCompletionProposal[0]);
            } catch (Exception e) {
                LOG.warn("Failed to access fJavaProposals via reflection", e); //$NON-NLS-1$
            }
        }
        // Fallback
        int oldSize = collector.getJavaCompletionProposals().length;
        collector.accept(proposal);
        IJavaCompletionProposal[] jdtProposals = collector.getJavaCompletionProposals();
        return Arrays.copyOfRange(jdtProposals, oldSize, jdtProposals.length);
    }

    /**
     * Returns the internal compiler context collected during code completion.
     */
    public InternalCompletionContext getCoreContext() {
        return compilerContext;
    }

    /**
     * Returns the collected proposals as a map from UI proposals to their core compiler proposals.
     */
    public Map<IJavaCompletionProposal, CompletionProposal> getProposals() {
        return proposals;
    }

    @Override
    public void completionFailure(IProblem problem) {
        // Silently ignore completion failures
    }
}
