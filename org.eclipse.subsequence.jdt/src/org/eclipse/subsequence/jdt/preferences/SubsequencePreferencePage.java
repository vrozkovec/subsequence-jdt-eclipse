/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.preferences;

import static org.eclipse.jface.fieldassist.FieldDecorationRegistry.DEC_INFORMATION;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.fieldassist.ControlDecoration;
import org.eclipse.jface.fieldassist.FieldDecoration;
import org.eclipse.jface.fieldassist.FieldDecorationRegistry;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Preference page for Subsequence Matching settings, accessible under
 * Java > Editor > Content Assist > Subsequence Matching.
 */
public class SubsequencePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    public SubsequencePreferencePage() {
        super(GRID);
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, SubsequencePreferences.PLUGIN_ID));
        setMessage("Subsequence Matching Content Assist");
        setDescription("Configure the minimum prefix length before type/constructor proposals are triggered. "
                + "A shorter prefix will produce more proposals but may be slower.");
    }

    @Override
    protected void createFieldEditors() {
        IntegerFieldEditor prefixLengthEditor = new IntegerFieldEditor(
                SubsequencePreferences.PREF_MIN_PREFIX_LENGTH_FOR_TYPES,
                "Minimum prefix length for types:",
                getFieldEditorParent());
        prefixLengthEditor.setValidRange(1, 99);

        Text control = prefixLengthEditor.getTextControl(getFieldEditorParent());
        ControlDecoration dec = new ControlDecoration(control, SWT.TOP | SWT.LEFT, getFieldEditorParent());
        FieldDecoration infoDecoration = FieldDecorationRegistry.getDefault().getFieldDecoration(DEC_INFORMATION);
        dec.setImage(infoDecoration.getImage());
        dec.setDescriptionText("Minimum number of characters that must be typed before type and constructor "
                + "proposals are included. Proposals for fields, methods, and variables at the current "
                + "scope are always included regardless of this setting.");

        addField(prefixLengthEditor);
    }
}
