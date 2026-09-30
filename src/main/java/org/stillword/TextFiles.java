/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 *
 * @author veronique
 */

final class TextFiles {
    private TextFiles() {}

    static String filename(String title) {
        String safe = title.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "-")
                .replaceAll("\\s+", " ").strip().replaceAll("[. ]+$", "");
        if (safe.isBlank()) safe = "video";
        return safe.substring(0, Math.min(safe.length(), 100));
    }

    static void save(Path destination, String content) throws IOException {
        Path target = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".stillword-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Seconds, MM:SS, or HH:MM:SS; a blank end time means the end of the video. */
    static Double time(String value) {
        if (value.isBlank()) return null;
        String[] parts = value.strip().split(":", -1);
        if (parts.length > 3) throw new IllegalArgumentException("Use seconds, MM:SS, or HH:MM:SS for a video time.");
        double total = 0;
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches(i == parts.length - 1 ? "\\d+(?:\\.\\d+)?" : "\\d+"))
                throw new IllegalArgumentException("Times must contain positive numbers, for example 05:30 or 1:02:00.");
            double number = Double.parseDouble(parts[i]);
            if (i > 0 && number >= 60) throw new IllegalArgumentException("Minutes and seconds after a colon must be less than 60.");
            total = total * 60 + number;
        }
        if (!Double.isFinite(total)) throw new IllegalArgumentException("The video time is too large.");
        return total;
    }
}
