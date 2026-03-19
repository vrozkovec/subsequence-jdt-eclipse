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
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler that opens the {@link SubsequenceOpenTypeDialog}.
 * <p>
 * Registered via {@code plugin.xml} as a handler for the
 * {@code org.eclipse.subsequence.jdt.openType} command.
 */
public class OpenSubsequenceTypeHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(OpenSubsequenceTypeHandler.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            return null;
        }

        SubsequenceOpenTypeDialog dialog = new SubsequenceOpenTypeDialog(
                window.getShell(), false, SearchEngine.createWorkspaceScope());
        dialog.setInitialPattern("", FilteredItemsSelectionDialog.NONE); //$NON-NLS-1$

        if (dialog.open() == Window.OK) {
            Object result = dialog.getFirstResult();
            if (result instanceof TypeEntry entry) {
                openType(entry);
            }
        }

        return null;
    }

    /**
     * Opens the selected type in the Java editor.
     */
    private void openType(TypeEntry entry) {
        try {
            IType type = findType(entry.fullyQualifiedName());
            if (type != null) {
                JavaUI.openInEditor(type, true, true);
            }
        } catch (PartInitException | JavaModelException e) {
            LOG.error("Failed to open type: " + entry.fullyQualifiedName(), e); //$NON-NLS-1$
        }
    }

    /**
     * Finds an {@link IType} by its fully qualified name across all open Java projects.
     */
    private IType findType(String fullyQualifiedName) throws JavaModelException {
        var javaModel = org.eclipse.jdt.core.JavaCore.create(
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot());
        for (var project : javaModel.getJavaProjects()) {
            if (project.isOpen()) {
                IType type = project.findType(fullyQualifiedName);
                if (type != null) {
                    return type;
                }
            }
        }
        return null;
    }
}
