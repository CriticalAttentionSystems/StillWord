/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Position;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author veronique
 */

/** Timed caption playback and review links. All UI calls belong on the Swing event thread. */
public final class VideoLinks {
    @FunctionalInterface interface UriOpener { void open(URI uri) throws Exception; }
    @FunctionalInterface interface LocationChooser { Playback choose(List<Playback> choices); }
    record Playback(double seconds, String excerpt, int sourceOffset) {
        @Override public String toString() { return timestamp(seconds) + " — " + excerpt; }
    }
    /** A verified cue link for the current editing session, never an AI-estimated timestamp. */
    public record PlaybackLink(URI uri, double startSeconds, double endSeconds, String excerpt) {
        public String timestamp() { return VideoLinks.timestamp(startSeconds); }
    }

    private static final DefaultHighlighter.DefaultHighlightPainter GOLD =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(247, 222, 142));
    private final JTextArea transcript;
    private final JTextArea summary;
    private final Consumer<String> showError;
    private final UriOpener opener;
    private final LocationChooser chooser;
    private final List<TimedSpan> cues = new ArrayList<>();
    private final List<ReviewSpan> marks = new ArrayList<>();
    private final String originalSummaryTooltip;
    private final String originalTranscriptTooltip;
    private final DocumentListener transcriptChanges;
    private final DocumentListener summaryChanges;
    private Document transcriptDocument;
    private Document summaryDocument;
    private String sourceUrl;
    private int previousTranscriptLength;
    private int previousSummaryLength;
    private int popupOffset = -1;
    private PlaybackLink visibleLink;
    private Consumer<PlaybackLink> playbackLinkListener = ignored -> { };

    public VideoLinks(JTextArea transcript, JTextArea summary, Consumer<String> showError) {
        this(transcript, summary, showError, uri -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new IllegalStateException("No system browser is available.");
            Desktop.getDesktop().browse(uri);
        }, null);
    }

    VideoLinks(JTextArea transcript, JTextArea summary, Consumer<String> showError,
               UriOpener opener, LocationChooser chooser) {
        this.transcript = Objects.requireNonNull(transcript);
        this.summary = Objects.requireNonNull(summary);
        this.showError = showError == null ? ignored -> { } : showError;
        this.opener = Objects.requireNonNull(opener);
        this.chooser = chooser == null ? choices -> (Playback) JOptionPane.showInputDialog(summary,
                "This phrase occurs more than once. Choose the passage to hear.", "Choose video passage",
                JOptionPane.QUESTION_MESSAGE, null, choices.toArray(), choices.get(0)) : chooser;
        originalSummaryTooltip = summary.getToolTipText();
        originalTranscriptTooltip = transcript.getToolTipText();
        transcriptChanges = changes(true);
        summaryChanges = changes(false);
        attachDocuments();
        transcript.addPropertyChangeListener("document", event -> {
            transcriptDocument.removeDocumentListener(transcriptChanges);
            invalidateSource();
            transcriptDocument = transcript.getDocument();
            previousTranscriptLength = transcriptDocument.getLength();
            transcriptDocument.addDocumentListener(transcriptChanges);
        });
        summary.addPropertyChangeListener("document", event -> {
            summaryDocument.removeDocumentListener(summaryChanges);
            clearSummaryMarks();
            summaryDocument = summary.getDocument();
            previousSummaryLength = summaryDocument.getLength();
            summaryDocument.addDocumentListener(summaryChanges);
        });
        installMouseActions();
        installPopupAction();
        transcript.addCaretListener(event -> refreshSelectionLink());
    }

    /** Call after replacing transcript text. The loaded video's URL is independent of any URL field. */
    public void setSource(String videoUrl, List<VttParser.Cue> timings) {
        invalidateSource();
        if (videoUrl == null || videoUrl.isBlank()) return;
        try {
            String canonical = TranscriptService.validateUrl(videoUrl);
            if (timings != null) for (VttParser.Cue cue : timings) {
                if (cue == null || !Double.isFinite(cue.startSeconds()) || !Double.isFinite(cue.endSeconds())
                        || cue.startSeconds() < 0 || cue.endSeconds() < cue.startSeconds()
                        || cue.startOffset() < 0 || cue.endOffset() <= cue.startOffset()
                        || cue.endOffset() > transcriptDocument.getLength()) continue;
                cues.add(new TimedSpan(transcriptDocument, cue));
            }
            cues.sort(Comparator.comparingInt(cue -> cue.start.getOffset()));
            sourceUrl = canonical;
            previousTranscriptLength = transcriptDocument.getLength();
            transcript.setToolTipText(hasSource() ? "Click any word to show its video link."
                    : originalTranscriptTooltip);
            refreshSelectionLink();
        } catch (IllegalArgumentException | BadLocationException exception) {
            invalidateSource();
            showError.accept("Video links could not be attached. Retrieve the transcript again from a valid YouTube link.");
        }
    }

    /** Call after the cleaned summary text is in the editor. Unmatched phrases are never guessed. */
    public void setSummaryMarks(List<SummaryReview.Mark> reviewMarks) {
        clearSummaryMarks();
        if (!hasSource() || reviewMarks == null) return;
        for (SummaryReview.Mark mark : reviewMarks) {
            if (mark == null || mark.phrase() == null || mark.phrase().isBlank() || mark.startOffset() < 0
                    || mark.endOffset() <= mark.startOffset() || mark.endOffset() > summaryDocument.getLength()) continue;
            try {
                if (!normalized(summaryDocument.getText(mark.startOffset(), mark.endOffset() - mark.startOffset()))
                        .equals(normalized(mark.phrase()))) continue;
                ReviewSpan span = new ReviewSpan(summaryDocument, mark);
                if (!matches(span.phrase).isEmpty()) marks.add(span);
            } catch (BadLocationException ignored) { /* Invalid parser offsets cannot create a playback link. */ }
        }
        refreshHighlights();
    }

    public void clearSummaryMarks() {
        for (ReviewSpan span : marks) removeHighlight(span);
        marks.clear();
        resetSummaryPointer();
    }

    public boolean hasSource() {
        return sourceUrl != null && cues.stream().anyMatch(Span::valid);
    }

    public int linkedAmbiguityCount() { return marks.size(); }

    /** Updates whenever an ordinary caret/selection change chooses a timed passage. Null means untimed text. */
    public void setPlaybackLinkListener(Consumer<PlaybackLink> listener) {
        playbackLinkListener = listener == null ? ignored -> { } : listener;
        playbackLinkListener.accept(visibleLink);
    }

    public PlaybackLink currentPlaybackLink() { return visibleLink; }

    public PlaybackLink transcriptSelectionLink() {
        if (!hasSource() || transcript.getDocument() != transcriptDocument) return null;
        int start = transcript.getSelectionStart(), end = transcript.getSelectionEnd();
        if (end > start) {
            for (TimedSpan cue : cues) if (cue.valid() && cue.start.getOffset() < end && cue.end.getOffset() > start)
                return link(cue);
        }
        return link(cueAt(start));
    }

    /** Opens only the link currently displayed to the user. */
    public void playVisibleLink() {
        if (visibleLink != null && hasSource()) open(visibleLink.uri());
    }

    public void playTranscriptSelection() {
        PlaybackLink selected = transcriptSelectionLink();
        if (selected == null) { playTranscriptAt(transcript.getSelectionStart()); return; }
        showLink(selected);
        open(selected.uri());
    }

    void playTranscriptAt(int offset) {
        if (!hasSource()) {
            showError.accept("Retrieve a video transcript first to attach playback times. Pasted text has no video timing.");
            return;
        }
        TimedSpan cue = cueAt(offset);
        if (cue == null) showError.accept("Select a word from the retrieved captions, then choose Play video here.");
        else {
            PlaybackLink selected = link(cue);
            showLink(selected);
            open(selected.uri());
        }
    }

    void playSummaryAt(int offset) {
        ReviewSpan span = markAt(offset);
        if (span == null) return;
        List<Playback> choices = matches(span.phrase);
        if (choices.isEmpty()) {
            refreshHighlights();
            showError.accept("This phrase no longer matches a timed caption. Use the transcript to select the passage to hear.");
            return;
        }
        Playback selected = choices.size() == 1 ? choices.get(0) : chooser.choose(choices);
        if (selected != null && choices.contains(selected)) {
            PlaybackLink playback = link(cueAt(selected.sourceOffset()));
            if (playback != null) {
                showLink(playback);
                open(playback.uri());
            }
        }
    }

    private void open(URI uri) {
        if (!hasSource()) return;
        try { opener.open(uri); }
        catch (Exception exception) {
            showError.accept("Could not open your browser. Open this video address manually: " + uri);
        }
    }

    private PlaybackLink link(TimedSpan cue) {
        if (!hasSource() || cue == null || !cue.valid()) return null;
        return new PlaybackLink(URI.create(sourceUrl + "&t=" + (long) Math.floor(cue.seconds) + "s"),
                cue.seconds, cue.endSeconds, excerpt(cue.start.getOffset(), cue.end.getOffset()));
    }

    private void refreshSelectionLink() { showLink(transcriptSelectionLink()); }

    private void showLink(PlaybackLink link) {
        if (Objects.equals(visibleLink, link)) return;
        visibleLink = link;
        playbackLinkListener.accept(link);
    }

    private void attachDocuments() {
        transcriptDocument = transcript.getDocument();
        summaryDocument = summary.getDocument();
        previousTranscriptLength = transcriptDocument.getLength();
        previousSummaryLength = summaryDocument.getLength();
        transcriptDocument.addDocumentListener(transcriptChanges);
        summaryDocument.addDocumentListener(summaryChanges);
    }

    private DocumentListener changes(boolean source) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { update(event, true); }
            @Override public void removeUpdate(DocumentEvent event) { update(event, false); }
            @Override public void changedUpdate(DocumentEvent event) { update(event, false); }
            private void update(DocumentEvent event, boolean insertion) {
                int previous = source ? previousTranscriptLength : previousSummaryLength;
                boolean wholesale = event.getType() == DocumentEvent.EventType.REMOVE && event.getOffset() == 0
                        && event.getLength() == previous && previous > 0;
                if (wholesale) {
                    if (source) invalidateSource(); else clearSummaryMarks();
                } else {
                    List<? extends Span> spans = source ? cues : marks;
                    for (Span span : spans) span.edited(event, insertion);
                    if (source) cues.removeIf(cue -> !cue.valid());
                    refreshHighlights();
                }
                if (source) previousTranscriptLength = transcriptDocument.getLength();
                else previousSummaryLength = summaryDocument.getLength();
                if (source) refreshSelectionLink();
            }
        };
    }

    private void invalidateSource() {
        sourceUrl = null;
        cues.clear();
        showLink(null);
        clearSummaryMarks();
        transcript.setToolTipText(originalTranscriptTooltip);
    }

    private void refreshHighlights() {
        for (var iterator = marks.iterator(); iterator.hasNext();) {
            ReviewSpan span = iterator.next();
            removeHighlight(span);
            try {
                if (!span.valid() || !normalized(summaryDocument.getText(span.start.getOffset(),
                        span.end.getOffset() - span.start.getOffset())).equals(normalized(span.phrase))
                        || matches(span.phrase).isEmpty()) {
                    iterator.remove();
                } else span.highlight = summary.getHighlighter().addHighlight(span.start.getOffset(), span.end.getOffset(), GOLD);
            } catch (BadLocationException exception) { iterator.remove(); }
        }
        resetSummaryPointer();
    }

    private void removeHighlight(ReviewSpan span) {
        if (span.highlight != null) summary.getHighlighter().removeHighlight(span.highlight);
        span.highlight = null;
    }

    private List<Playback> matches(String phrase) {
        if (!hasSource()) return List.of();
        String[] words = phrase.strip().split("[\\p{Z}\\s]+");
        if (words.length == 0) return List.of();
        String pattern = String.join("[\\p{Z}\\s]+", java.util.Arrays.stream(words).map(Pattern::quote).toList());
        if (Character.isLetterOrDigit(phrase.strip().codePointAt(0))) pattern = "(?<![\\p{L}\\p{N}])" + pattern;
        if (Character.isLetterOrDigit(phrase.strip().codePointBefore(phrase.strip().length()))) pattern += "(?![\\p{L}\\p{N}])";
        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(transcript.getText());
        Map<TimedSpan, Playback> found = new LinkedHashMap<>();
        while (matcher.find()) {
            TimedSpan cue = cueAt(matcher.start());
            if (cue == null || !covers(matcher.start(), matcher.end())) continue;
            found.putIfAbsent(cue, new Playback(cue.seconds, excerpt(matcher.start(), matcher.end()), matcher.start()));
        }
        return List.copyOf(found.values());
    }

    /** A phrase may span adjacent cues and whitespace, but never untimed inserted text. */
    private boolean covers(int start, int end) {
        int through = start;
        String text = transcript.getText();
        for (TimedSpan cue : cues) {
            if (!cue.valid() || cue.end.getOffset() <= through) continue;
            if (cue.start.getOffset() > through && !text.substring(through, Math.min(end, cue.start.getOffset())).isBlank()) return false;
            if (cue.start.getOffset() >= end) return through >= end;
            through = Math.max(through, cue.end.getOffset());
            if (through >= end) return true;
        }
        return through >= end;
    }

    private String excerpt(int start, int end) {
        String text = transcript.getText();
        int from = Math.max(0, start - 35), to = Math.min(text.length(), end + 65);
        String value = text.substring(from, to).replaceAll("[\\p{Z}\\s]+", " ").strip();
        return (from > 0 ? "…" : "") + value + (to < text.length() ? "…" : "");
    }

    private TimedSpan cueAt(int offset) {
        if (offset == transcriptDocument.getLength() && offset > 0) offset--;
        for (TimedSpan cue : cues)
            if (cue.valid() && offset >= cue.start.getOffset() && offset < cue.end.getOffset()) return cue;
        return null;
    }

    private ReviewSpan markAt(int offset) {
        for (ReviewSpan span : marks)
            if (span.valid() && offset >= span.start.getOffset() && offset < span.end.getOffset()) return span;
        return null;
    }

    private void installMouseActions() {
        transcript.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { if (event.isPopupTrigger() || SwingUtilities.isRightMouseButton(event)) popupOffset = transcript.viewToModel2D(event.getPoint()); }
            @Override public void mouseReleased(MouseEvent event) { if (event.isPopupTrigger()) popupOffset = transcript.viewToModel2D(event.getPoint()); }
            @Override public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && (event.isMetaDown() || event.isControlDown()))
                    playTranscriptAt(transcript.viewToModel2D(event.getPoint()));
            }
        });
        MouseAdapter reviewMouse = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1)
                    playSummaryAt(summary.viewToModel2D(event.getPoint()));
            }
            @Override public void mouseMoved(MouseEvent event) {
                ReviewSpan span = markAt(summary.viewToModel2D(event.getPoint()));
                if (span == null) { resetSummaryPointer(); return; }
                List<Playback> matches = matches(span.phrase);
                if (matches.isEmpty()) { resetSummaryPointer(); return; }
                summary.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                summary.setToolTipText(matches.size() == 1 ? "Hear the original caption at " + timestamp(matches.get(0).seconds())
                        : "Matches at " + String.join(", ", matches.stream().limit(4)
                                .map(match -> timestamp(match.seconds())).toList())
                                + (matches.size() > 4 ? ", …" : "") + " — click to choose a passage.");
            }
            @Override public void mouseExited(MouseEvent event) { resetSummaryPointer(); }
        };
        summary.addMouseListener(reviewMouse);
        summary.addMouseMotionListener(reviewMouse);
    }

    private void installPopupAction() {
        JPopupMenu popup = transcript.getComponentPopupMenu();
        if (popup == null) { popup = new JPopupMenu(); transcript.setComponentPopupMenu(popup); }
        if (popup.getComponentCount() > 0) popup.addSeparator();
        JMenuItem play = new JMenuItem("Play video here");
        play.addActionListener(event -> {
            int offset = popupOffset;
            popupOffset = -1;
            if (offset < 0 || offset >= transcript.getSelectionStart() && offset < transcript.getSelectionEnd()) playTranscriptSelection();
            else playTranscriptAt(offset);
        });
        popup.add(play);
    }

    private void resetSummaryPointer() {
        summary.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        summary.setToolTipText(originalSummaryTooltip);
    }

    private static String normalized(String text) { return text.replaceAll("[\\p{Z}\\s]+", " ").strip().toLowerCase(java.util.Locale.ROOT); }
    private static String timestamp(double seconds) {
        long time = (long) Math.floor(seconds);
        return time >= 3600 ? String.format("%d:%02d:%02d", time / 3600, time / 60 % 60, time % 60)
                : String.format("%d:%02d", time / 60, time % 60);
    }

    private static class Span {
        Position start, end;
        int previousStart, previousEnd;
        Span(Document document, int start, int end) throws BadLocationException {
            this.start = document.createPosition(start);
            this.end = document.createPosition(end);
            previousStart = start; previousEnd = end;
        }
        boolean valid() { return end.getOffset() > start.getOffset(); }
        void edited(DocumentEvent event, boolean insertion) {
            try {
                // Swing's zero offset is backward-biased; keep leading inserted text outside the old cue.
                if (insertion && event.getOffset() == previousStart && previousStart == 0)
                    start = event.getDocument().createPosition(event.getLength());
                // An insertion just after a linked span is not part of that span.
                if (insertion && event.getOffset() == previousEnd)
                    end = event.getDocument().createPosition(previousEnd);
            } catch (BadLocationException ignored) { }
            previousStart = start.getOffset(); previousEnd = end.getOffset();
        }
    }
    private static final class TimedSpan extends Span {
        final double seconds, endSeconds;
        TimedSpan(Document document, VttParser.Cue cue) throws BadLocationException {
            super(document, cue.startOffset(), cue.endOffset()); seconds = cue.startSeconds(); endSeconds = cue.endSeconds();
        }
    }
    private static final class ReviewSpan extends Span {
        final String phrase;
        Object highlight;
        ReviewSpan(Document document, SummaryReview.Mark mark) throws BadLocationException {
            super(document, mark.startOffset(), mark.endOffset()); phrase = mark.phrase();
        }
    }
}
