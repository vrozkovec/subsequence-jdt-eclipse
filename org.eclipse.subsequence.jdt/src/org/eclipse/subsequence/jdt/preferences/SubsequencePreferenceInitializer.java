/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

/**
 * Initializes default preference values for the Subsequence Matching plugin.
 */
public class SubsequencePreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(SubsequencePreferences.PLUGIN_ID);
        defaults.putInt(SubsequencePreferences.PREF_MIN_PREFIX_LENGTH_FOR_TYPES, 2);
        defaults.put(SubsequencePreferences.PREF_CALL_MODEL_ZIP_PATH, ""); //$NON-NLS-1$
    }
}
