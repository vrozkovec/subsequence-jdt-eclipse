/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.dialog;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation.IChooseImportQuery;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler that runs Organize Imports with a custom disambiguation dialog
 * that supports adding unwanted types to Eclipse's Type Filters.
 * <p>
 * Uses JDT's {@link OrganizeImportsOperation} for all import logic — produces
 * exactly the same results as standard Eclipse Organize Imports. The only difference
 * is the disambiguation dialog shown when multiple types match the same simple name.
 */
@SuppressWarnings("restriction")
public class OrganizeImportsWithFiltersHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(OrganizeImportsWithFiltersHandler.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor == null) {
            return null;
        }

        ITypeRoot typeRoot = EditorUtility.getEditorInputJavaElement(editor, false);
        if (!(typeRoot instanceof ICompilationUnit cu)) {
            return null;
        }

        Shell shell = HandlerUtil.getActiveShell(event);

        try {
            organizeImports(cu, shell);
        } catch (Exception e) {
            LOG.error("Failed to organize imports", e); //$NON-NLS-1$
        }

        return null;
    }

    /**
     * Runs the organize imports operation on the given compilation unit.
     *
     * @param cu    the compilation unit to organize imports for
     * @param shell the parent shell for dialogs
     */
    private void organizeImports(ICompilationUnit cu, Shell shell) throws Exception {
        // Parse the AST
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        // Create the custom import query
        IChooseImportQuery chooseQuery = createChooseImportQuery(shell);

        // Create and run the operation
        OrganizeImportsOperation operation = new OrganizeImportsOperation(
                cu,
                astRoot,
                true,  // ignoreLowerCaseNames
                false, // save — we apply edits ourselves
                true,  // allowSyntaxErrors
                chooseQuery);

        // Create and apply the text edits
        TextEdit edit = operation.createTextEdit(new NullProgressMonitor());
        if (edit != null && edit.hasChildren()) {
            cu.applyTextEdit(edit, new NullProgressMonitor());
            cu.reconcile(ICompilationUnit.NO_AST, false, null, new NullProgressMonitor());
        }
    }

    /**
     * Creates an {@link IChooseImportQuery} that shows the custom
     * {@link ImportChooserDialog} for ambiguous type resolution.
     *
     * @param shell the parent shell for the dialog
     * @return the import query implementation
     */
    private IChooseImportQuery createChooseImportQuery(Shell shell) {
        return (TypeNameMatch[][] choices, ISourceRange[] ranges) -> {
            // Check if all ambiguities can be auto-resolved by Type Filters
            ImportChooserDialog dialog = new ImportChooserDialog(shell, choices);
            if (dialog.isFullyResolved()) {
                return dialog.getSelections();
            }

            // Show the dialog for user input
            if (dialog.open() == Window.OK) {
                return dialog.getSelections();
            }

            // Cancelled — return empty array to skip all
            return new TypeNameMatch[0];
        };
    }
}
