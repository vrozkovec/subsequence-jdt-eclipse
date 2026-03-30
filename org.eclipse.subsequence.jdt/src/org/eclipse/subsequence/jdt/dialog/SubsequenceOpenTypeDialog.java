/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.dialog;

import java.util.Comparator;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.jdt.ui.ISharedImages;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.subsequence.jdt.core.LCSS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;

/**
 * A type selection dialog that supports subsequence matching.
 * <p>
 * Works like the standard Open Type dialog (Ctrl+Shift+T) but additionally
 * matches types where the typed characters appear in order but not necessarily
 * contiguously. For example, typing "hml" matches "HashMapLocal" and "HtmlMailer".
 * <p>
 * Standard matching (prefix, CamelCase, wildcard) is tried first; subsequence
 * is the fallback.
 */
public class SubsequenceOpenTypeDialog extends FilteredItemsSelectionDialog {

    private static final ILog LOG = Platform.getLog(SubsequenceOpenTypeDialog.class);
    private static final String DIALOG_SETTINGS_ID = "org.eclipse.subsequence.jdt.openTypeDialog"; //$NON-NLS-1$

    private final IJavaSearchScope searchScope;

    /**
     * Creates a new Open Type dialog with subsequence matching.
     *
     * @param shell       the parent shell
     * @param multiSelect whether multiple types can be selected
     * @param scope       the Java search scope, or {@code null} for workspace scope
     */
    public SubsequenceOpenTypeDialog(Shell shell, boolean multiSelect, IJavaSearchScope scope) {
        super(shell, multiSelect);
        this.searchScope = scope != null ? scope : SearchEngine.createWorkspaceScope();
        setTitle("Open Type (Subsequence)"); //$NON-NLS-1$
        setMessage("Enter type name prefix, pattern (*, ?, or CamelCase), or subsequence:"); //$NON-NLS-1$
        setListLabelProvider(new TypeEntryLabelProvider());
        setDetailsLabelProvider(new TypeEntryDetailsLabelProvider());
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Control control = super.createDialogArea(parent);
        Table table = findTable((Composite) control);
        if (table != null) {
            createTypeFilterContextMenu(table);
        }
        return control;
    }

    @Override
    protected Control createExtendedContentArea(Composite parent) {
        return null;
    }

    /**
     * Creates a right-click context menu on the table with "Add to Type Filters" actions.
     */
    private void createTypeFilterContextMenu(Table table) {
        Menu menu = new Menu(table);

        MenuItem addType = new MenuItem(menu, SWT.PUSH);
        addType.setText("Add Type to Type Filters"); //$NON-NLS-1$
        addType.addListener(SWT.Selection, e -> {
            TypeEntry entry = getSelectedTypeEntry(table);
            if (entry != null) {
                TypeFilterHelper.addTypeFilter(entry.fullyQualifiedName());
                applyFilter(); // refresh to hide the filtered type
            }
        });

        MenuItem addPackage = new MenuItem(menu, SWT.PUSH);
        addPackage.setText("Add Package to Type Filters"); //$NON-NLS-1$
        addPackage.addListener(SWT.Selection, e -> {
            TypeEntry entry = getSelectedTypeEntry(table);
            if (entry != null && !entry.packageName().isEmpty()) {
                TypeFilterHelper.addPackageFilter(entry.packageName());
                applyFilter(); // refresh to hide filtered types
            }
        });

        menu.addListener(SWT.Show, e -> {
            TypeEntry entry = getSelectedTypeEntry(table);
            addType.setEnabled(entry != null);
            addPackage.setEnabled(entry != null && !entry.packageName().isEmpty());
        });

        table.setMenu(menu);
    }

