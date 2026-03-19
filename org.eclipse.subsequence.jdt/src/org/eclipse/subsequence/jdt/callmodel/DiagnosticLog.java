/**
 * Copyright (c) 2024 Eclipse Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.subsequence.jdt.callmodel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Diagnostic file logger that appends timestamped messages to {@code /data/tmp/subsequence.log}.
 * <p>
 * Catches all exceptions silently — must never break code completion.
 */
final class DiagnosticLog {

    private static final Path LOG_FILE = Path.of("/data/tmp/subsequence.log"); //$NON-NLS-1$
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"); //$NON-NLS-1$

    private DiagnosticLog() {
        // Not meant to be instantiated
    }

    /**
     * Appends a single timestamped line to the diagnostic log file.
     *
     * @param message the message to log
     */
    static void log(String message) {
        try (BufferedWriter w = Files.newBufferedWriter(LOG_FILE,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(FMT.format(LocalDateTime.now()));
            w.write("  "); //$NON-NLS-1$
            w.write(message);
            w.newLine();
        } catch (IOException ignored) {
            // must never break completion
        }
    }
}
