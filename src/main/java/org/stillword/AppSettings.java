/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.util.prefs.Preferences;

/**
 *
 * @author veronique
 */

/** Preferences contain configuration only. API credentials never go to disk. */
final class AppSettings {
    enum TranscriptMode {
        YOUTUBE_CAPTIONS("YouTube captions"), LOCAL_AUDIO("Whisper · Free local");
        private final String label;
        TranscriptMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    enum AiMode {
        LOCAL("Local model — Free"), BROWSER("AI website — Free / subscription"), API("AI API — Fees may apply");
        private final String label;
        AiMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    // Keep the established preferences node so the package rename retains user settings.
    private final Preferences preferences = Preferences.userRoot().node("org/bhavana/stillword");
    AiMode aiMode = readMode();
    SummaryStyle summaryStyle = readSummaryStyle();
    TranscriptMode transcriptMode = readTranscriptMode();
    String endpoint = preferences.get("endpoint", "https://api.openai.com/v1");
    String model = preferences.get("model", "gpt-6-luna");
    SummaryService.Protocol protocol = readProtocol();
    String localEndpoint = preferences.get("localEndpoint", "http://localhost:11434/v1");
    String localModel = preferences.get("localModel", "stillword-gpt-oss");
    String browserWebsite = preferences.get("browserWebsite", "https://chatgpt.com/");
    String executable = preferences.get("executable", "");
    String language = preferences.get("language", "en.*");
    String directory = preferences.get("directory", System.getProperty("user.home"));
    String apiKey = endpoint.equals("https://api.openai.com/v1") ? System.getenv().getOrDefault("OPENAI_API_KEY", "") : "";

    private AiMode readMode() {
        try { return AiMode.valueOf(preferences.get("aiMode", "LOCAL")); }
        catch (IllegalArgumentException e) { return AiMode.LOCAL; }
    }
    private SummaryService.Protocol readProtocol() {
        try { return SummaryService.Protocol.valueOf(preferences.get("protocol", "OPENAI_COMPATIBLE")); }
        catch (IllegalArgumentException e) { return SummaryService.Protocol.OPENAI_COMPATIBLE; }
    }

    private SummaryStyle readSummaryStyle() {
        try { return SummaryStyle.valueOf(preferences.get("summaryStyle", "AUTO")); }
        catch (IllegalArgumentException e) { return SummaryStyle.AUTO; }
    }

    private TranscriptMode readTranscriptMode() {
        try { return TranscriptMode.valueOf(preferences.get("transcriptMode", "YOUTUBE_CAPTIONS")); }
        catch (IllegalArgumentException e) { return TranscriptMode.YOUTUBE_CAPTIONS; }
    }

    void save() {
        preferences.put("aiMode", aiMode.name());
        preferences.put("summaryStyle", summaryStyle.name());
        preferences.put("transcriptMode", transcriptMode.name());
        preferences.put("protocol", protocol.name());
        preferences.put("localEndpoint", localEndpoint);
        preferences.put("localModel", localModel);
        preferences.put("browserWebsite", browserWebsite);
        preferences.put("endpoint", endpoint);
        preferences.put("model", model);
        preferences.put("executable", executable);
        preferences.put("language", language);
        preferences.put("directory", directory);
    }

    SummaryService.Config summaryConfig() {
        return aiMode == AiMode.LOCAL ? new SummaryService.Config(localEndpoint, localModel, "")
                : new SummaryService.Config(endpoint, model, apiKey, protocol);
    }
}
