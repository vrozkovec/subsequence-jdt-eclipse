/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.dialog;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.ui.ISharedImages;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Dialog for choosing between ambiguous type imports during Organize Imports,
 * with support for adding unwanted types to Eclipse's Type Filters.
 * <p>
 * Replaces Eclipse's standard import disambiguation dialog. Shows one page per
 * ambiguous type reference, with a right-click context menu to add types or
 * packages to Type Filters.
 */
public class ImportChooserDialog extends TitleAreaDialog {

    private static final int SKIP_ID = IDialogConstants.CLIENT_ID;
    private static final int BACK_ID = IDialogConstants.BACK_ID;
    private static final int NEXT_ID = IDialogConstants.NEXT_ID;
    private static final int FINISH_ID = IDialogConstants.FINISH_ID;

    private final List<List<TypeNameMatch>> pages;
    private final TypeNameMatch[] selections;
    private int currentPage;
    private TableViewer tableViewer;

    /**
     * Creates a new import chooser dialog.
     *
     * @param parentShell the parent shell
     * @param choices     the ambiguous type choices — each element is an array of alternatives
     *                    for one unresolved type reference, as provided by JDT's
     *                    {@code IChooseImportQuery.chooseImports()}
     */
    public ImportChooserDialog(Shell parentShell, TypeNameMatch[][] choices) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE);
        this.pages = new ArrayList<>();
        for (TypeNameMatch[] choice : choices) {
            List<TypeNameMatch> page = new ArrayList<>();
            for (TypeNameMatch match : choice) {
                if (!TypeFilterHelper.isFiltered(match.getFullyQualifiedName())) {
                    page.add(match);
                }
            }
            pages.add(page);
        }
        this.selections = new TypeNameMatch[choices.length];
        this.currentPage = 0;

        // Auto-resolve pages with exactly one remaining choice
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).size() == 1) {
                selections[i] = pages.get(i).get(0);
            }
        }

        // Advance to first page that actually needs user input
        advanceToNextUnresolved();
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Organize Imports"); //$NON-NLS-1$
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);

        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout());
        container.setLayoutData(new GridData(GridData.FILL_BOTH));

        tableViewer = new TableViewer(container, SWT.SINGLE | SWT.BORDER | SWT.FULL_SELECTION);
        tableViewer.getTable().setLayoutData(new GridData(GridData.FILL_BOTH));
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.setLabelProvider(new TypeNameMatchLabelProvider());

        // Double-click selects and advances
        tableViewer.addDoubleClickListener(event -> {
            IStructuredSelection sel = tableViewer.getStructuredSelection();
            if (!sel.isEmpty()) {
                selections[currentPage] = (TypeNameMatch) sel.getFirstElement();
                if (hasNextUnresolved()) {
                    goToNextUnresolved();
                } else {
                    setReturnCode(OK);
                    close();
                }
            }
        });

        // Context menu for Type Filters
        createContextMenu();

        showPage(currentPage);
        return area;
    }

    /**
     * Creates the right-click context menu with "Add to Type Filters" actions.
     */
    private void createContextMenu() {
        Menu menu = new Menu(tableViewer.getTable());

        MenuItem addType = new MenuItem(menu, SWT.PUSH);
        addType.setText("Add Type to Type Filters"); //$NON-NLS-1$
        addType.addListener(SWT.Selection, e -> {
            TypeNameMatch selected = getSelectedMatch();
            if (selected != null) {
                TypeFilterHelper.addTypeFilter(selected.getFullyQualifiedName());
                pages.get(currentPage).remove(selected);
                handlePageAfterRemoval();
            }
        });

        MenuItem addPackage = new MenuItem(menu, SWT.PUSH);
        addPackage.setText("Add Package to Type Filters"); //$NON-NLS-1$
        addPackage.addListener(SWT.Selection, e -> {
            TypeNameMatch selected = getSelectedMatch();
            if (selected != null) {
                String pkg = selected.getPackageName();
                TypeFilterHelper.addPackageFilter(pkg);
                // Remove all types from this package in current page at once
                List<TypeNameMatch> page = pages.get(currentPage);
                page.removeIf(m -> m.getPackageName().equals(pkg));
                handlePageAfterRemoval();
            }
        });

        menu.addListener(SWT.Show, e -> {
            boolean hasSelection = !tableViewer.getStructuredSelection().isEmpty();
            addType.setEnabled(hasSelection);
            addPackage.setEnabled(hasSelection);
        });

        tableViewer.getTable().setMenu(menu);
    }

    /**
     * Handles the current page after items have been removed.
     * Auto-selects if only one choice remains, auto-skips if none remain,
     * or refreshes the table if multiple choices remain.
     */
    private void handlePageAfterRemoval() {
        List<TypeNameMatch> page = pages.get(currentPage);

        if (page.size() == 1) {
            // Auto-select the only remaining choice
            selections[currentPage] = page.get(0);
            if (hasNextUnresolved()) {
                goToNextUnresolved();
            } else {
                setReturnCode(OK);
                close();
            }
        } else if (page.isEmpty()) {
            // No choices left — skip this import
            selections[currentPage] = null;
            if (hasNextUnresolved()) {
                goToNextUnresolved();
            } else {
                setReturnCode(OK);
                close();
            }
        } else {
            tableViewer.setInput(page);
            tableViewer.getTable().select(0);
        }
    }

    /**
     * Returns the currently selected {@link TypeNameMatch} in the table, or {@code null}.
     */
    private TypeNameMatch getSelectedMatch() {
        IStructuredSelection sel = tableViewer.getStructuredSelection();
        if (!sel.isEmpty() && sel.getFirstElement() instanceof TypeNameMatch match) {
            return match;
        }
        return null;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, SKIP_ID, "Skip", false); //$NON-NLS-1$
        createButton(parent, BACK_ID, IDialogConstants.BACK_LABEL, false);
        createButton(parent, NEXT_ID, IDialogConstants.NEXT_LABEL, false);
        createButton(parent, FINISH_ID, IDialogConstants.FINISH_LABEL, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        updateButtons();
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == SKIP_ID) {
            selections[currentPage] = null;
            if (hasNextUnresolved()) {
                goToNextUnresolved();
            } else {
                setReturnCode(OK);
                close();
            }
        } else if (buttonId == BACK_ID) {
            goToPreviousPage();
        } else if (buttonId == NEXT_ID) {
            saveCurrentSelection();
            goToNextUnresolved();
        } else if (buttonId == FINISH_ID) {
            saveCurrentSelection();
            // Auto-select first item for any remaining unresolved pages
            for (int i = 0; i < pages.size(); i++) {
                if (selections[i] == null && !pages.get(i).isEmpty()) {
                    selections[i] = pages.get(i).get(0);
                }
            }
            setReturnCode(OK);
            close();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    /**
     * Saves the current table selection for the current page.
     */
    private void saveCurrentSelection() {
        TypeNameMatch selected = getSelectedMatch();
        if (selected != null) {
            selections[currentPage] = selected;
        } else if (!pages.get(currentPage).isEmpty()) {
            // Default to first item if nothing explicitly selected
            selections[currentPage] = pages.get(currentPage).get(0);
        }
    }

    /**
     * Shows the specified page in the dialog.
     */
    private void showPage(int pageIndex) {
        currentPage = pageIndex;
        List<TypeNameMatch> page = pages.get(pageIndex);

        setTitle("Choose type to import:"); //$NON-NLS-1$
        setMessage("Page " + (pageIndex + 1) + " of " + pages.size()); //$NON-NLS-1$ //$NON-NLS-2$

        if (tableViewer != null) {
            tableViewer.setInput(page);
            // Pre-select previously chosen item or first item
            if (selections[pageIndex] != null && page.contains(selections[pageIndex])) {
                tableViewer.getTable().select(page.indexOf(selections[pageIndex]));
            } else if (!page.isEmpty()) {
                tableViewer.getTable().select(0);
            }
        }
        updateButtons();
    }

    /**
     * Advances to the next page that still needs user input.
     */
    private void goToNextUnresolved() {
        for (int i = currentPage + 1; i < pages.size(); i++) {
            if (needsUserInput(i)) {
                showPage(i);
                return;
            }
        }
        // No more unresolved — dialog can close
        setReturnCode(OK);
        close();
    }

    /**
     * Goes back to the previous page.
     */
    private void goToPreviousPage() {
        if (currentPage > 0) {
            showPage(currentPage - 1);
        }
    }

    /**
     * Advances {@link #currentPage} to the first page needing user input,
     * called during construction before the dialog is opened.
     */
    private void advanceToNextUnresolved() {
        for (int i = 0; i < pages.size(); i++) {
            if (needsUserInput(i)) {
                currentPage = i;
                return;
            }
        }
        // All resolved — currentPage stays at 0, dialog will auto-close
    }

    /**
     * Returns {@code true} if there is at least one unresolved page after the current one.
     */
    private boolean hasNextUnresolved() {
        for (int i = currentPage + 1; i < pages.size(); i++) {
            if (needsUserInput(i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the page at the given index needs user input
     * (more than one choice and not yet selected).
     */
    private boolean needsUserInput(int pageIndex) {
        return pages.get(pageIndex).size() > 1 && selections[pageIndex] == null;
    }

    /**
     * Updates button enabled state based on current page position.
     */
    private void updateButtons() {
        if (getButton(BACK_ID) != null) {
            getButton(BACK_ID).setEnabled(currentPage > 0);
        }
        if (getButton(NEXT_ID) != null) {
            getButton(NEXT_ID).setEnabled(hasNextUnresolved());
        }
    }

    /**
     * Returns the user's selections. Elements may be {@code null} for skipped imports.
     *
     * @return array of selected {@link TypeNameMatch} objects, one per ambiguous reference
     */
    public TypeNameMatch[] getSelections() {
        return selections;
    }

    /**
     * Returns {@code true} if all ambiguities were resolved without needing user input
     * (all pages had 0 or 1 choice after applying Type Filters).
     */
    public boolean isFullyResolved() {
        for (int i = 0; i < pages.size(); i++) {
            if (needsUserInput(i)) {
                return false;
            }
        }
        return true;
    }

    // --- Label provider ---

    /**
     * Label provider for {@link TypeNameMatch} entries showing FQN with type icon.
     */
    private static class TypeNameMatchLabelProvider extends ColumnLabelProvider {

        @Override
        public String getText(Object element) {
            if (element instanceof TypeNameMatch match) {
                return match.getFullyQualifiedName();
            }
            return super.getText(element);
        }

        @Override
        public Image getImage(Object element) {
            if (element instanceof TypeNameMatch match) {
                return getTypeImage(match.getModifiers());
            }
            return null;
        }

        private Image getTypeImage(int modifiers) {
            try {
                if (Flags.isInterface(modifiers)) {
                    return JavaUI.getSharedImages().getImage(ISharedImages.IMG_OBJS_INTERFACE);
                } else if (Flags.isEnum(modifiers)) {
                    return JavaUI.getSharedImages().getImage(ISharedImages.IMG_OBJS_ENUM);
                } else if (Flags.isAnnotation(modifiers)) {
                    return JavaUI.getSharedImages().getImage(ISharedImages.IMG_OBJS_ANNOTATION);
                } else {
                    return JavaUI.getSharedImages().getImage(ISharedImages.IMG_OBJS_CLASS);
                }
            } catch (Exception e) {
                return null;
            }
        }
    }
}