    /**
     * Returns the selected {@link TypeEntry} from the table, or {@code null}.
     */
    private TypeEntry getSelectedTypeEntry(Table table) {
        int index = table.getSelectionIndex();
        if (index >= 0) {
            Object data = table.getItem(index).getData();
            if (data instanceof TypeEntry entry) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Recursively finds the first {@link Table} widget in a composite hierarchy.
     */
    private static Table findTable(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Table table) {
                return table;
            }
            if (child instanceof Composite comp) {
                Table result = findTable(comp);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    @Override
    protected IDialogSettings getDialogSettings() {
        // Dialog settings persist dialog size, position, and history between invocations.
        // We store them in the Eclipse preferences for this plugin.
        IDialogSettings root = new org.eclipse.jface.dialogs.DialogSettings(DIALOG_SETTINGS_ID);
        return root;
    }

    @Override
    protected IStatus validateItem(Object item) {
        return Status.OK_STATUS;
    }

    @Override
    protected ItemsFilter createFilter() {
        return new SubsequenceTypeFilter();
    }

    @Override
    protected Comparator<TypeEntry> getItemsComparator() {
        return (e1, e2) -> {
            int cmp = e1.simpleName().compareToIgnoreCase(e2.simpleName());
            if (cmp != 0) {
                return cmp;
            }
            return e1.packageName().compareToIgnoreCase(e2.packageName());
        };
    }

    @Override
    protected void fillContentProvider(AbstractContentProvider contentProvider, ItemsFilter itemsFilter,
            IProgressMonitor progressMonitor) throws CoreException {
        progressMonitor.beginTask("Searching for types...", IProgressMonitor.UNKNOWN); //$NON-NLS-1$
        try {
            SearchEngine engine = new SearchEngine();
            engine.searchAllTypeNames(
                    null, // all packages
                    SearchPattern.R_PATTERN_MATCH,
                    null, // all type names
                    SearchPattern.R_PATTERN_MATCH,
                    IJavaSearchConstants.TYPE,
                    searchScope,
                    new TypeNameRequestor() {
                        @Override
                        public void acceptType(int modifiers, char[] packageNameChars, char[] simpleTypeNameChars,
                                char[][] enclosingTypeNames, String path) {
                            String simpleName = buildSimpleName(simpleTypeNameChars, enclosingTypeNames);
                            String packageName = new String(packageNameChars);
                            String fqn = packageName.isEmpty()
                                    ? simpleName
                                    : packageName + "." + simpleName; //$NON-NLS-1$
                            TypeEntry entry = new TypeEntry(simpleName, packageName, fqn, modifiers);
                            contentProvider.add(entry, itemsFilter);
                        }
                    },
                    IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                    progressMonitor);
        } finally {
            progressMonitor.done();
        }
    }

    /**
     * Builds a simple name including enclosing type names (e.g. "Outer.Inner").
     */
    private static String buildSimpleName(char[] simpleTypeName, char[][] enclosingTypeNames) {
        if (enclosingTypeNames == null || enclosingTypeNames.length == 0) {
            return new String(simpleTypeName);
        }
        StringBuilder sb = new StringBuilder();
        for (char[] enclosing : enclosingTypeNames) {
            if (enclosing != null && enclosing.length > 0) {
                sb.append(enclosing).append('.');
            }
        }
        sb.append(simpleTypeName);
        return sb.toString();
    }

    @Override
    public String getElementName(Object item) {
        if (item instanceof TypeEntry entry) {
            return entry.simpleName();
        }
        return item.toString();
    }

    // --- Filter with subsequence matching ---

    /**
     * Custom items filter that matches using standard patterns first,
     * then falls back to subsequence matching.
     */
    private class SubsequenceTypeFilter extends ItemsFilter {

        @Override
        public boolean matchItem(Object item) {
            if (!(item instanceof TypeEntry entry)) {
                return false;
            }
            // Respect Eclipse Type Filters
            if (TypeFilterHelper.isFiltered(entry.fullyQualifiedName())) {
                return false;
            }
            String pattern = getPattern();
            if (pattern == null || pattern.isEmpty()) {
                return true;
            }
            String simpleName = entry.simpleName();

            // Try standard matching first (prefix, wildcard, CamelCase)
            if (matchesStandard(simpleName, pattern)) {
                return true;
            }

            // Fallback: subsequence matching
            return LCSS.containsSubsequence(simpleName, pattern);
        }

        /**
         * Checks standard matching: prefix, wildcard, CamelCase.
         */
        private boolean matchesStandard(String name, String pattern) {
            // Wildcard pattern
            if (pattern.contains("*") || pattern.contains("?")) { //$NON-NLS-1$ //$NON-NLS-2$
                return matchesWildcard(name, pattern);
            }

            // Prefix match (case-insensitive)
            if (name.toLowerCase().startsWith(pattern.toLowerCase())) {
                return true;
            }

            // CamelCase match
            if (hasCamelCaseChars(pattern)) {
                return CharOperation.camelCaseMatch(pattern.toCharArray(), name.toCharArray());
            }

            return false;
        }

        /**
         * Simple wildcard matching.
         */
        private boolean matchesWildcard(String name, String pattern) {
            String regex = pattern.replace(".", "\\.") //$NON-NLS-1$ //$NON-NLS-2$
                    .replace("*", ".*") //$NON-NLS-1$ //$NON-NLS-2$
                    .replace("?", "."); //$NON-NLS-1$ //$NON-NLS-2$
            try {
                return name.matches("(?i)" + regex); //$NON-NLS-1$
            } catch (PatternSyntaxException e) {
                return false;
            }
        }

        private boolean hasCamelCaseChars(String pattern) {
            for (int i = 1; i < pattern.length(); i++) {
                if (Character.isUpperCase(pattern.charAt(i))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isConsistentItem(Object item) {
            return true;
        }

        @Override
        public boolean isSubFilter(ItemsFilter filter) {
            // For subsequence matching, a longer pattern is not necessarily a sub-filter
            // of a shorter one (unlike prefix matching), so always return false to force re-filtering.
            return false;
        }

        @Override
        public boolean equalsFilter(ItemsFilter filter) {
            if (!(filter instanceof SubsequenceTypeFilter other)) {
                return false;
            }
            return Objects.equals(getPattern(), other.getPattern());
        }
    }

    // --- Label providers ---

    /**
     * Label provider for the list, showing "TypeName - package" with highlighted matches.
     */
    private static class TypeEntryLabelProvider extends LabelProvider implements IStyledLabelProvider {

        @Override
        public String getText(Object element) {
            if (element instanceof TypeEntry entry) {
                return entry.toString();
            }
            return super.getText(element);
        }

        @Override
        public Image getImage(Object element) {
            if (element instanceof TypeEntry entry) {
                return getTypeImage(entry.modifiers());
            }
            return null;
        }

        @Override
        public StyledString getStyledText(Object element) {
            if (!(element instanceof TypeEntry entry)) {
                return new StyledString(element != null ? element.toString() : ""); //$NON-NLS-1$
            }

            StyledString styled = new StyledString(entry.simpleName());
            if (!entry.packageName().isEmpty()) {
                styled.append(" - " + entry.packageName(), StyledString.QUALIFIER_STYLER); //$NON-NLS-1$
            }
            return styled;
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

    /**
     * Label provider for the details area, showing the fully qualified name.
     */
    private static class TypeEntryDetailsLabelProvider extends LabelProvider {

        @Override
        public String getText(Object element) {
            if (element instanceof TypeEntry entry) {
                return entry.fullyQualifiedName();
            }
            return super.getText(element);
        }
    }
}
