/**
 * Copyright (c) 2014 Codetrails GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Marcel Bruch - initial API and implementation.
 *    Extracted and adapted for standalone use.
 */
package org.eclipse.subsequence.jdt.completion;

import static java.lang.Math.min;
import static org.eclipse.subsequence.jdt.core.LCSS.containsSubsequence;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.internal.codeassist.InternalCompletionContext;
import org.eclipse.jdt.internal.codeassist.RelevanceConstants;
import org.eclipse.jdt.internal.codeassist.complete.CompletionOnFieldType;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility;
import org.eclipse.jdt.internal.ui.text.java.JavaCompletionProposal;
import org.eclipse.jdt.internal.ui.text.java.LazyJavaCompletionProposal;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposalComputer;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.subsequence.jdt.callmodel.FrequencyBooster;
import org.eclipse.subsequence.jdt.core.LCSS;
import org.eclipse.subsequence.jdt.preferences.SubsequencePreferences;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.ui.IEditorPart;

/**
 * JDT Content Assist computer that provides subsequence matching for Java completions.
 * <p>
 * Typing "hm" will match "HashMap", "sbu" will match "StringBuilder", etc. The algorithm respects
 * camelCase word boundaries and highlights matched characters in bold.
 * <p>
 * This is a standalone reimplementation of the subsequence matching feature from Eclipse Recommenders,
 * using only standard JDT extension points ({@code IJavaCompletionProposalComputer}) and zero
 * Recommenders dependencies.
 */
@SuppressWarnings("restriction")
public class SubsequenceCompletionProposalComputer implements IJavaCompletionProposalComputer {

    private static final ILog LOG = Platform.getLog(SubsequenceCompletionProposalComputer.class);

    /**
     * Use the timeout set by {@code JavaCompletionProposalComputer.JAVA_CODE_ASSIST_TIMEOUT}.
     */
    private static final long COMPLETION_TIME_OUT = Long.getLong("org.eclipse.jdt.ui.codeAssistTimeout", 5000); //$NON-NLS-1$

    private static final int JAVADOC_TYPE_REF_HIGHLIGHT_ADJUSTMENT = "{@link ".length(); //$NON-NLS-1$

    // Negative value ensures subsequence matches have a lower relevance than standard JDT or template proposals
    public static final int SUBWORDS_RANGE_START = -9000;
    public static final int CASE_SENSITIVE_EXACT_MATCH_START = 16
            * (RelevanceConstants.R_EXACT_NAME + RelevanceConstants.R_CASE);
    public static final int CASE_INSENSITIVE_EXACT_MATCH_START = 16 * RelevanceConstants.R_EXACT_NAME;

    private static final int[] EMPTY_SEQUENCE = new int[0];

    // MODULE_DECLARATION and MODULE_REF constants (may not exist in older JDT)
    private static final int MODULE_DECLARATION = 28;
    private static final int MODULE_REF = 29;

    // --- Reflection for JavaContentAssistInvocationContext internals ---
    private static final Field CORE_CONTEXT;
    private static final Field CU_FIELD;
    private static final Field CU_COMPUTED;

    static {
        Field coreCtx = null;
        Field cuField = null;
        Field cuComputed = null;
        try {
            coreCtx = JavaContentAssistInvocationContext.class.getDeclaredField("fCoreContext"); //$NON-NLS-1$
            coreCtx.setAccessible(true);
            cuField = JavaContentAssistInvocationContext.class.getDeclaredField("fCU"); //$NON-NLS-1$
            cuField.setAccessible(true);
            cuComputed = JavaContentAssistInvocationContext.class.getDeclaredField("fCUComputed"); //$NON-NLS-1$
            cuComputed.setAccessible(true);
        } catch (Exception e) {
            LOG.warn("Could not access JavaContentAssistInvocationContext internals via reflection", e); //$NON-NLS-1$
        }
        CORE_CONTEXT = coreCtx;
        CU_FIELD = cuField;
        CU_COMPUTED = cuComputed;
    }

    private Styler styler;

    @Override
    public void sessionStarted() {
        // Nothing to initialize
    }

    @Override
    public List<ICompletionProposal> computeCompletionProposals(ContentAssistInvocationContext invocationContext,
            IProgressMonitor monitor) {
        if (!(invocationContext instanceof JavaContentAssistInvocationContext jdtContext)) {
            return Collections.emptyList();
        }

        try {
            return doComputeProposals(jdtContext);
        } catch (Exception e) {
            LOG.error("Exception during subsequence code completion", e); //$NON-NLS-1$
            return Collections.emptyList();
        }
    }

