/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 *
 * @author veronique
 */

/** Opt-in real model installation and smoke check, excluded from the offline test suite. */
public final class LocalAiInstallCheck {
    public static void main(String[] args) throws Exception {
        Path directory = LocalAiService.dataDirectory();
        Files.createDirectories(directory);
        Path status = directory.resolve("setup-status.txt");
        Files.writeString(status, "Downloading and preparing the free local model. Started " + Instant.now() + "\n");
        try {
            LocalAiService local = new LocalAiService();
            local.setup(System.out::println);
            local.ensureRunning(System.out::println);
            Files.writeString(status, "Model downloaded. Running a local summary check. " + Instant.now() + "\n");
            String transcript = """
                    Teacher: Mindfulness begins by noticing the breath without trying to force it.
                    Sit comfortably and notice the sensation of each breath coming in and going out.
                    When thoughts interrupt, recognize that attention has wandered and gently return
                    to breathing. Wandering is an opportunity to practice patience, not a personal
                    failure. Consistent short practice can be more useful than an occasional long
                    session. Carry this same awareness into daily activities such as walking and
                    washing dishes. The aim is to notice experience with kindness and less automatic
                    judgment. Student: Must I stop every thought? Teacher: No. Notice thoughts as
                    passing events and return to the present activity, without struggling against them.
                    Announcement: The monastery fundraiser starts at 9:30 on Saturday and tickets cost
                    forty dollars. Please mention the fundraiser to all your friends.
                    Teacher: To close, remember to approach practice gently and repeat it regularly.
                    """;
            String summary = new SummaryService().summarize(transcript,
                    new SummaryService.Config(LocalAiService.BASE_URL, LocalAiService.MODEL, ""),
                    "Exclude the fundraiser, tickets, schedule, and all administrative announcements. Summarize only the mindfulness teaching.",
                    System.out::println);
            String lower = summary.toLowerCase(Locale.ROOT);
            if (!lower.contains("breath") || lower.contains("fundrais") || lower.contains("ticket") || lower.contains("9:30")
                    || SummaryService.wordCount(summary) < 15)
                throw new IllegalStateException("The local model returned an unexpected smoke summary; review setup.log and retry the check.");
            Files.writeString(directory.resolve("smoke-summary.txt"), summary + "\n");
            Files.writeString(status, "Ready. Local summary and exclusion-guidance smoke check passed. " + Instant.now() + "\n");
            System.out.println("Installation complete; the local summary and exclusion-guidance smoke check passed.");
        } catch (Exception exception) {
            Files.writeString(status, "Setup or smoke check failed. " + Instant.now() + "\n" + exception.getMessage() + "\n");
            throw exception;
        }
    }
}
