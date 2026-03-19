/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.dialog;

/**
 * Lightweight holder for type information collected during search.
 * Avoids holding {@code IType} handles during the search phase for performance.
 *
 * @param simpleName          the simple (unqualified) type name
 * @param packageName         the package name, or empty string for the default package
 * @param fullyQualifiedName  the fully qualified type name (e.g. {@code java.util.HashMap})
 * @param modifiers           the type's modifier flags (see {@link org.eclipse.jdt.core.Flags})
 */
public record TypeEntry(String simpleName, String packageName, String fullyQualifiedName, int modifiers) {

    @Override
    public String toString() {
        return packageName.isEmpty() ? simpleName : simpleName + " - " + packageName; //$NON-NLS-1$
    }
}
