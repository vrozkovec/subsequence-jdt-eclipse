/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.Test;

/**
 * Drives the real JDT completion engine over the reported source shape and prints exactly what it
 * proposes: kind, completion string and replace/token ranges, for the constructor proposal and the
 * {@code TYPE_REF} it requires — at the real caret and at the reduced offsets
 * {@code SubsequenceCompletionProposalComputer} actually completes at.
 * <p>
 * This is the ground truth {@link ProposalRanges} has to normalize.
 */
class CompletionEngineProbeTest {

    /** The reported shape: a constructor completion nested inside an anonymous class body. */
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

    private static final String TARGET_TYPE = "ListProjektBudgetRealizatorPanel";

    private static final String TARGET = """
            package probe;
            public class ListProjektBudgetRealizatorPanel {
                public ListProjektBudgetRealizatorPanel(String id) {
                }
            }
            """;

    private record Seen(int kind, String completion, int replaceStart, int replaceEnd, int tokenStart, int tokenEnd,
            String name, List<Seen> required) {

        @Override
        public String toString() {
            return "kind=" + kindName(kind) + " name=" + name + " completion='" + completion + "'"
                    + " replace=[" + replaceStart + ',' + replaceEnd + ")"
                    + " token=[" + tokenStart + ',' + tokenEnd + ")"
                    + (required.isEmpty() ? "" : "\n      required -> " + required);
        }
    }

    private static String kindName(int kind) {
        return switch (kind) {
            case CompletionProposal.TYPE_REF -> "TYPE_REF";
            case CompletionProposal.CONSTRUCTOR_INVOCATION -> "CONSTRUCTOR_INVOCATION";
            case CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION -> "ANON_CTOR_INVOCATION";
            case CompletionProposal.ANONYMOUS_CLASS_DECLARATION -> "ANON_CLASS_DECL";
            case CompletionProposal.METHOD_REF -> "METHOD_REF";
            case CompletionProposal.TYPE_IMPORT -> "TYPE_IMPORT";
            default -> "kind#" + kind;
        };
    }

    @Test
    void probe() throws Exception {
        IJavaProject javaProject = createProject("probe" + System.nanoTime());
        IPackageFragment pkg = createPackage(javaProject);
        pkg.createCompilationUnit("ListProjektBudgetRealizatorPanel.java", TARGET, true, new NullProgressMonitor());
        ICompilationUnit cu = pkg.createCompilationUnit("Outer.java", SOURCE, true, new NullProgressMonitor());

        int caret = SOURCE.indexOf("new ListProjektBudget") + "new ListProjektBudget".length();
        int tokenStart = caret - "ListProjektBudget".length();

        System.out.println("=== PROBE: caret=" + caret + " tokenStart=" + tokenStart
                + " charAfterToken='" + SOURCE.charAt(caret) + "' ===");

        for (int trigger : new int[] { caret, tokenStart + 2, tokenStart }) {
            System.out.println("\n--- codeComplete at offset " + trigger
                    + (trigger == caret ? " (REAL CARET)" : " (reduced trigger)") + " ---");
            List<Seen> seen = complete(cu, trigger);
            seen.forEach(s -> System.out.println("  " + s));

            Seen constructor = seen.stream()
                    .filter(s -> s.kind() == CompletionProposal.CONSTRUCTOR_INVOCATION)
                    .findFirst()
                    .orElse(null);
            if (constructor == null) {
                // completing on an empty prefix does not propose this type's constructor at all
                continue;
            }

            // The engine already reports the token under the real caret, whatever offset it was
            // triggered at - CompletionScanner scans the whole identifier around the cursor. Anything
            // that assumes otherwise is reasoning about a drift that does not happen.
            assertEquals("()", constructor.completion(), "constructor completion at " + trigger);
            assertEquals(caret, constructor.replaceStart(), "constructor replace start at " + trigger);
            assertEquals(caret, constructor.replaceEnd(), "constructor replace end at " + trigger);

            Seen requiredType = constructor.required().get(0);
            assertEquals(tokenStart, requiredType.replaceStart(), "required type replace start at " + trigger);
            assertEquals(caret, requiredType.replaceEnd(), "required type replace end at " + trigger);
        }
    }

    private static List<Seen> complete(ICompilationUnit cu, int offset) throws Exception {
        List<Seen> result = new ArrayList<>();
        cu.codeComplete(offset, new CompletionRequestor() {

            {
                setIgnored(CompletionProposal.CONSTRUCTOR_INVOCATION, false);
                setIgnored(CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION, false);
                setIgnored(CompletionProposal.TYPE_REF, false);
                setAllowsRequiredProposals(CompletionProposal.CONSTRUCTOR_INVOCATION,
                        CompletionProposal.TYPE_REF, true);
                setAllowsRequiredProposals(CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION,
                        CompletionProposal.TYPE_REF, true);
            }

            @Override
            public void accept(CompletionProposal proposal) {
                // only the proposals for the target type; a reduced trigger also returns the
                // whole JRE type universe, which is the point of subsequence matching but noise here
                String completion = proposal.getCompletion() == null ? "" : new String(proposal.getCompletion());
                String name = proposal.getName() == null ? "" : new String(proposal.getName());
                if (name.startsWith(TARGET_TYPE) || completion.endsWith(TARGET_TYPE)) {
                    result.add(snapshot(proposal));
                }
            }
        }, new NullProgressMonitor());
        return result;
    }

    private static Seen snapshot(CompletionProposal proposal) {
        List<Seen> required = new ArrayList<>();
        CompletionProposal[] requiredProposals = proposal.getRequiredProposals();
        if (requiredProposals != null) {
            for (CompletionProposal r : requiredProposals) {
                required.add(snapshot(r));
            }
        }
        return new Seen(proposal.getKind(),
                proposal.getCompletion() == null ? "<null>" : new String(proposal.getCompletion()),
                proposal.getReplaceStart(), proposal.getReplaceEnd(),
                proposal.getTokenStart(), proposal.getTokenEnd(),
                proposal.getName() == null ? "?" : new String(proposal.getName()),
                required);
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
