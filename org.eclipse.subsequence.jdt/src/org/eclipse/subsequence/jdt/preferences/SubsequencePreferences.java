/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.preferences;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Constants and utility methods for Subsequence Matching plugin preferences.
 */
public final class SubsequencePreferences {

    private SubsequencePreferences() {
        // Not meant to be instantiated
    }

    /** The plugin identifier. */
    public static final String PLUGIN_ID = "org.eclipse.subsequence.jdt"; //$NON-NLS-1$

    /** Preference key for the minimum prefix length before type proposals are triggered. */
    public static final String PREF_MIN_PREFIX_LENGTH_FOR_TYPES = "subwords_min_prefix_length_for_types"; //$NON-NLS-1$

    /** Preference key for the path to a call model ZIP archive. */
    public static final String PREF_CALL_MODEL_ZIP_PATH = "subwords_call_model_zip_path"; //$NON-NLS-1$

    /**
     * Returns the current minimum prefix length for types from the preference store.
     */
    public static int getMinPrefixLengthForTypes() {
        ScopedPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, PLUGIN_ID);
        int value = store.getInt(PREF_MIN_PREFIX_LENGTH_FOR_TYPES);
        return value > 0 ? value : 2;
    }

    /**
     * Returns the configured path to the call model ZIP archive, or an empty string if not set.
     */
    public static String getCallModelZipPath() {
        ScopedPreferenceStore store = new ScopedPreferenceStore(InstanceScope.INSTANCE, PLUGIN_ID);
        return store.getString(PREF_CALL_MODEL_ZIP_PATH);
    }
}
