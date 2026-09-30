/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author veronique
 */

/** Removes explicit AI review annotations while retaining their exact editable-text locations. */
public final class SummaryReview {
    private static final String PREFIX = "[[check:";

    /** Offsets are half-open UTF-16 indices in Parsed.text(), as used by Swing text documents. */
    public record Mark(String phrase, int startOffset, int endOffset) { }
    public record Parsed(String text, List<Mark> marks) {
        public Parsed { marks = List.copyOf(marks); }
    }

    private SummaryReview() { }

    /** Empty or malformed annotations stay verbatim; null input is treated as empty text. */
    public static Parsed parse(String generatedText) {
        if (generatedText == null || generatedText.isEmpty()) return new Parsed("", List.of());
        StringBuilder clean = new StringBuilder();
        List<Mark> marks = new ArrayList<>();
        int cursor = 0;
        while (cursor < generatedText.length()) {
            int opening = generatedText.indexOf("[[", cursor);
            if (opening < 0) { clean.append(generatedText, cursor, generatedText.length()); break; }
            clean.append(generatedText, cursor, opening);
            int depth = 1, closing = -1;
            boolean nested = false;
            for (int at = opening + 2; at < generatedText.length() - 1; at++) {
                if (generatedText.startsWith("[[", at)) { depth++; nested = true; at++; }
                else if (generatedText.startsWith("]]", at)) {
                    if (--depth == 0) { closing = at; break; }
                    at++;
                }
            }
            if (closing < 0) { clean.append(generatedText, opening, generatedText.length()); break; }
            String phrase = !nested && generatedText.startsWith(PREFIX, opening)
                    ? generatedText.substring(opening + PREFIX.length(), closing).strip() : "";
            if (!phrase.isEmpty()) {
                int start = clean.length();
                clean.append(phrase);
                marks.add(new Mark(phrase, start, clean.length()));
            } else clean.append(generatedText, opening, closing + 2);
            cursor = closing + 2;
        }
        return new Parsed(clean.toString(), marks);
    }
}
