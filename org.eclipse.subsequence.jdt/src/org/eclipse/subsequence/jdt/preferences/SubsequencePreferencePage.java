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
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.subsequence.jdt.callmodel.CallModelIndex;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Preference page for Subsequence Matching settings, accessible under
 * Java > Editor > Content Assist > Subsequence Matching.
 */
public class SubsequencePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private DirectoryFieldEditor modelDirEditor;
    private Label modelDirStatusLabel;

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

        modelDirEditor = new DirectoryFieldEditor(
                SubsequencePreferences.PREF_MODEL_DIR_PATH,
                "Model directory:",
                getFieldEditorParent());
        addField(modelDirEditor);

        modelDirStatusLabel = new Label(getFieldEditorParent(), SWT.WRAP);
        GridData statusLayout = new GridData(SWT.FILL, SWT.TOP, true, false);
        statusLayout.horizontalSpan = 3; // DirectoryFieldEditor spans label, text and browse button
        statusLayout.widthHint = 400;
        modelDirStatusLabel.setLayoutData(statusLayout);
        updateModelDirStatus(SubsequencePreferences.getModelDirPath());

        getPreferenceStore().addPropertyChangeListener(event -> {
            if (SubsequencePreferences.PREF_MODEL_DIR_PATH.equals(event.getProperty())) {
                CallModelIndex.reset();
            }
        });
    }

    @Override
    public void propertyChange(PropertyChangeEvent event) {
        super.propertyChange(event);
        // Live-update the status line while the user edits the directory field
        if (event.getSource() == modelDirEditor && FieldEditor.VALUE.equals(event.getProperty())) {
            updateModelDirStatus(modelDirEditor.getStringValue());
        }
    }

    /**
     * Shows which model ZIPs are found in the given directory (or why none are used)
     * below the directory field.
     *
     * @param dirPath the directory path to describe
     */
    private void updateModelDirStatus(String dirPath) {
        if (modelDirStatusLabel == null || modelDirStatusLabel.isDisposed()) {
            return;
        }
        modelDirStatusLabel.setText(CallModelIndex.describeModelDir(dirPath));
        modelDirStatusLabel.getParent().requestLayout();
    }
}
