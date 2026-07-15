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

    /** Preference key for the path to the model directory containing ZIP archives. */
    public static final String PREF_MODEL_DIR_PATH = "subwords_model_dir_path"; //$NON-NLS-1$

    /** Shared store — the getters run on the completion hot path, once per keystroke. */
    private static final ScopedPreferenceStore STORE = new ScopedPreferenceStore(InstanceScope.INSTANCE, PLUGIN_ID);

    /**
     * Returns the current minimum prefix length for types from the preference store.
     */
    public static int getMinPrefixLengthForTypes() {
        int value = STORE.getInt(PREF_MIN_PREFIX_LENGTH_FOR_TYPES);
        return value > 0 ? value : 2;
    }

    /**
     * Returns the configured path to the model directory, or an empty string if not set.
     */
    public static String getModelDirPath() {
        return STORE.getString(PREF_MODEL_DIR_PATH);
    }
}
