/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import javax.swing.*;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.PlainDocument;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.net.URI;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 *
 * @author veronique
 */

/** Offline Swing checks: playback is injected and never starts a browser or downloads media. */
public final class VideoLinksTest {
    private static int checks;
    private static final String URL = "https://www.youtube.com/watch?v=5VDejFcm5_o";
    private static final String TEXT = "calm breath\n\nodd phrase\n\ncalm breath";

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { run(); visibleLinksFollowEditing(); visibleLinkInterface(); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        });
        System.out.println("VideoLinksTest: " + checks + " checks passed");
    }

    private static void run() throws Exception {
        Harness test = new Harness();
        equal(false, test.links.hasSource());
        test.links.playTranscriptSelection();
        equal(1, test.errors.size());
        equal(0, test.opened.size());
        equal("Copy", ((JMenuItem) test.transcript.getComponentPopupMenu().getComponent(0)).getText());
        equal("Play video here", ((JMenuItem) test.transcript.getComponentPopupMenu().getComponent(2)).getText());

        test.load();
        equal(true, test.links.hasSource());
        test.links.playTranscriptAt(15);
        equal(URI.create(URL + "&t=50s"), test.lastOpened());
        test.transcript.select(10, 17);
        test.links.playTranscriptSelection();
        equal(URI.create(URL + "&t=4s"), test.lastOpened());
        test.transcript.select(11, 16); // Starts in a paragraph separator, then enters the next cue.
        test.links.playTranscriptSelection();
        equal(URI.create(URL + "&t=50s"), test.lastOpened());
        test.links.playTranscriptAt(TEXT.length());
        equal(URI.create(URL + "&t=130s"), test.lastOpened());
        int opened = test.opened.size();
        test.links.playTranscriptAt(12);
        equal(opened, test.opened.size());
        equal(true, test.errors.get(test.errors.size() - 1).contains("Select a word"));

        test.mark("odd phrase");
        equal(1, test.links.linkedAmbiguityCount());
        equal(1, test.summary.getHighlighter().getHighlights().length);
        test.links.playSummaryAt(7);
        equal(URI.create(URL + "&t=50s"), test.lastOpened());
        equal(test.lastOpened(), test.links.currentPlaybackLink().uri());
        equal(0, test.choices.size());
        equal(true, test.summary.isEditable());
        equal(true, test.transcript.isEditable());
        test.summary.setSize(600, 100);
        MouseEvent move = event(test.summary, 8, MouseEvent.MOUSE_MOVED, 0);
        for (var listener : test.summary.getMouseMotionListeners()) listener.mouseMoved(move);
        equal(Cursor.HAND_CURSOR, test.summary.getCursor().getType());
        equal(true, test.summary.getToolTipText().contains("0:50"));
        opened = test.opened.size();
        for (var listener : test.summary.getMouseListeners()) listener.mouseClicked(event(test.summary, 8, MouseEvent.MOUSE_CLICKED, 0));
        equal(opened + 1, test.opened.size());
        test.transcript.setSize(600, 200);
        opened = test.opened.size();
        for (var listener : test.transcript.getMouseListeners()) listener.mouseClicked(event(test.transcript, 15, MouseEvent.MOUSE_CLICKED, InputEvent.META_DOWN_MASK));
        equal(opened + 1, test.opened.size());
        equal(URI.create(URL + "&t=50s"), test.lastOpened());

        test.mark("CALM   BREATH");
        equal(1, test.links.linkedAmbiguityCount());
        test.links.playSummaryAt(8);
        equal(2, test.choices.size());
        equal(4.8, test.choices.get(0).seconds());
        equal(130.9, test.choices.get(1).seconds());
        equal(URI.create(URL + "&t=130s"), test.lastOpened());
        equal(test.lastOpened(), test.links.currentPlaybackLink().uri());
        equal(true, test.choices.get(1).toString().startsWith("2:10 — "));
        equal(true, test.choices.get(1).excerpt().contains("calm breath"));
        for (var listener : test.summary.getMouseMotionListeners()) listener.mouseMoved(event(test.summary, 8, MouseEvent.MOUSE_MOVED, 0));
        equal(true, test.summary.getToolTipText().contains("0:04"));
        equal(true, test.summary.getToolTipText().contains("2:10"));
        test.cancelChoice = true;
        opened = test.opened.size();
        test.links.playSummaryAt(8);
        equal(opened, test.opened.size());
        test.cancelChoice = false;

        test.mark("breath odd"); // A single source occurrence spans two adjacent caption cues.
        equal(1, test.links.linkedAmbiguityCount());
        test.links.playSummaryAt(8);
        equal(URI.create(URL + "&t=4s"), test.lastOpened());
        test.mark("arm breath"); // A substring within a word must not be treated as the quoted phrase.
        equal(0, test.links.linkedAmbiguityCount());
        test.mark("invented phrase");
        equal(0, test.links.linkedAmbiguityCount());

        test.mark("odd phrase");
        test.transcript.insert("An untimed introduction. ", 0);
        test.links.playTranscriptAt(2);
        equal(true, test.errors.get(test.errors.size() - 1).contains("Select a word"));
        test.links.playTranscriptAt(test.transcript.getText().indexOf("odd"));
        equal(URI.create(URL + "&t=50s"), test.lastOpened());
        equal(1, test.links.linkedAmbiguityCount());
        test.summary.insert("My notes: ", 0);
        equal(1, test.links.linkedAmbiguityCount());
        test.links.playSummaryAt(test.summary.getText().indexOf("odd") + 1);
        equal(URI.create(URL + "&t=50s"), test.lastOpened());
        equal(test.summary.getText().indexOf("odd"), test.summary.getHighlighter().getHighlights()[0].getStartOffset());
        int markEnd = test.summary.getText().indexOf("odd") + "odd phrase".length();
        test.summary.insert(" (my annotation)", markEnd);
        equal(1, test.links.linkedAmbiguityCount());
        equal(markEnd, test.summary.getHighlighter().getHighlights()[0].getEndOffset());
        test.summary.insert("changed ", test.summary.getText().indexOf("phrase"));
        equal(0, test.links.linkedAmbiguityCount());
        equal(0, test.summary.getHighlighter().getHighlights().length);

        test.load();
        test.mark("odd phrase");
        test.transcript.replaceRange("changed", 13, 16);
        equal(0, test.links.linkedAmbiguityCount());
        test.load();
        test.mark("odd phrase");
        test.transcript.replaceRange("", 13, 23);
        equal(0, test.links.linkedAmbiguityCount());
        test.links.playTranscriptAt(test.transcript.getText().lastIndexOf("calm"));
        equal(URI.create(URL + "&t=130s"), test.lastOpened());

        test.load();
        test.summary.setText("odd phrase");
        test.links.setSummaryMarks(List.of(new SummaryReview.Mark("odd phrase", 0, 10)));
        test.summary.insert("Prefix ", 0);
        equal(1, test.links.linkedAmbiguityCount());
        equal(7, test.summary.getHighlighter().getHighlights()[0].getStartOffset());
        test.links.playSummaryAt(8);
        equal(URI.create(URL + "&t=50s"), test.lastOpened());
        test.summary.setText("odd phrase"); // Replacement loses provenance even when the words look the same.
        equal(0, test.links.linkedAmbiguityCount());

        test.mark("odd phrase");
        Object otherHighlight = test.summary.getHighlighter().addHighlight(0, 2,
                new DefaultHighlighter.DefaultHighlightPainter(Color.CYAN));
        test.links.clearSummaryMarks();
        equal(0, test.links.linkedAmbiguityCount());
        equal(1, test.summary.getHighlighter().getHighlights().length);
        test.summary.getHighlighter().removeHighlight(otherHighlight);
        test.mark("odd phrase");
        test.links.setSource("https://youtu.be/WPm4c0VDmW4?t=90", test.cues());
        equal(0, test.links.linkedAmbiguityCount());
        test.links.playTranscriptAt(15);
        equal(URI.create("https://www.youtube.com/watch?v=WPm4c0VDmW4&t=50s"), test.lastOpened());
        test.mark("odd phrase");
        test.transcript.setText(TEXT);
        equal(false, test.links.hasSource());
        equal(0, test.links.linkedAmbiguityCount());

        test.load();
        test.mark("odd phrase");
        test.transcript.setDocument(new PlainDocument());
        equal(false, test.links.hasSource());
        equal(0, test.links.linkedAmbiguityCount());
        test.load();
        test.mark("odd phrase");
        test.summary.setDocument(new PlainDocument());
        equal(0, test.links.linkedAmbiguityCount());
        test.links.setSource("https://example.com/watch?v=5VDejFcm5_o", test.cues());
        equal(false, test.links.hasSource());
        test.links.setSource(URL, List.of(new VttParser.Cue(Double.NaN, 20, 0, 5),
                new VttParser.Cue(0, 4, -1, 5), new VttParser.Cue(4, 2, 0, 5),
                new VttParser.Cue(0, 4, 0, 50)));
        equal(false, test.links.hasSource());

        test.load();
        test.mark("odd phrase");
        test.failOpen = true;
        test.links.playSummaryAt(8);
        equal(true, test.errors.get(test.errors.size() - 1).contains(URL + "&t=50s"));
        equal(true, test.errors.get(test.errors.size() - 1).contains("manually"));
        test.links.setSummaryMarks(List.of(new SummaryReview.Mark("wrong", 6, 16),
                new SummaryReview.Mark("odd phrase", -1, 10), new SummaryReview.Mark("odd phrase", 100, 110)));
        equal(0, test.links.linkedAmbiguityCount());
    }

    private static void visibleLinksFollowEditing() throws Exception {
        Harness test = new Harness();
        List<VideoLinks.PlaybackLink> changes = new ArrayList<>();
        test.links.setPlaybackLinkListener(changes::add);
        equal(1, changes.size());
        equal(null, changes.get(0));
        equal(null, test.links.transcriptSelectionLink());
        test.load();
        test.transcript.setCaretPosition(15); // An ordinary editing click/caret move; no modifier is needed.
        VideoLinks.PlaybackLink link = test.links.currentPlaybackLink();
        equal(URI.create(URL + "&t=50s"), link.uri());
        equal(50.4, link.startSeconds());
        equal(58.0, link.endSeconds());
        equal("0:50", link.timestamp());
        equal(true, link.excerpt().contains("odd phrase"));
        equal(link, changes.get(changes.size() - 1));
        equal(link, test.links.transcriptSelectionLink());
        equal(0, test.opened.size()); // Clicking to edit only reveals the link.
        test.links.playVisibleLink();
        equal(link.uri(), test.lastOpened());

        test.transcript.select(11, 16); // A selection beginning in a separator finds its first timed word.
        equal(URI.create(URL + "&t=50s"), test.links.currentPlaybackLink().uri());
        test.transcript.setCaretPosition(12);
        equal(null, test.links.currentPlaybackLink());
        int opened = test.opened.size();
        test.links.playVisibleLink();
        equal(opened, test.opened.size());

        test.transcript.setCaretPosition(15);
        test.transcript.insert("Untimed new introduction. ", 0);
        equal(URI.create(URL + "&t=50s"), test.links.currentPlaybackLink().uri());
        test.transcript.setCaretPosition(2);
        equal(null, test.links.currentPlaybackLink());
        int phrase = test.transcript.getText().indexOf("odd phrase");
        test.transcript.setCaretPosition(phrase + 5);
        test.transcript.insert("clear ", phrase + 4);
        equal(URI.create(URL + "&t=50s"), test.links.currentPlaybackLink().uri());
        equal(true, test.links.currentPlaybackLink().excerpt().contains("odd clear phrase"));
        test.transcript.setText(TEXT); // Replacing the whole transcript invalidates timing, even for identical text.
        equal(null, test.links.currentPlaybackLink());
        equal(null, changes.get(changes.size() - 1));
        test.links.playVisibleLink();
        equal(opened, test.opened.size());

        test.links.setSource(URL, List.of(new VttParser.Cue(75, 75, 13, 23)));
        test.transcript.setCaretPosition(15);
        equal(true, test.links.hasSource());
        equal(URI.create(URL + "&t=75s"), test.links.currentPlaybackLink().uri());
        equal(75.0, test.links.currentPlaybackLink().endSeconds());
        test.mark("odd phrase");
        equal(1, test.links.linkedAmbiguityCount());
        test.links.playSummaryAt(8);
        equal(URI.create(URL + "&t=75s"), test.lastOpened());
        test.transcript.setDocument(new PlainDocument());
        equal(null, test.links.currentPlaybackLink());
        equal(false, test.links.hasSource());
    }

    private static void visibleLinkInterface() throws Exception {
        // Read existing preferences through construction, but never select/save a settings choice.
        StillWord app = new StillWord();
        JTextArea text = field(app, "transcript", JTextArea.class);
        VideoLinks links = field(app, "videoLinks", VideoLinks.class);
        JButton play = field(app, "playbackLink", JButton.class);
        JButton copy = field(app, "copyPlaybackLink", JButton.class);
        JLabel excerpt = field(app, "playbackExcerpt", JLabel.class);
        JPanel root = field(app, "root", JPanel.class);
        equal(false, play.isEnabled());
        equal(false, copy.isEnabled());
        text.setText(TEXT);
        links.setSource(URL, new Harness().cues());
        text.setCaretPosition(15);
        equal(true, play.isEnabled());
        equal(true, copy.isEnabled());
        equal(true, play.getText().contains("Play video at 0:50"));
        equal(URL + "&t=50s", play.getToolTipText());
        equal(URL + "&t=50s", copy.getToolTipText());
        equal("Play video at 0:50", play.getAccessibleContext().getAccessibleName());
        equal(true, excerpt.getText().contains("odd phrase"));
        field(app, "url", JTextField.class).setText("https://youtu.be/WPm4c0VDmW4");
        equal(URL + "&t=50s", play.getToolTipText()); // The loaded video's URL owns the link.
        for (int[] size : new int[][]{{1030, 860}, {1280, 960}}) {
            root.setSize(size[0], size[1]); layout(root);
            equal(true, play.getWidth() > 150 && play.getHeight() > 0);
            equal(true, play.getX() + play.getWidth() <= copy.getX());
            equal(true, text.getHeight() >= 100);
        }
        text.setText("Pasted plain text with no timing metadata.");
        equal(false, play.isEnabled());
        equal(false, copy.isEnabled());
        equal(null, play.getToolTipText());
    }

    private static <T> T field(Object object, String name, Class<T> type) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static final class Harness {
        final JTextArea transcript = new JTextArea();
        final JTextArea summary = new JTextArea();
        final List<URI> opened = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        List<VideoLinks.Playback> choices = List.of();
        boolean cancelChoice, failOpen;
        final VideoLinks links;
        Harness() {
            JPopupMenu popup = new JPopupMenu(); popup.add(new JMenuItem("Copy"));
            transcript.setComponentPopupMenu(popup);
            links = new VideoLinks(transcript, summary, errors::add, uri -> {
                if (failOpen) throw new IllegalStateException("Synthetic failure");
                opened.add(uri);
            }, options -> { choices = options; return cancelChoice ? null : options.get(options.size() - 1); });
        }
        List<VttParser.Cue> cues() {
            return List.of(new VttParser.Cue(4.8, 8, 0, 11), new VttParser.Cue(50.4, 58, 13, 23),
                    new VttParser.Cue(130.9, 138, 25, 36));
        }
        void load() { transcript.setText(TEXT); links.setSource(URL, cues()); }
        void mark(String phrase) {
            summary.setText("Check " + phrase + " carefully.");
            links.setSummaryMarks(List.of(new SummaryReview.Mark(phrase, 6, 6 + phrase.length())));
        }
        URI lastOpened() { return opened.get(opened.size() - 1); }
    }

    private static MouseEvent event(JTextArea area, int offset, int eventType, int modifiers) throws Exception {
        Rectangle2D rectangle = area.modelToView2D(offset);
        return new MouseEvent(area, eventType, System.currentTimeMillis(), modifiers,
                (int) rectangle.getX() + 1, (int) (rectangle.getY() + rectangle.getHeight() / 2),
                1, false, eventType == MouseEvent.MOUSE_CLICKED ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }
}
