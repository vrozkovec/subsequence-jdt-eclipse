/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;

/**
 * Eclipse command handler that analyzes all Java source files in the workspace
 * to count method call frequencies per declaring type.
 * <p>
 * The analysis runs as a background {@link Job} with progress reporting. Results
 * are normalized per type (most-called method gets 1.0) and stored via
 * {@link CallModelIndex#setWorkspaceData(Map)}.
 * <p>
 * Triggered from Navigate menu → "Analyze Workspace Method Calls".
 */
public class WorkspaceAnalyzer extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(WorkspaceAnalyzer.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Job job = new Job("Analyzing workspace method calls") { //$NON-NLS-1$

            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    Map<String, Map<String, Integer>> counts = analyzeWorkspace(monitor);
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }

                    Map<String, Map<String, Double>> normalized = normalize(counts);
                    CallModelIndex.getInstance().setWorkspaceData(normalized);
                    LOG.info("Workspace analysis complete: " + normalized.size() + " types"); //$NON-NLS-1$ //$NON-NLS-2$
                    return Status.OK_STATUS;
                } catch (Exception e) {
                    LOG.error("Workspace analysis failed", e); //$NON-NLS-1$
                    return Status.error("Workspace analysis failed: " + e.getMessage(), e); //$NON-NLS-1$
                }
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    /**
     * Scans all source compilation units in the workspace and counts method invocations
     * grouped by declaring type and method name.
     *
     * @param monitor progress monitor for reporting and cancellation
     * @return map of type name to (method name to invocation count)
     * @throws JavaModelException if workspace traversal fails
     */
    private Map<String, Map<String, Integer>> analyzeWorkspace(IProgressMonitor monitor) throws JavaModelException {
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        IJavaModel javaModel = JavaCore.create(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot());
        IJavaProject[] projects = javaModel.getJavaProjects();

        monitor.beginTask("Analyzing workspace", projects.length); //$NON-NLS-1$

        for (IJavaProject project : projects) {
            if (monitor.isCanceled()) {
                break;
            }
            monitor.subTask(project.getElementName());
            analyzeProject(project, counts);
            monitor.worked(1);
        }

        monitor.done();
        return counts;
    }

    /**
     * Analyzes a single Java project, iterating its source package fragment roots.
     */
    private void analyzeProject(IJavaProject project, Map<String, Map<String, Integer>> counts)
            throws JavaModelException {
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.isArchive() || root.isExternal()) {
                continue; // skip JARs and external sources
            }

            for (IJavaElement element : root.getChildren()) {
                if (element instanceof IPackageFragment pkg) {
                    analyzePackage(project, pkg, counts);
                }
            }
        }
    }

    /**
     * Analyzes all compilation units in a package fragment.
     */
    private void analyzePackage(IJavaProject project, IPackageFragment pkg,
            Map<String, Map<String, Integer>> counts) throws JavaModelException {
        for (ICompilationUnit cu : pkg.getCompilationUnits()) {
            analyzeCompilationUnit(project, cu, counts);
        }
    }

    /**
     * Parses a single compilation unit and visits its method invocations.
     */
    private void analyzeCompilationUnit(IJavaProject project, ICompilationUnit cu,
            Map<String, Map<String, Integer>> counts) {
        ASTParser parser = ASTParser.newParser(AST.JLS17);
        parser.setResolveBindings(true);
        parser.setProject(project);
        parser.setSource(cu);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        ast.accept(new MethodCallVisitor(counts));
    }

    /**
     * Normalizes raw invocation counts to probabilities in [0.0, 1.0] per type.
     * <p>
     * The most-called method for each type receives 1.0, and all others are scaled
     * proportionally.
     *
     * @param counts raw invocation counts
     * @return normalized probabilities
     */
    static Map<String, Map<String, Double>> normalize(Map<String, Map<String, Integer>> counts) {
        Map<String, Map<String, Double>> normalized = new HashMap<>();

        for (var typeEntry : counts.entrySet()) {
            Map<String, Integer> methodCounts = typeEntry.getValue();
            int maxCount = methodCounts.values().stream().mapToInt(Integer::intValue).max().orElse(1);
            if (maxCount == 0) {
                continue;
            }

            Map<String, Double> probs = new HashMap<>();
            for (var methodEntry : methodCounts.entrySet()) {
                probs.put(methodEntry.getKey(), (double) methodEntry.getValue() / maxCount);
            }
            normalized.put(typeEntry.getKey(), Collections.unmodifiableMap(probs));
        }

        return normalized;
    }

    /**
     * AST visitor that counts method invocations grouped by declaring type.
     */
    private static class MethodCallVisitor extends ASTVisitor {

        private final Map<String, Map<String, Integer>> counts;

        MethodCallVisitor(Map<String, Map<String, Integer>> counts) {
            this.counts = counts;
        }

        @Override
        public boolean visit(MethodInvocation node) {
            IMethodBinding binding = node.resolveMethodBinding();
            if (binding == null) {
                return true;
            }

            ITypeBinding declaringClass = binding.getDeclaringClass();
            if (declaringClass == null) {
                return true;
            }

            // Use the erased type to avoid generics noise (HashMap vs HashMap<K,V>)
            ITypeBinding erasure = declaringClass.getErasure();
            String typeName = (erasure != null ? erasure : declaringClass).getQualifiedName();
            if (typeName == null || typeName.isEmpty()) {
                return true;
            }

            String methodName = binding.getName();
            counts.computeIfAbsent(typeName, k -> new HashMap<>())
                    .merge(methodName, 1, Integer::sum);

            return true;
        }
    }
}