    private List<ICompletionProposal> doComputeProposals(JavaContentAssistInvocationContext jdtContext) throws Exception {
        ICompilationUnit cu = jdtContext.getCompilationUnit();
        if (cu == null) {
            return Collections.emptyList();
        }

        int offset = jdtContext.getInvocationOffset();
        int minPrefixLengthForTypes = SubsequencePreferences.getMinPrefixLengthForTypes();

        // Step 1: Get the InternalCompletionContext by doing a lightweight code completion
        ContextCollector contextCollector = new ContextCollector();
        cu.codeComplete(offset, contextCollector, new NullProgressMonitor());
        InternalCompletionContext compContext = contextCollector.getCoreContext();
        if (compContext == null) {
            return Collections.emptyList();
        }

        // Step 2: Set the core context on the JDT context via reflection
        if (CORE_CONTEXT != null) {
            CORE_CONTEXT.set(jdtContext, compContext);
        }

        // Step 3: Compute the prefix
        String prefix = getPrefix(jdtContext);
        int length = prefix.length();

        // Step 4: Compute trigger locations
        ASTNode completionNode = compContext.getCompletionNode();
        ASTNode completionNodeParent = compContext.getCompletionNodeParent();
        SortedSet<Integer> triggerLocations = computeTriggerLocations(offset, completionNode, completionNodeParent,
                length, minPrefixLengthForTypes);

        // Step 5: Collect proposals from all trigger locations
        Map<IJavaCompletionProposal, CompletionProposal> allProposals = new HashMap<>();
        Set<String> sortKeys = new HashSet<>();

        for (int trigger : triggerLocations) {
            Map<IJavaCompletionProposal, CompletionProposal> newProposals = getNewProposals(jdtContext, cu, trigger);
            filterAndInsert(allProposals, sortKeys, newProposals, prefix);
        }

        // Step 6: Wrap proposals with adjusted relevance and highlighting
        List<ICompletionProposal> result = new ArrayList<>();
        Styler boldStyler = getStyler();

        for (Entry<IJavaCompletionProposal, CompletionProposal> entry : allProposals.entrySet()) {
            IJavaCompletionProposal javaProposal = entry.getKey();
            CompletionProposal coreProposal = entry.getValue();

            String completionIdentifier = computeCompletionIdentifier(javaProposal, coreProposal);
            String matchingArea = CompletionUtils.getPrefixMatchingArea(completionIdentifier);

            int[] bestSequence = LCSS.bestSubsequence(matchingArea, prefix);
            int adjustedRelevance = computeRelevance(javaProposal, bestSequence, prefix, matchingArea,
                    minPrefixLengthForTypes)
                    + FrequencyBooster.computeFrequencyBoost(coreProposal);
            int highlightAdjustment = computeHighlightAdjustment(javaProposal, coreProposal);

            result.add(new SubsequenceProposal(javaProposal, adjustedRelevance, bestSequence, boldStyler,
                    highlightAdjustment, coreProposal, matchingArea));
        }

        return result;
    }

    private SortedSet<Integer> computeTriggerLocations(int offset, ASTNode completionNode,
            ASTNode completionNodeParent, int length, int minPrefixLengthForTypes) {
        // Trigger at higher locations first, as the base relevance assigned by JDT may depend on the prefix.
        SortedSet<Integer> triggerLocations = new TreeSet<>(java.util.Comparator.reverseOrder());
        int emptyPrefix = offset - length;

        // Method stub creation proposals like exe --> private void exe()
        if (completionNode instanceof CompletionOnFieldType) {
            triggerLocations.add(offset);
        }

        // Trigger with either the specified prefix or the minimum prefix length
        int triggerOffset = min(minPrefixLengthForTypes, length);
        triggerLocations.add(emptyPrefix + triggerOffset);

        // Always trigger with empty prefix to get all members at the current location
        triggerLocations.add(emptyPrefix);

        return triggerLocations;
    }

    private String getPrefix(JavaContentAssistInvocationContext jdtContext) throws BadLocationException {
        CharSequence prefix = jdtContext.computeIdentifierPrefix();
        return prefix == null ? "" : prefix.toString(); //$NON-NLS-1$
    }

