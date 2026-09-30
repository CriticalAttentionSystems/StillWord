/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 *
 * @author veronique
 */

/** Regression checks for user-facing time entry and lossless text export. */
public final class AppSupportTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        equal(null, TextFiles.time("  "));
        equal(0.0, TextFiles.time("00:00"));
        equal(90.5, TextFiles.time("1:30.5"));
        equal(3723.0, TextFiles.time("01:02:03"));
        equal(120.0, TextFiles.time("120"));
        for (String bad : new String[]{"-1", "1:60", "1::2", "1:2:3:4", "1.5:30", "NaN", "Infinity"}) {
            try { TextFiles.time(bad); throw new AssertionError("Accepted invalid time: " + bad); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        equal("video", TextFiles.filename("  ..."));
        equal("Dhamma - attention - practice", TextFiles.filename("Dhamma / attention : practice"));
        equal("Bhāvanā & mindfulness", TextFiles.filename("Bhāvanā & mindfulness"));
        Path temporary = Files.createTempDirectory("stillword-export-test-");
        try {
            Path export = temporary.resolve("my notes.txt");
            String first = "Bhāvanā — clarity 🪷\n\nKeep repeated words. Keep repeated words.\n";
            TextFiles.save(export, first);
            equal(first, Files.readString(export));
            String second = "Edited summary\nwith a second line.";
            TextFiles.save(export, second);
            equal(second, Files.readString(export));
            try (var children = Files.list(temporary)) { equal(1L, children.count()); }
            try { TextFiles.save(temporary.resolve("missing").resolve("file.txt"), "test"); throw new AssertionError("Invalid directory succeeded"); }
            catch (java.io.IOException expected) { checks++; }
        } finally {
            try (var paths = Files.walk(temporary)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        if (StillWord.class.getResource("/images/monastery.png") == null)
            throw new AssertionError("The monastery artwork must be bundled in the app");
        checks++;
        // Mail drafts must preserve readable line breaks and contain only basic runtime details.
        java.net.URI report = AppInfo.bugReportUri();
        equal("mailto", report.getScheme());
        String[] addressAndQuery = report.getRawSchemeSpecificPart().split("\\?", 2);
        equal("criticalattentionsystems@gmail.com", addressAndQuery[0]);
        java.util.Map<String, String> fields = new java.util.HashMap<>();
        for (String pair : addressAndQuery[1].split("&")) {
            String[] parts = pair.split("=", 2);
            fields.put(parts[0], java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8));
        }
        equal("StillWord " + AppInfo.VERSION + " bug report", fields.get("subject"));
        equal(AppInfo.bugReport(), fields.get("body"));
        equal(false, fields.get("body").contains(System.getProperty("user.home")));
        equal("https://criticalattentionsystems.org", AppInfo.WEBSITE.toString());
        // Switching to local inference must not carry a remote provider's credential or protocol.
        AppSettings settings = new AppSettings();
        settings.apiKey = "synthetic-test-key";
        settings.endpoint = "https://api.anthropic.com/v1";
        settings.model = "test-remote-model";
        settings.protocol = SummaryService.Protocol.ANTHROPIC;
        settings.localEndpoint = "http://localhost:11434/v1";
        settings.localModel = LocalAiService.MODEL;
        settings.aiMode = AppSettings.AiMode.LOCAL;
        equal("", settings.summaryConfig().apiKey());
        equal(SummaryService.Protocol.OPENAI_COMPATIBLE, settings.summaryConfig().protocol());
        equal(settings.localEndpoint, settings.summaryConfig().endpoint());
        equal(LocalAiService.MODEL, settings.summaryConfig().model());
        settings.aiMode = AppSettings.AiMode.API;
        equal("synthetic-test-key", settings.summaryConfig().apiKey());
        equal(SummaryService.Protocol.ANTHROPIC, settings.summaryConfig().protocol());
        equal(settings.endpoint, settings.summaryConfig().endpoint());
        equal(settings.model, settings.summaryConfig().model());
        AiSettingsDialog.validateEndpoint("http://localhost:11434/v1", true); checks++;
        AiSettingsDialog.validateEndpoint("https://provider.example/v1", false); checks++;
        for (String bad : new String[]{"http://provider.example/v1", "https://key@provider.example/v1",
                "https://provider.example/v1?key=secret", "https://provider.example/v1#fragment",
                "file:///tmp/ai", "https://provider.example:99999/v1", "https://provider.example:0/v1"}) {
            try { AiSettingsDialog.validateEndpoint(bad, false); throw new AssertionError("Accepted unsafe endpoint: " + bad); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        try { AiSettingsDialog.validateEndpoint("https://provider.example/v1", true); throw new AssertionError("Local mode accepted remote service"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("AppSupportTest: " + checks + " checks passed");
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }
}
