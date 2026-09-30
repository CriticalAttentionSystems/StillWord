/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author veronique
 */

/** Turns WebVTT cues into readable prose without globally deleting repeated sentences. */
public final class VttParser {
    /** Timing for newly emitted caption text; offsets are UTF-16, with an exclusive end. */
    public record Cue(double startSeconds, double endSeconds, int startOffset, int endOffset) { }

    public record Parsed(String text, List<Cue> cues) {
        public Parsed { cues = List.copyOf(cues); }
    }

    private static final Pattern TIMING = Pattern.compile(
            "^((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})\\s+-->\\s+((?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3})(.*)$");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#X[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern INLINE_TIME = Pattern.compile("<(?:\\d+:)?\\d{2}:\\d{2}[.,]\\d{3}>");
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("lrm", ""), Map.entry("rlm", ""), Map.entry("ndash", "–"),
            Map.entry("mdash", "—"), Map.entry("hellip", "…"), Map.entry("lsquo", "‘"),
            Map.entry("rsquo", "’"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"));

    private VttParser() { }

    public static String parse(String source) {
        return parse(source, null, null);
    }

    /** Selects cues overlapping [startSeconds, endSeconds); words are kept at cue granularity. */
    public static String parse(String source, Double startSeconds, Double endSeconds) {
        return parseWithCues(source, startSeconds, endSeconds).text();
    }

    public static Parsed parseWithCues(String source) {
        return parseWithCues(source, null, null);
    }

    /** Keeps source cue times for each newly emitted span after cleaning and rolling-caption deduplication. */
    public static Parsed parseWithCues(String source, Double startSeconds, Double endSeconds) {
        validateRange(startSeconds, endSeconds);
        if (source == null || source.isBlank()) return new Parsed("", List.of());
        String normalized = source.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        List<RawCue> cues = new ArrayList<>();
        String[] lines = normalized.split("\n", -1);
        for (int index = 0; index < lines.length; ) {
            String line = lines[index].strip();
            if (metadataBlock(line)) {
                do { index++; } while (index < lines.length && !lines[index].isBlank());
                continue;
            }
            Matcher timing = TIMING.matcher(line);
            if (!timing.matches()) { index++; continue; }
            StringBuilder body = new StringBuilder();
            int next = index + 1;
            for (; next < lines.length && !lines[next].isEmpty(); next++) {
                if (TIMING.matcher(lines[next].strip()).matches()) break;
                // YouTube uses a space-only display line before newly spoken text. It is
                // not an empty cue separator unless the following lines start a new block.
                if (lines[next].isBlank()) {
                    if (startsBlock(lines, next + 1)) break;
                    continue;
                }
                if (body.length() > 0) body.append(' ');
                body.append(lines[next].strip());
            }
            boolean rolling = INLINE_TIME.matcher(body).find()
                    || timing.group(3).contains("align:start") && timing.group(3).contains("position:0%");
            String text = clean(body.toString());
            long start = millis(timing.group(1));
            long end = millis(timing.group(2));
            boolean selected = (startSeconds == null || end > startSeconds * 1_000)
                    && (endSeconds == null || start < endSeconds * 1_000);
            if (selected) cues.add(new RawCue(start, end, text, rolling));
            index = next;
        }

        StringBuilder result = new StringBuilder();
        List<Cue> emitted = new ArrayList<>();
        RawCue previous = null;
        boolean displayCleared = false;
        int paragraphLength = 0;
        for (RawCue cue : cues) {
            if (cue.text().isBlank()) {
                displayCleared = true;
                continue;
            }
            String addition = cue.text();
            if (previous != null) {
                long gap = cue.start() - previous.end();
                boolean overlapping = cue.start() < previous.end() && cue.end() > previous.start();
                boolean rolling = (cue.rolling() || previous.rolling()) && gap >= -30_000 && gap <= 200;
                if (!displayCleared && (overlapping || rolling)) addition = removeOverlap(previous.text(), addition);
                if (!addition.isBlank() && result.length() > 0) {
                    boolean paragraph = gap >= 2_000
                            || paragraphLength >= 750 && endsSentence(result);
                    result.append(paragraph ? "\n\n" : " ");
                    if (paragraph) paragraphLength = 0;
                }
            }
            int offset = result.length();
            result.append(addition);
            if (!addition.isBlank()) emitted.add(new Cue(cue.start() / 1_000.0, cue.end() / 1_000.0,
                    offset, result.length()));
            paragraphLength += addition.length();
            previous = cue;
            displayCleared = false;
        }
        return new Parsed(result.toString().strip(), emitted);
    }

    private static boolean metadataBlock(String line) {
        return line.equals("NOTE") || line.startsWith("NOTE ") || line.startsWith("NOTE\t")
                || line.equals("STYLE") || line.equals("REGION");
    }

    private static boolean startsBlock(String[] lines, int index) {
        while (index < lines.length && lines[index].isBlank()) index++;
        if (index == lines.length) return true;
        if (TIMING.matcher(lines[index].strip()).matches() || metadataBlock(lines[index].strip())) return true;
        // A cue may have an identifier on the line directly before its timestamp.
        return index + 1 < lines.length && TIMING.matcher(lines[index + 1].strip()).matches();
    }

    static void validateRange(Double startSeconds, Double endSeconds) {
        if (startSeconds != null && (!Double.isFinite(startSeconds) || startSeconds < 0)
                || endSeconds != null && (!Double.isFinite(endSeconds) || endSeconds < 0))
            throw new IllegalArgumentException("Start and end times must be finite, nonnegative numbers.");
        if (endSeconds != null && endSeconds <= (startSeconds == null ? 0 : startSeconds))
            throw new IllegalArgumentException("The end time must be later than the start time.");
    }

    private static String removeOverlap(String previous, String current) {
        String[] before = previous.split("\\s+");
        String[] after = current.split("\\s+");
        int maximum = Math.min(before.length, after.length);
        // Match only the tail of the immediately preceding cue, never the whole transcript.
        for (int count = maximum; count >= 1; count--) {
            if (count == 1 && before.length > 1 && after.length > 1) continue;
            boolean matches = true;
            for (int n = 0; n < count; n++) {
                if (!before[before.length - count + n].equals(after[n])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return String.join(" ", java.util.Arrays.copyOfRange(after, count, after.length));
        }
        return current;
    }

    private static boolean endsSentence(StringBuilder text) {
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '”';
    }

    private static long millis(String timestamp) {
        String[] parts = timestamp.replace(',', '.').split(":");
        long hours = parts.length == 3 ? Long.parseLong(parts[0]) : 0;
        long minutes = Long.parseLong(parts[parts.length - 2]);
        String[] seconds = parts[parts.length - 1].split("\\.");
        return hours * 3_600_000 + minutes * 60_000 + Long.parseLong(seconds[0]) * 1_000
                + Long.parseLong(seconds[1]);
    }

    private static String clean(String text) {
        String withoutTags = TAG.matcher(text.replaceAll("(?i)<br\\s*/?>", " ")).replaceAll("");
        Matcher matcher = ENTITY.matcher(withoutTags);
        StringBuilder decoded = new StringBuilder();
        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement = ENTITIES.get(entity.toLowerCase(Locale.ROOT));
            if (entity.startsWith("#")) {
                try {
                    boolean hex = entity.length() > 2 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X');
                    int point = Integer.parseInt(entity.substring(hex ? 2 : 1), hex ? 16 : 10);
                    if (Character.isValidCodePoint(point) && !(point >= 0xD800 && point <= 0xDFFF))
                        replacement = new String(Character.toChars(point));
                } catch (NumberFormatException ignored) { /* Retain malformed entities as text. */ }
            }
            matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement));
        }
        matcher.appendTail(decoded);
        return decoded.toString().replace('\u00a0', ' ').replaceAll("[\\p{Z}\\s]+", " ").strip();
    }

    private record RawCue(long start, long end, String text, boolean rolling) { }
}