    private Map<IJavaCompletionProposal, CompletionProposal> getNewProposals(
            JavaContentAssistInvocationContext originalContext, ICompilationUnit cu, int triggerOffset) {
        if (triggerOffset < 0) {
            return new HashMap<>();
        }
        ITextViewer viewer = originalContext.getViewer();
        IEditorPart editor = EditorUtility.isOpenInEditor(cu);
        JavaContentAssistInvocationContext newJdtContext = new JavaContentAssistInvocationContext(viewer, triggerOffset,
                editor);
        setCompilationUnit(newJdtContext, cu);
        ProposalCollector collector = computeProposals(cu, newJdtContext, triggerOffset);
        Map<IJavaCompletionProposal, CompletionProposal> proposals = collector.getProposals();
        return proposals != null ? proposals : new HashMap<>();
    }

    private void setCompilationUnit(JavaContentAssistInvocationContext newJdtContext, ICompilationUnit cu) {
        if (CU_FIELD == null || CU_COMPUTED == null) {
            return;
        }
        try {
            CU_FIELD.set(newJdtContext, cu);
            CU_COMPUTED.set(newJdtContext, true);
        } catch (Exception e) {
            LOG.warn("Failed to set compilation unit on context", e); //$NON-NLS-1$
        }
    }

    private void filterAndInsert(Map<IJavaCompletionProposal, CompletionProposal> baseProposals,
            Set<String> sortKeys, Map<IJavaCompletionProposal, CompletionProposal> newProposals, String prefix) {
        for (Entry<IJavaCompletionProposal, CompletionProposal> entry : newProposals.entrySet()) {
            IJavaCompletionProposal javaProposal = entry.getKey();
            CompletionProposal coreProposal = entry.getValue();

            String completionIdentifier = computeCompletionIdentifier(javaProposal, coreProposal);
            String matchingArea = CompletionUtils.getPrefixMatchingArea(completionIdentifier);

            if (!sortKeys.contains(completionIdentifier) && containsSubsequence(matchingArea, prefix)) {
                baseProposals.put(javaProposal, coreProposal);
                sortKeys.add(completionIdentifier);
            }
        }
    }

    private String computeCompletionIdentifier(IJavaCompletionProposal javaProposal, CompletionProposal coreProposal) {
        if (javaProposal instanceof LazyJavaCompletionProposal && coreProposal != null) {
            return switch (coreProposal.getKind()) {
                case CompletionProposal.CONSTRUCTOR_INVOCATION -> {
                    yield new StringBuilder().append(coreProposal.getName()).append(' ')
                            .append(coreProposal.getSignature()).append(coreProposal.getDeclarationSignature())
                            .toString();
                }
                case CompletionProposal.JAVADOC_TYPE_REF -> {
                    char[] signature = coreProposal.getSignature();
                    char[] simpleName = Signature.getSignatureSimpleName(signature);
                    int indexOf = CharOperation.lastIndexOf('.', simpleName);
                    simpleName = CharOperation.subarray(simpleName, indexOf + 1, simpleName.length);
                    yield new StringBuilder().append(simpleName).append(' ').append(signature)
                            .append(" javadoc").toString(); //$NON-NLS-1$
                }
                case CompletionProposal.TYPE_REF -> {
                    char[] signature = coreProposal.getSignature();
                    char[] simpleName = Signature.getSignatureSimpleName(signature);
                    int indexOf = CharOperation.lastIndexOf('.', simpleName);
                    simpleName = CharOperation.subarray(simpleName, indexOf + 1, simpleName.length);
                    yield new StringBuilder().append(simpleName).append(' ').append(signature).toString();
                }
                case CompletionProposal.PACKAGE_REF -> new String(coreProposal.getDeclarationSignature());
                case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_REF_WITH_CASTED_RECEIVER,
                        CompletionProposal.METHOD_NAME_REFERENCE -> {
                    yield new StringBuilder().append(coreProposal.getName()).append(' ')
                            .append(coreProposal.getSignature()).append(coreProposal.getDeclarationSignature())
                            .toString();
                }
                case CompletionProposal.JAVADOC_METHOD_REF -> {
                    yield new StringBuilder().append(coreProposal.getName()).append(' ')
                            .append(coreProposal.getSignature()).append(coreProposal.getDeclarationSignature())
                            .append(" javadoc").toString(); //$NON-NLS-1$
                }
                case CompletionProposal.JAVADOC_PARAM_REF, CompletionProposal.JAVADOC_BLOCK_TAG,
                        CompletionProposal.JAVADOC_INLINE_TAG, MODULE_DECLARATION, MODULE_REF ->
                    javaProposal.getDisplayString();
                default -> javaProposal.getDisplayString();
            };
        }
        return javaProposal.getDisplayString();
    }

