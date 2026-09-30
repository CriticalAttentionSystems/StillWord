/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import com.sun.net.httpserver.HttpServer;
import javax.swing.*;
import java.awt.event.ActionListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author veronique
 */

/** Exercises the edit → redo → timestamp review flow through the real Swing worker and a local mock AI. */
public final class ReviewFlowTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = "Check [[check: the phrase in Pali]] before relying on its meaning.";
            byte[] body = Json.stringify(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        AtomicReference<StillWord> appRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    StillWord app = new StillWord(); appRef.set(app);
                    AppSettings settings = (AppSettings) get(app, "settings");
                    settings.aiMode = AppSettings.AiMode.API;
                    settings.endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
                    settings.model = "offline-test"; settings.apiKey = "";
                    settings.protocol = SummaryService.Protocol.OPENAI_COMPATIBLE;
                    JComboBox<?> style = (JComboBox<?>) get(app, "summaryStyleChoice");
                    check(style.getItemCount() == 3, "Summary style dropdown offers exactly three choices");
                    check(style.getItemAt(0) == SummaryStyle.AUTO && style.getItemAt(1) == SummaryStyle.BRIEF
                            && style.getItemAt(2) == SummaryStyle.DETAILED_QA, "Auto, Brief and Detailed are selectable");
                    selectStyleWithoutSaving(app, SummaryStyle.AUTO);
                    JTextArea transcript = (JTextArea) get(app, "transcript");
                    JTextArea summary = (JTextArea) get(app, "summary");
                    JTextArea guidance = (JTextArea) get(app, "guidance");
                    String original = "Discuss the phrase in Pali and practice meta with patience.";
                    transcript.setText(original);
                    check(get(app, "sourceType") == SummarySource.UNKNOWN, "Pasted text does not assume a source format");
                    set(app, "sourceType", SummarySource.LECTURE);
                    VideoLinks links = (VideoLinks) get(app, "videoLinks");
                    links.setSource("https://www.youtube.com/watch?v=5VDejFcm5_o", List.of(new VttParser.Cue(60, 65, 0, original.length())));
                    guidance.setText("Keep Q&A answers; exclude the closing recitation.");
                    summary.setText("Earlier summary.");
                    set(app, "savedSummary", summary.getText());
                    set(app, "summaryInput", original); set(app, "summaryGuidance", guidance.getText());
                    set(app, "summaryStyleUsed", SummaryStyle.AUTO);
                    set(app, "summarySourceUsed", SummarySource.LECTURE);
                    selectStyleWithoutSaving(app, SummaryStyle.BRIEF);
                    check(((JLabel) get(app, "summaryCount")).getText().contains("style changed"), "Changing only style marks the existing summary stale");
                    check(((JButton) get(app, "summarize")).getText().equals("Redo summary"), "Style change keeps the redo action available");
                    selectStyleWithoutSaving(app, SummaryStyle.AUTO);
                    check(!((JLabel) get(app, "summaryCount")).getText().contains("style changed"), "Returning to the summary's original style clears style staleness");
                    selectStyleWithoutSaving(app, SummaryStyle.BRIEF);
                    int typo = original.indexOf("meta");
                    transcript.replaceRange("mettā", typo, typo + 4);
                    check(get(app, "sourceType") == SummarySource.LECTURE, "A transcript correction retains the loaded lecture format");
                    JButton redo = (JButton) get(app, "summarize");
                    check(redo.getText().equals("Redo summary"), "Existing summary exposes redo");
                    check(((JLabel) get(app, "summaryCount")).getText().contains("source or guidance changed"), "Correction marks summary stale");
                    check(((JButton) get(app, "playPassage")).isEnabled(), "Ordinary correction retains source playback");
                    redo.doClick();
                    check(!transcript.isEditable(), "Snapshot remains stable during request");
                    check(!style.isEnabled(), "Summary style cannot change during a running request");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitWorker(appRef.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    StillWord app = appRef.get();
                    JTextArea transcript = (JTextArea) get(app, "transcript");
                    JTextArea summary = (JTextArea) get(app, "summary");
                    check(request.get().contains("practice mettā with patience"), "AI receives corrected transcript");
                    check(!request.get().contains("practice meta with patience"), "AI does not receive outdated text");
                    check(request.get().contains("exclude the closing recitation"), "Redo retains guidance");
                    check(request.get().contains("Selected summary style: Brief."), "Swing summary worker sends the selected Brief style");
                    check(request.get().contains("Source video type: lecture or talk, not a Q&A video."), "Swing summary worker sends the loaded lecture format");
                    check(!request.get().contains("Selected summary style: Auto."), "Worker does not silently fall back to Auto");
                    check(get(app, "summaryStyleUsed") == SummaryStyle.BRIEF, "Completed summary records the style actually requested");
                    check(get(app, "summarySourceUsed") == SummarySource.LECTURE, "Completed summary records the video format requested");
                    check(summary.getText().equals("Check the phrase in Pali before relying on its meaning."), "Review markup becomes editable plain text");
                    check(((VideoLinks) get(app, "videoLinks")).linkedAmbiguityCount() == 1, "AI ambiguity links to retained source timestamp");
                    check(!((JLabel) get(app, "summaryCount")).getText().contains("source or guidance changed"), "Completed redo clears stale status");
                    check(!((JLabel) get(app, "summaryCount")).getText().contains("style changed"), "Completed redo clears style staleness");
                    check(transcript.isEditable() && summary.isEditable(), "Editors remain editable after redo");
                    check(((JComboBox<?>) get(app, "summaryStyleChoice")).isEnabled(), "Summary style becomes selectable after completion");
                    selectStyleWithoutSaving(app, SummaryStyle.DETAILED_QA);
                    check(((JLabel) get(app, "summaryCount")).getText().contains("style changed"), "A later style change marks the generated summary stale");
                    set(app, "savedSummary", summary.getText());
                    ((JButton) get(app, "summarize")).doClick();
                    check(!((JComboBox<?>) get(app, "summaryStyleChoice")).isEnabled(), "Redo also locks the selected style while running");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            awaitWorker(appRef.get());
            SwingUtilities.invokeAndWait(() -> {
                try {
                    StillWord app = appRef.get();
                    JTextArea transcript = (JTextArea) get(app, "transcript");
                    JTextArea summary = (JTextArea) get(app, "summary");
                    check(request.get().contains("Selected summary style: Detailed."), "Redo sends the newly selected Detailed style");
                    check(request.get().contains("Even the Detailed style means a detailed topical summary for this video."), "Detailed style keeps a lecture organized as teaching");
                    check(request.get().contains("practice mettā with patience") && request.get().contains("exclude the closing recitation"),
                            "Style-only redo preserves corrected transcript and existing guidance");
                    check(get(app, "summaryStyleUsed") == SummaryStyle.DETAILED_QA, "Redo updates the recorded summary style");
                    check(!((JLabel) get(app, "summaryCount")).getText().contains("style changed"), "Style-only redo clears stale status");
                    check(((JComboBox<?>) get(app, "summaryStyleChoice")).isEnabled(), "Style control re-enables after style-only redo");
                    manualSummaryEditingAllStyles(app);
                    summary.setText("An independently pasted answer.");
                    check(((JLabel) get(app, "summaryCount")).getText().contains("source not tracked"), "Direct replacement does not inherit older source provenance");
                    transcript.append(" Another correction.");
                    check(((JLabel) get(app, "summaryCount")).getText().contains("source not tracked"), "Unknown provenance stays explicit after a source edit");
                    set(app, "transcriptProvenance", "Whisper audio");
                    set(app, "sourceType", SummarySource.QA);
                    transcript.append(" A local correction.");
                    check(get(app, "sourceType") == SummarySource.QA, "Ordinary edits retain a Q&A video's format");
                    check(((JLabel) get(app, "transcriptCount")).getText().contains("Whisper audio"), "Ordinary edits retain the transcript's original source");
                    transcript.setText("An independently pasted transcript.");
                    check(get(app, "sourceType") == SummarySource.UNKNOWN, "Wholesale replacement does not inherit the earlier video's format");
                    check(get(app, "transcriptProvenance").equals(""), "Wholesale replacement does not inherit the speech model's provenance");
                    check(!((JLabel) get(app, "transcriptCount")).getText().contains("Whisper audio"), "Unrelated pasted text is not labeled Whisper output");
                    check(!((JButton) get(app, "playPassage")).isEnabled(), "Unrelated pasted text has no invented timestamp playback");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        } finally { server.stop(0); }
        System.out.println("ReviewFlowTest: " + checks + " checks passed");
    }
    private static void manualSummaryEditingAllStyles(StillWord app) throws Exception {
        JTextArea summary = (JTextArea) get(app, "summary");
        JTextArea transcript = (JTextArea) get(app, "transcript");
        String originalTranscript = transcript.getText();
        String expected = summary.getText();
        Path saved = Files.createTempFile("stillword-edited-summary-", ".txt");
        try {
            for (SummaryStyle style : SummaryStyle.values()) {
                selectStyleWithoutSaving(app, style);
                check(summary.isEditable(), style.label() + " summary remains editable by hand");
                check(summary.getText().equals(expected), "Switching to " + style.label() + " preserves existing manual edits");
                summary.setCaretPosition(summary.getDocument().getLength());
                String addition = "\nHand correction in " + style.label() + ": mettā 🙏.";
                summary.replaceSelection(addition);
                expected += addition;
                check(summary.getText().equals(expected), style.label() + " accepts a manual text correction");
                check(((JButton) get(app, "saveSummary")).isEnabled(), style.label() + " edited summary can be saved");
                // The native file chooser cannot run headlessly. StillWord.save(true) passes
                // area.getText() to this operation after the user selects a path, with no style branch.
                TextFiles.save(saved, summary.getText());
                check(Files.readString(saved, StandardCharsets.UTF_8).equals(expected), style.label() + " save retains the current editor text and Unicode");
            }
            selectStyleWithoutSaving(app, SummaryStyle.AUTO);
            check(summary.getText().equals(expected), "Switching back to Auto retains edits from every style");
            check(transcript.getText().equals(originalTranscript), "Manual summary edits leave the transcript unchanged");
        } finally { Files.deleteIfExists(saved); }
    }
    private static void selectStyleWithoutSaving(StillWord app, SummaryStyle selected) throws Exception {
        JComboBox<?> choice = (JComboBox<?>) get(app, "summaryStyleChoice");
        ActionListener[] listeners = choice.getActionListeners();
        for (ActionListener listener : listeners) choice.removeActionListener(listener);
        try { choice.setSelectedItem(selected); }
        finally { for (ActionListener listener : listeners) choice.addActionListener(listener); }
        Method changed = StillWord.class.getDeclaredMethod("documentChanged");
        changed.setAccessible(true); changed.invoke(app);
    }
    private static void awaitWorker(StillWord app) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean[] done = {false};
        while (!done[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                try { done[0] = get(app, "worker") == null; }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (!done[0]) Thread.sleep(20);
        }
        check(done[0], "Redo completes");
    }
    private static Object get(Object object, String name) throws Exception { Field field = StillWord.class.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = StillWord.class.getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static void check(boolean condition, String description) { if (!condition) throw new AssertionError(description); checks++; }
}
