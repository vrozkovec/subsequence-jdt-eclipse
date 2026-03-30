/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.dialog;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Utility for reading and writing Eclipse JDT's Type Filters preference.
 * <p>
 * Type Filters control which types are hidden from the Open Type dialog,
 * content assist, quick fix suggestions, and the Organize Imports disambiguation dialog.
 * Filters are stored as a semicolon-separated list of fully qualified type names
 * or package wildcard patterns (e.g. {@code com.sun.*}).
 */
public final class TypeFilterHelper {

    private static final ILog LOG = Platform.getLog(TypeFilterHelper.class);

    /** Preference store plugin ID for JDT UI. */
    private static final String JDT_UI_PLUGIN_ID = "org.eclipse.jdt.ui"; //$NON-NLS-1$

    /** Preference key for enabled type filters. */
    private static final String TYPE_FILTER_ENABLED_KEY = "org.eclipse.jdt.ui.typefilter.enabled"; //$NON-NLS-1$

    private static final ScopedPreferenceStore JDT_UI_STORE = new ScopedPreferenceStore(InstanceScope.INSTANCE,
            JDT_UI_PLUGIN_ID);

    /** Cached filter set and the raw preference value it was parsed from. */
    private static volatile String cachedRawValue;
    private static volatile Set<String> cachedFilters;

    private TypeFilterHelper() {
        // Not meant to be instantiated
    }

    /**
     * Adds a type filter pattern to Eclipse's Type Filters preference.
     * <p>
     * The pattern can be an exact fully qualified type name (e.g. {@code com.itextpdf.text.List})
     * or a package wildcard (e.g. {@code com.itextpdf.text.*}).
     *
     * @param pattern the filter pattern to add
     * @return {@code true} if the pattern was added, {@code false} if it was already present
     */
    public static boolean addTypeFilter(String pattern) {
        String current = JDT_UI_STORE.getString(TYPE_FILTER_ENABLED_KEY);
        Set<String> filters = parseFilters(current);

        if (!filters.add(pattern)) {
            return false; // Already present
        }

        String newValue = joinFilters(filters);
        JDT_UI_STORE.setValue(TYPE_FILTER_ENABLED_KEY, newValue);
        // Invalidate cache
        cachedRawValue = newValue;
        cachedFilters = filters;
        try {
            JDT_UI_STORE.save();
        } catch (IOException e) {
            LOG.error("Failed to save Type Filter preference", e); //$NON-NLS-1$
        }
        return true;
    }

    /**
     * Adds a package wildcard filter (e.g. {@code com.example.*}) for the given package name.
     *
     * @param packageName the package name (e.g. {@code com.example})
     * @return {@code true} if the filter was added, {@code false} if already present
     */
    public static boolean addPackageFilter(String packageName) {
        return addTypeFilter(packageName + ".*"); //$NON-NLS-1$
    }

    /**
     * Checks whether a fully qualified type name is covered by the current Type Filters.
     *
     * @param fullyQualifiedName the type's FQN to check
     * @return {@code true} if the type is filtered (hidden)
     */
    public static boolean isFiltered(String fullyQualifiedName) {
        Set<String> filters = getFilters();

        for (String filter : filters) {
            if (filter.equals(fullyQualifiedName)) {
                return true;
            }
            if (filter.endsWith(".*")) { //$NON-NLS-1$
                String pkg = filter.substring(0, filter.length() - 2);
                if (fullyQualifiedName.startsWith(pkg + ".") //$NON-NLS-1$
                        && fullyQualifiedName.indexOf('.', pkg.length() + 1) == -1) {
                    return true; // Matches package wildcard (direct children only)
                }
            }
        }
        return false;
    }

    /**
     * Returns the current filter set, using a cache to avoid re-reading the preference store
     * and re-parsing the string on every call. The cache is invalidated when the raw value
     * changes (e.g. after calling {@link #addTypeFilter}).
     */
    private static Set<String> getFilters() {
        String current = JDT_UI_STORE.getString(TYPE_FILTER_ENABLED_KEY);
        // Check cache — compare raw string identity/equality to detect external changes
        if (current.equals(cachedRawValue) && cachedFilters != null) {
            return cachedFilters;
        }
        Set<String> filters = parseFilters(current);
        cachedRawValue = current;
        cachedFilters = filters;
        return filters;
    }

    /**
     * Parses the semicolon-separated filter string into a mutable set.
     */
    private static Set<String> parseFilters(String value) {
        Set<String> filters = new LinkedHashSet<>();
        if (value != null && !value.isEmpty()) {
            Arrays.stream(value.split(";")) //$NON-NLS-1$
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(filters::add);
        }
        return filters;
    }

    /**
     * Joins a set of filter patterns into a semicolon-separated string.
     */
    private static String joinFilters(Set<String> filters) {
        return String.join(";", filters); //$NON-NLS-1$
    }
}
