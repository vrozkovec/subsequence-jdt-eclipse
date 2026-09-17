/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: runs {@link SubsequenceCompletionProposalComputer} over a real viewer and document,
 * applies the constructor proposal the way the content assist popup does, and checks what lands in
 * the document.
 * <p>
 * {@link ConstructorCompletionApplyTest} shows plain JDT produces
 * {@code new ListProjektBudgetRealizatorPanel()} here, so anything missing is ours.
 */
@SuppressWarnings("restriction")
class SubsequenceApplyEndToEndTest {

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

    /**
     * The reported shape: the anonymous class is an argument to a static call, which is itself an
     * argument to an instance call, and the completion sits in an overridden method inside it. The
     * completion parser recovers an incomplete argument list quite differently from a plain
     * {@code return new Runnable() {...};}.
     */
    private static final String SOURCE_NESTED_ARGS = """
            package probe;
            import java.util.List;
            public class OuterNested {
                void m(List<Object> tabs) {
                    tabs.add(Holder.of("icon", new Base("title") {
                        @Override
                        public Object getPanel(String panelId) {
                            return new ListProjektBudget
                        }
                    }));
                }
            }
            """;

    /**
     * The reported case in full: an anonymous class declared inside a method of <em>another</em>
     * anonymous class, the inner one nested in two argument lists. Two levels of anonymous-class
     * recovery on top of an incomplete argument list.
     */
    private static final String SOURCE_DEEP = """
            package probe;
            import java.util.List;
            public class OuterDeep {
                protected Object newContentPanel(String id) {
                    return new Base(id) {
                        @Override
                        public Object getPanel(String panelId) {
                            return null;
                        }

                        protected void populateTabs(List<Object> tabs) {
                            tabs.add(Holder.of("icon", new Base("title") {
                                @Override
                                public Object getPanel(String panelId) {
                                    return new ListProjektBudget
                                }
                            }));
                        }
                    };
                }
            }
            """;

    private static final String BASE = """
            package probe;
            public abstract class Base {
                public Base(String title) {
                }
                public abstract Object getPanel(String panelId);
            }
            """;

    private static final String HOLDER = """
            package probe;
            public class Holder {
                public static Object of(String icon, Base tab) {
                    return null;
                }
            }
            """;

    private static final String TARGET = """
            package probe.other;
            public class ListProjektBudgetRealizatorPanel {
                public ListProjektBudgetRealizatorPanel(String id) {
                }
            }
            """;

    @Test
    void constructorProposalKeepsItsArgumentList() throws Exception {
        assertKeepsArgumentList("Outer.java", SOURCE, "plain anonymous class");
    }

    @Test
    void constructorProposalKeepsItsArgumentListInsideNestedArgumentLists() throws Exception {
        assertKeepsArgumentList("OuterNested.java", SOURCE_NESTED_ARGS, "anonymous class in nested argument lists");
    }

    @Test
    void constructorProposalKeepsItsArgumentListTwoAnonymousClassesDeep() throws Exception {
        assertKeepsArgumentList("OuterDeep.java", SOURCE_DEEP, "two anonymous classes deep");
    }

