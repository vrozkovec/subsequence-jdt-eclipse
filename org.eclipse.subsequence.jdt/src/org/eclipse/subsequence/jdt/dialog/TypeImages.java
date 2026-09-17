/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.dialog;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.ui.ISharedImages;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.swt.graphics.Image;

/**
 * Resolves the shared JDT icon for a type from its modifier flags.
 */
final class TypeImages {

    private TypeImages() {
        // Not meant to be instantiated
    }

    /**
     * Returns the shared JDT image for a type with the given modifiers (interface, enum,
     * annotation or class), or {@code null} if the shared images cannot be obtained.
     *
     * @param modifiers the type's modifier flags as reported by the search engine
     */
    static Image forModifiers(int modifiers) {
        String key;
        if (Flags.isInterface(modifiers)) {
            key = ISharedImages.IMG_OBJS_INTERFACE;
        } else if (Flags.isEnum(modifiers)) {
            key = ISharedImages.IMG_OBJS_ENUM;
        } else if (Flags.isAnnotation(modifiers)) {
            key = ISharedImages.IMG_OBJS_ANNOTATION;
        } else {
            key = ISharedImages.IMG_OBJS_CLASS;
        }
        try {
            return JavaUI.getSharedImages().getImage(key);
        } catch (Exception e) {
            return null;
        }
    }
}
