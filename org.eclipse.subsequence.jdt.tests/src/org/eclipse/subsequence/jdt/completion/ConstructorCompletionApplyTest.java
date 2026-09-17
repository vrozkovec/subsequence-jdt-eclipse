/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.text.java.AbstractJavaCompletionProposal;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.java.CompletionProposalCollector;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.junit.jupiter.api.Test;

/**
 * Builds the <em>UI</em> proposal JDT would build for {@code new ListProjektBudget|} inside a nested
 * anonymous class and applies it to a document, in both "Completion inserts" and
 * "Completion overwrites" mode.
 * <p>
 * The core engine already reports the right ranges and a {@code "()"} completion
 * ({@link CompletionEngineProbeTest}), so if the argument list goes missing it goes missing here.
 */
@SuppressWarnings("restriction")
class ConstructorCompletionApplyTest {

    private static final String TARGET_TYPE = "ListProjektBudgetRealizatorPanel";

    private static final String SOURCE = """
            package probe;
            public class Outer {
                Object m() {
                    return new java.lang.Runnable() {
                        @Override
                        public void run() {
                            Object o = new ListProjektBudget
                        }
                    };
                }
            }
            """;

    private static final String TARGET = """
            package probe;
            public class ListProjektBudgetRealizatorPanel {
                public ListProjektBudgetRealizatorPanel(String id) {
                }
            }
            """;

    @Test
    void applyConstructorProposalInBothInsertModes() throws Exception {
        IJavaProject javaProject = createProject("apply" + System.nanoTime());
        IPackageFragment pkg = createPackage(javaProject);
        pkg.createCompilationUnit(TARGET_TYPE + ".java", TARGET, true, new NullProgressMonitor());
        ICompilationUnit cu = pkg.createCompilationUnit("Outer.java", SOURCE, true, new NullProgressMonitor());

        int caret = SOURCE.indexOf("new ListProjektBudget") + "new ListProjektBudget".length();
        int trigger = caret - "ListProjektBudget".length() + 2;

        for (boolean insertCompletion : new boolean[] { true, false }) {
            JavaPlugin.getDefault().getPreferenceStore()
                    .setValue(PreferenceConstants.CODEASSIST_INSERT_COMPLETION, insertCompletion);

            System.out.println("\n=== CODEASSIST_INSERT_COMPLETION=" + insertCompletion
                    + (insertCompletion ? " (inserts)" : " (OVERWRITES - the reported setting)") + " ===");
            System.out.println("    fill argument names = " + PreferenceConstants.getPreferenceStore()
                    .getBoolean(PreferenceConstants.CODEASSIST_FILL_ARGUMENT_NAMES)
                    + ", guess arguments = " + PreferenceConstants.getPreferenceStore()
                            .getBoolean(PreferenceConstants.CODEASSIST_GUESS_METHOD_ARGUMENTS));

            for (IJavaCompletionProposal proposal : collect(cu, trigger)) {
                if (!(proposal instanceof AbstractJavaCompletionProposal ajcp)) {
                    continue;
                }
                System.out.println("  " + proposal.getClass().getSimpleName()
                        + " display='" + proposal.getDisplayString() + "'");
                System.out.println("      replacement='" + ajcp.getReplacementString() + "'"
                        + " offset=" + ajcp.getReplacementOffset()
                        + " length=" + ajcp.getReplacementLength());

                if (proposal instanceof ICompletionProposalExtension ext) {
                    IDocument document = new Document(SOURCE);
                    ext.apply(document, (char) 0, caret);
                    String line = lineAround(document, "new " + TARGET_TYPE);
                    System.out.println("      APPLIED -> " + line);
                }
            }
        }
    }

    private static String lineAround(IDocument document, String needle) {
        String text = document.get();
        int at = text.indexOf(needle);
        if (at < 0) {
            return "<'" + needle + "' not found>";
        }
        int end = text.indexOf('\n', at);
        return "'" + text.substring(at, end < 0 ? text.length() : end).strip() + "'";
    }

    private static List<IJavaCompletionProposal> collect(ICompilationUnit cu, int offset) throws Exception {
        List<IJavaCompletionProposal> result = new ArrayList<>();
        CompletionProposalCollector collector = new CompletionProposalCollector(cu, true) {

            @Override
            public void accept(CompletionProposal proposal) {
                if (proposal.getKind() != CompletionProposal.CONSTRUCTOR_INVOCATION
                        && proposal.getKind() != CompletionProposal.TYPE_REF) {
                    return;
                }
                String name = proposal.getName() == null ? "" : new String(proposal.getName());
                String completion = proposal.getCompletion() == null ? "" : new String(proposal.getCompletion());
                if (!name.startsWith(TARGET_TYPE) && !completion.endsWith(TARGET_TYPE)) {
                    return;
                }
                super.accept(proposal);
            }
        };
        collector.setIgnored(CompletionProposal.CONSTRUCTOR_INVOCATION, false);
        collector.setIgnored(CompletionProposal.TYPE_REF, false);
        collector.setAllowsRequiredProposals(CompletionProposal.CONSTRUCTOR_INVOCATION,
                CompletionProposal.TYPE_REF, true);
        cu.codeComplete(offset, collector, new NullProgressMonitor());
        for (IJavaCompletionProposal p : collector.getJavaCompletionProposals()) {
            result.add(p);
        }
        return result;
    }

    private static IJavaProject createProject(String name) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.setDescription(description, new NullProgressMonitor());

        IJavaProject javaProject = JavaCore.create(project);
        IFolder src = project.getFolder("src");
        src.create(false, true, new NullProgressMonitor());
        javaProject.setRawClasspath(new IClasspathEntry[] {
                JavaCore.newSourceEntry(src.getFullPath()),
                JavaRuntime.getDefaultJREContainerEntry(),
        }, project.getFolder("bin").getFullPath(), new NullProgressMonitor());
        return javaProject;
    }

    private static IPackageFragment createPackage(IJavaProject javaProject) throws CoreException {
        IPackageFragmentRoot root = javaProject.findPackageFragmentRoot(
                javaProject.getProject().getFullPath().append(new Path("src")));
        return root.createPackageFragment("probe", true, new NullProgressMonitor());
    }
}