    private void assertKeepsArgumentList(String unitName, String source, String label) throws Exception {
        IJavaProject javaProject = createProject("e2e" + System.nanoTime());
        IPackageFragment pkg = createPackage(javaProject);
        IPackageFragment other = createPackage(javaProject, "probe.other");
        other.createCompilationUnit(TARGET_TYPE + ".java", TARGET, true, new NullProgressMonitor());
        pkg.createCompilationUnit("Base.java", BASE, true, new NullProgressMonitor());
        pkg.createCompilationUnit("Holder.java", HOLDER, true, new NullProgressMonitor());
        ICompilationUnit cu = pkg.createCompilationUnit(unitName, source, true, new NullProgressMonitor());

        int caret = source.indexOf("new ListProjektBudget") + "new ListProjektBudget".length();

        for (boolean fillArgumentNames : new boolean[] { true, false }) {
        for (boolean insertCompletion : new boolean[] { true, false }) {
            // fill-argument-names off makes ProposalCollector use the CU-only CompletionProposalCollector,
            // which builds its own context with no viewer - a different path entirely
            PreferenceConstants.getPreferenceStore()
                    .setValue(PreferenceConstants.CODEASSIST_FILL_ARGUMENT_NAMES, fillArgumentNames);
            JavaPlugin.getDefault().getPreferenceStore()
                    .setValue(PreferenceConstants.CODEASSIST_FILL_ARGUMENT_NAMES, fillArgumentNames);
            JavaPlugin.getDefault().getPreferenceStore()
                    .setValue(PreferenceConstants.CODEASSIST_INSERT_COMPLETION, insertCompletion);
            System.out.println("\n=== SUBSEQUENCE e2e [" + label + "] insert=" + insertCompletion
                    + " fillArgumentNames=" + fillArgumentNames + " ===");

            Shell shell = new Shell(Display.getDefault());
            try {
                SourceViewer viewer = new SourceViewer(shell, null, SWT.NONE);
                IDocument document = new Document(source);
                viewer.setDocument(document);
                viewer.setSelectedRange(caret, 0);

                // the context demands a real editor, exactly as in the IDE
                IEditorPart editor = JavaUI.openInEditor(cu);
                JavaContentAssistInvocationContext context =
                        new JavaContentAssistInvocationContext(viewer, caret, editor);
                injectCompilationUnit(context, cu);

                List<ICompletionProposal> proposals = new SubsequenceCompletionProposalComputer()
                        .computeCompletionProposals(context, new NullProgressMonitor());

                ICompletionProposal constructor = proposals.stream()
                        .filter(p -> p.getDisplayString().startsWith(TARGET_TYPE + "("))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no constructor proposal; got "
                                + proposals.stream().map(ICompletionProposal::getDisplayString).toList()));

                System.out.println("  proposal = " + constructor.getDisplayString());
                System.out.println("  delegate = "
                        + ((SubsequenceProposal) constructor).getDelegate().getClass().getSimpleName());

                ((ICompletionProposalExtension2) constructor).apply(viewer, (char) 0, 0, caret);

                String applied = lineAround(document);
                System.out.println("  APPLIED -> " + applied);
                System.out.println("  IMPORT  -> " + (document.get().contains("import probe.other." + TARGET_TYPE)
                        ? "added" : "NOT added"));
                assertTrue(applied.contains("new " + TARGET_TYPE + "("),
                        label + ": argument list lost with insert=" + insertCompletion
                                + " fillArgumentNames=" + fillArgumentNames + ": " + applied);
            } finally {
                shell.dispose();
            }
        }
        }
    }

    private static String lineAround(IDocument document) {
        String text = document.get();
        int at = text.indexOf("new " + TARGET_TYPE);
        if (at < 0) {
            at = text.indexOf("new ListProjektBudget");
        }
        if (at < 0) {
            return "<completion not found>";
        }
        int start = text.lastIndexOf('\n', at) + 1;
        int end = text.indexOf('\n', at);
        return text.substring(start, end < 0 ? text.length() : end).strip();
    }

    private static void injectCompilationUnit(JavaContentAssistInvocationContext context, ICompilationUnit cu)
            throws Exception {
        Field cuField = JavaContentAssistInvocationContext.class.getDeclaredField("fCU");
        cuField.setAccessible(true);
        cuField.set(context, cu);
        Field computed = JavaContentAssistInvocationContext.class.getDeclaredField("fCUComputed");
        computed.setAccessible(true);
        computed.set(context, true);
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
        return createPackage(javaProject, "probe");
    }

    private static IPackageFragment createPackage(IJavaProject javaProject, String name) throws CoreException {
        IPackageFragmentRoot root = javaProject.findPackageFragmentRoot(
                javaProject.getProject().getFullPath().append(new Path("src")));
        return root.createPackageFragment(name, true, new NullProgressMonitor());
    }
}