    private int computeRelevance(IJavaCompletionProposal javaProposal, int[] bestSequence, String prefix,
            String matchingArea, int minPrefixLengthForTypes) {
        int baseRelevance = javaProposal.getRelevance();

        if (bestSequence.length == 0) {
            // Prefix match (no subsequence needed)
            return baseRelevance;
        }

        int relevanceBoost;

        if (prefix.equals(matchingArea)) {
            // Case-sensitive exact match
            relevanceBoost = minPrefixLengthForTypes < prefix.length() ? CASE_SENSITIVE_EXACT_MATCH_START : 0;
        } else if (prefix.equalsIgnoreCase(matchingArea)) {
            // Case-insensitive exact match
            relevanceBoost = minPrefixLengthForTypes < prefix.length() ? CASE_INSENSITIVE_EXACT_MATCH_START : 0;
        } else if (matchingArea.toLowerCase().startsWith(prefix.toLowerCase())) {
            // Prefix match (case insensitive)
            relevanceBoost = 0;
        } else if (CharOperation.camelCaseMatch(prefix.toCharArray(), matchingArea.toCharArray())) {
            // CamelCase match
            relevanceBoost = 0;
        } else {
            // Pure subsequence match - score below normal proposals
            int score = LCSS.scoreSubsequence(bestSequence);
            relevanceBoost = SUBWORDS_RANGE_START + score;
        }

        return baseRelevance + relevanceBoost;
    }

    private int computeHighlightAdjustment(IJavaCompletionProposal javaProposal, CompletionProposal coreProposal) {
        if (coreProposal == null) {
            // HTML tag proposals
            if (javaProposal instanceof JavaCompletionProposal) {
                String display = javaProposal.getDisplayString();
                if (display.startsWith("</")) { //$NON-NLS-1$
                    return 2;
                } else if (display.startsWith("<")) { //$NON-NLS-1$
                    return 1;
                }
            }
            return 0;
        }
        return switch (coreProposal.getKind()) {
            case CompletionProposal.JAVADOC_FIELD_REF, CompletionProposal.JAVADOC_METHOD_REF,
                    CompletionProposal.JAVADOC_VALUE_REF ->
                javaProposal.getDisplayString().lastIndexOf('#') + 1;
            case CompletionProposal.JAVADOC_TYPE_REF -> JAVADOC_TYPE_REF_HIGHLIGHT_ADJUSTMENT;
            default -> 0;
        };
    }

    private ProposalCollector computeProposals(ICompilationUnit cu,
            JavaContentAssistInvocationContext coreContext, int offset) {
        ProposalCollector collector = new ProposalCollector(coreContext, cu);
        try {
            cu.codeComplete(offset, collector, new NullProgressMonitor());
        } catch (Exception e) {
            LOG.error("Exception during code completion at offset " + offset, e); //$NON-NLS-1$
        }
        return collector;
    }

    /**
     * Returns a {@link Styler} that renders matched characters in bold.
     * Uses JFace's managed font registry — no disposal needed.
     */
    private Styler getStyler() {
        if (styler != null) {
            return styler;
        }
        styler = new Styler() {
            @Override
            public void applyStyles(TextStyle textStyle) {
                textStyle.font = JFaceResources.getFontRegistry()
                        .getBold(JFaceResources.DEFAULT_FONT);
            }
        };
        return styler;
    }

    @Override
    public List<IContextInformation> computeContextInformation(ContentAssistInvocationContext context,
            IProgressMonitor monitor) {
        return Collections.emptyList();
    }

    @Override
    public String getErrorMessage() {
        return null;
    }

    @Override
    public void sessionEnded() {
        styler = null;
    }

    /**
     * A minimal {@link org.eclipse.jdt.core.CompletionRequestor} that only collects the
     * {@link InternalCompletionContext}, ignoring all proposals.
     */
    private static class ContextCollector extends org.eclipse.jdt.core.CompletionRequestor {

        private InternalCompletionContext coreContext;

        ContextCollector() {
            super(false);
        }

        @Override
        public boolean isIgnored(int completionProposalKind) {
            return true;
        }

        @Override
        public boolean isExtendedContextRequired() {
            return true;
        }

        @Override
        public void acceptContext(org.eclipse.jdt.core.CompletionContext context) {
            this.coreContext = (InternalCompletionContext) context;
        }

        @Override
        public void accept(CompletionProposal proposal) {
            // Ignored - we only want the context
        }

        /**
         * Returns the collected internal completion context, or {@code null} if none was provided.
         */
        InternalCompletionContext getCoreContext() {
            return coreContext;
        }
    }
}
