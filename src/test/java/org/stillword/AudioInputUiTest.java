/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import javax.swing.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author veronique
 */

/** Exercises additive audio selection and the existing YouTube caption worker without network or model calls. */
public final class AudioInputUiTest {
    private static final String YOUTUBE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path fixtures = Files.createTempDirectory("stillword-audio-ui-");
        AtomicReference<StillWord> ref = new AtomicReference<>();
        try {
            Path localAudio = fixtures.resolve("a local recording without extension");
            Files.write(localAudio, new byte[]{1, 2, 3});
            edt(() -> {
                invoke(null, "configureLook");
                StillWord app = new StillWord(); ref.set(app);
                sourceSwitching(app, localAudio);
                readingSpace(app);
            });
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                youtubeCaptions(ref.get(), fixtures);
                cancelCaptionWorker(ref.get(), fixtures);
            }
        } finally {
            try (var paths = Files.walk(fixtures)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("AudioInputUiTest: " + checks + " checks passed");
    }

    private static void sourceSwitching(StillWord app, Path audio) throws Exception {
        AppSettings settings = get(app, "settings");
        AppSettings.TranscriptMode stored = new AppSettings().transcriptMode;
        settings.transcriptMode = AppSettings.TranscriptMode.YOUTUBE_CAPTIONS;
        JTextField input = get(app, "url");
        JComboBox<?> choice = get(app, "transcriptSource");
        JTextArea transcript = get(app, "transcript"), summary = get(app, "summary");
        transcript.setText("Existing edited text."); summary.setText("Existing edited summary.");
        input.setText(YOUTUBE);
        equal(AppSettings.TranscriptMode.YOUTUBE_CAPTIONS, choice.getSelectedItem(), "YouTube retains caption preference");
        check(choice.isEnabled(), "YouTube transcript source remains selectable");
        for (String source : List.of("https://dharmaseed.org/talks/79412/", "https://example.org/talk.mp3", "https://audio.example.org/recording?id=17", "https://example.org/podcasts/episode-7", audio.toString())) {
            input.setText(source);
            equal(AppSettings.TranscriptMode.LOCAL_AUDIO, choice.getSelectedItem(), "Audio source selects effective Whisper mode");
            check(!choice.isEnabled(), "Audio source cannot incorrectly request YouTube captions");
            check(((JButton) get(app, "speechSetup")).isVisible(), "Audio source exposes speech setup");
            equal(AppSettings.TranscriptMode.YOUTUBE_CAPTIONS, settings.transcriptMode, "Audio source preserves YouTube preference in memory");
            input.setText(YOUTUBE);
            equal(AppSettings.TranscriptMode.YOUTUBE_CAPTIONS, choice.getSelectedItem(), "Returning to YouTube restores captions");
            check(choice.isEnabled(), "Returning to YouTube restores source control");
        }
        settings.transcriptMode = AppSettings.TranscriptMode.LOCAL_AUDIO;
        input.setText("https://example.org/talk.wav"); input.setText(YOUTUBE);
        equal(AppSettings.TranscriptMode.LOCAL_AUDIO, choice.getSelectedItem(), "YouTube Whisper preference also survives audio input");
        equal(stored, new AppSettings().transcriptMode, "Switching inputs does not write a different saved YouTube preference");
        equal("Existing edited text.", transcript.getText(), "Choosing another source preserves edited transcript");
        equal("Existing edited summary.", summary.getText(), "Choosing another source preserves edited summary");
        check(transcript.isEditable() && summary.isEditable(), "Source selection preserves editable panels");
        check(((JButton) get(app, "openAudio")).isEnabled(), "Audio file chooser is available while idle");
        settings.transcriptMode = AppSettings.TranscriptMode.YOUTUBE_CAPTIONS;
        invoke(app, "updateTranscriptSource");
    }

    private static void readingSpace(StillWord app) throws Exception {
        JPanel root = get(app, "root");
        JTextArea transcript = get(app, "transcript"), summary = get(app, "summary");
        Method layout = StillWord.class.getDeclaredMethod("layoutAll", java.awt.Container.class);
        layout.setAccessible(true);
        for (int[] size : new int[][]{{1030, 860, 220}, {1280, 960, 300}}) {
            root.setSize(size[0], size[1]); layout.invoke(null, root);
            check(transcript.getParent().getHeight() >= size[2], "Transcript has useful reading height at " + size[0] + " by " + size[1]);
            check(summary.getParent().getHeight() >= size[2], "Summary has useful reading height at " + size[0] + " by " + size[1]);
            java.awt.Container transcriptCard = transcript.getParent().getParent().getParent();
            java.awt.Container summaryCard = summary.getParent().getParent().getParent();
            equal(transcriptCard.getSize(), summaryCard.getSize(), "Editor panels retain equal dimensions");
            check(((JButton) get(app, "openAudio")).getHeight() > 0 && ((JButton) get(app, "saveSummary")).getHeight() > 0,
                    "Compacting banner preserves file and save controls");
        }
    }

    private static void youtubeCaptions(StillWord app, Path fixtures) throws Exception {
        Path downloader = fixtures.resolve("caption-fixture");
        Files.writeString(downloader, "#!/bin/sh\nset -eu\nprintf '%s\\n' \"$@\" > 'arguments.txt'\n"
                + "printf '%s' 'An ordinary lecture' > title.txt\ncat > captions.en.vtt <<'VTT'\nWEBVTT\n\n"
                + "00:00:04.000 --> 00:00:08.000\nOutside selected range.\n\n"
                + "00:00:22.000 --> 00:00:26.000\nCaption-only teaching with clear timing.\nVTT\n");
        check(downloader.toFile().setExecutable(true), "Caption fixture executable");
        edt(() -> {
            AppSettings settings = get(app, "settings"); settings.executable = downloader.toString();
            JTextArea transcript = get(app, "transcript"), summary = get(app, "summary");
            set(app, "savedTranscript", transcript.getText());
            ((JTextField) get(app, "url")).setText(YOUTUBE);
            ((JCheckBox) get(app, "segment")).setSelected(true);
            ((JTextField) get(app, "start")).setText("00:20");
            ((JTextField) get(app, "end")).setText("00:30");
            ((JButton) get(app, "fetch")).doClick();
            check(!((JButton) get(app, "openAudio")).isEnabled(), "Audio chooser disabled during caption retrieval");
            check(!((JTextField) get(app, "url")).isEnabled(), "Source cannot change during caption retrieval");
            check(!transcript.isEditable() && !summary.isEditable(), "Current text stays stable while worker is active");
        });
        awaitWorker(app);
        edt(() -> {
            JTextArea transcript = get(app, "transcript"), summary = get(app, "summary");
            equal("Caption-only teaching with clear timing.", transcript.getText(), "YouTube caption route and selected range still work");
            equal("Publisher captions", get(app, "transcriptProvenance"), "Caption provenance is not mislabeled audio");
            equal("Existing edited summary.", summary.getText(), "Loading a transcript retains the prior edited summary");
            VideoLinks links = get(app, "videoLinks");
            transcript.setCaretPosition(4);
            equal(URI.create(YOUTUBE + "&t=22s"), links.currentPlaybackLink().uri(), "YouTube playback links retain original source timestamp");
            check(((JButton) get(app, "saveTranscript")).isEnabled(), "Retrieved caption text can be saved");
            check(((JButton) get(app, "summarize")).isEnabled(), "Retrieved caption text can be summarized");
            check(((JButton) get(app, "openAudio")).isEnabled(), "Audio chooser re-enables after captions complete");
            check(transcript.isEditable() && summary.isEditable(), "Both panels remain editable after captions complete");
        });
    }

    private static void cancelCaptionWorker(StillWord app, Path fixtures) throws Exception {
        Path downloader = fixtures.resolve("slow-caption-fixture");
        Files.writeString(downloader, "#!/bin/sh\nexec sleep 30\n");
        check(downloader.toFile().setExecutable(true), "Slow caption fixture executable");
        AtomicReference<String> beforeText = new AtomicReference<>(), beforeSummary = new AtomicReference<>();
        edt(() -> {
            AppSettings settings = get(app, "settings"); settings.executable = downloader.toString();
            JTextArea transcript = get(app, "transcript"), summary = get(app, "summary");
            beforeText.set(transcript.getText()); beforeSummary.set(summary.getText());
            set(app, "savedTranscript", transcript.getText());
            ((JButton) get(app, "fetch")).doClick();
            check(!((JButton) get(app, "openAudio")).isEnabled(), "Audio chooser remains disabled during cancellable request");
            ((JButton) get(app, "cancel")).doClick();
        });
        awaitWorker(app);
        edt(() -> {
            JTextArea transcript = get(app, "transcript"), summary = get(app, "summary");
            equal(beforeText.get(), transcript.getText(), "Cancel preserves current transcript");
            equal(beforeSummary.get(), summary.getText(), "Cancel preserves current summary");
            check(transcript.isEditable() && summary.isEditable(), "Cancel restores both editors");
            check(((JButton) get(app, "openAudio")).isEnabled(), "Cancel restores audio chooser");
            check(((JLabel) get(app, "status")).getText().startsWith("Cancelled"), "Cancellation is reported accurately");
            equal(URI.create(YOUTUBE + "&t=22s"), ((VideoLinks) get(app, "videoLinks")).currentPlaybackLink().uri(), "Cancel preserves original playback mapping");
        });
    }

    private static void awaitWorker(StillWord app) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean[] done = {false};
        while (!done[0] && System.nanoTime() < deadline) {
            edt(() -> done[0] = get(app, "worker") == null);
            if (!done[0]) Thread.sleep(20);
        }
        check(done[0], "Transcript worker completes");
    }
    @SuppressWarnings("unchecked") private static <T> T get(Object app, String name) throws Exception {
        Field field = StillWord.class.getDeclaredField(name); field.setAccessible(true); return (T) field.get(app);
    }
    private static void set(Object app, String name, Object value) throws Exception {
        Field field = StillWord.class.getDeclaredField(name); field.setAccessible(true); field.set(app, value);
    }
    private static void invoke(Object app, String name) throws Exception {
        Method method = StillWord.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(app);
    }
    private interface Checked { void run() throws Exception; }
    private static void edt(Checked action) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Exception e) { throw new RuntimeException(e); } });
    }
    private static void equal(Object expected, Object actual, String message) { check(java.util.Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual); }
    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); checks++; }
}
