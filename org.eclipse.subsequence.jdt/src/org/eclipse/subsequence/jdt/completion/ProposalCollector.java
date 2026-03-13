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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.ICompilationUnit;
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

    /** Module declaration completion proposal kind (value 28). */
    private static final int MODULE_DECLARATION = 28;
    /** Module reference completion proposal kind (value 29). */
    private static final int MODULE_REF = 29;

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
     * Creates a new proposal collector for the given context and compilation unit.
     */
    public ProposalCollector(JavaContentAssistInvocationContext ctx, ICompilationUnit cu) {
        super(false);
        this.jdtuiContext = java.util.Objects.requireNonNull(ctx);
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
        // Un-ignore all standard proposal types (collector starts with ignoreAll=true)
        setIgnoreNonTypes(false);
        setIgnoreTypes(false);

        // Accept javadoc proposal types
        setIgnoredSafely(CompletionProposal.JAVADOC_BLOCK_TAG, false);
        setIgnoredSafely(CompletionProposal.JAVADOC_FIELD_REF, false);
        setIgnoredSafely(CompletionProposal.JAVADOC_INLINE_TAG, false);
        setIgnoredSafely(CompletionProposal.JAVADOC_METHOD_REF, false);
        setIgnoredSafely(CompletionProposal.JAVADOC_PARAM_REF, false);
        setIgnoredSafely(CompletionProposal.JAVADOC_TYPE_REF, false);
        setIgnoredSafely(CompletionProposal.JAVADOC_VALUE_REF, false);

        // Allow required proposals
        setAllowsRequiredProposalsSafely(CompletionProposal.FIELD_REF, CompletionProposal.TYPE_REF, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.FIELD_REF, CompletionProposal.TYPE_IMPORT, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.FIELD_REF, CompletionProposal.FIELD_IMPORT, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.METHOD_REF, CompletionProposal.TYPE_REF, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.METHOD_REF, CompletionProposal.TYPE_IMPORT, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.METHOD_REF, CompletionProposal.METHOD_IMPORT, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.CONSTRUCTOR_INVOCATION, CompletionProposal.TYPE_REF, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION, CompletionProposal.TYPE_REF, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.ANONYMOUS_CLASS_DECLARATION, CompletionProposal.TYPE_REF, true);
        setAllowsRequiredProposalsSafely(CompletionProposal.TYPE_REF, CompletionProposal.TYPE_REF, true);

        collector.setFavoriteReferences(getFavoriteStaticMembers());
        collector.setRequireExtendedContext(true);
    }

    private void setIgnoredSafely(int completionProposalKind, boolean ignore) {
        try {
            collector.setIgnored(completionProposalKind, ignore);
        } catch (IllegalArgumentException e) {
            // Proposal kind not supported in this JDT version - ignore safely
        }
    }

    /**
     * Un-ignores (or ignores) all non-type proposal kinds on the delegate collector.
     */
    private void setIgnoreNonTypes(boolean ignored) {
        setIgnoredSafely(CompletionProposal.ANNOTATION_ATTRIBUTE_REF, ignored);
        setIgnoredSafely(CompletionProposal.ANONYMOUS_CLASS_DECLARATION, ignored);
        setIgnoredSafely(CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION, ignored);
        setIgnoredSafely(CompletionProposal.FIELD_REF, ignored);
        setIgnoredSafely(CompletionProposal.FIELD_REF_WITH_CASTED_RECEIVER, ignored);
        setIgnoredSafely(CompletionProposal.KEYWORD, ignored);
        setIgnoredSafely(CompletionProposal.LABEL_REF, ignored);
        setIgnoredSafely(CompletionProposal.LOCAL_VARIABLE_REF, ignored);
        setIgnoredSafely(CompletionProposal.METHOD_DECLARATION, ignored);
        setIgnoredSafely(CompletionProposal.METHOD_NAME_REFERENCE, ignored);
        setIgnoredSafely(CompletionProposal.METHOD_REF, ignored);
        setIgnoredSafely(CompletionProposal.CONSTRUCTOR_INVOCATION, ignored);
        setIgnoredSafely(CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER, ignored);
        setIgnoredSafely(CompletionProposal.PACKAGE_REF, ignored);
        setIgnoredSafely(CompletionProposal.POTENTIAL_METHOD_DECLARATION, ignored);
        setIgnoredSafely(CompletionProposal.VARIABLE_DECLARATION, ignored);
        setIgnoredSafely(MODULE_DECLARATION, ignored);
        setIgnoredSafely(MODULE_REF, ignored);
    }

    /**
     * Un-ignores (or ignores) type reference proposals on the delegate collector.
     */
    private void setIgnoreTypes(boolean ignored) {
        setIgnoredSafely(CompletionProposal.TYPE_REF, ignored);
    }

    private void setAllowsRequiredProposalsSafely(int proposalKind, int requiredProposalKind, boolean allow) {
        try {
            collector.setAllowsRequiredProposals(proposalKind, requiredProposalKind, allow);
        } catch (IllegalArgumentException e) {
            // Proposal kind not supported in this JDT version - ignore safely
        }
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
        IJavaCompletionProposal[] newProposals = new IJavaCompletionProposal[jdtProposals.length - oldSize];
        System.arraycopy(jdtProposals, oldSize, newProposals, 0, newProposals.length);
        return newProposals;
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
