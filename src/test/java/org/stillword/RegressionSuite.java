/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 *
 * @author veronique
 */

/** Runs the offline main-based tests in isolated JVMs for both Maven and test.sh. */
public final class RegressionSuite {
    // LocalAiInstallCheck is intentionally excluded: it downloads and runs real models.
    private static final List<String> SUITES = List.of(
            "TranscriptServiceTest", "SummarySourceTest", "SummaryServiceTest",
            "LocalSummaryStreamTest", "LocalSummarySectionsTest", "LocalSummaryWriterTest",
            "SummaryReviewTest", "VideoLinksTest", "ReviewFlowTest", "BrowserAiServiceTest",
            "LocalAiServiceTest", "LocalSpeechServiceTest", "AiRoutingTest",
            "AiModelChoicesTest", "AppSupportTest");

    private RegressionSuite() { }

    public static void main(String[] args) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        int passed = 0;
        for (String suite : SUITES) {
            System.out.printf("Running %s (%d/%d)%n", suite, passed + 1, SUITES.size());
            Process process = new ProcessBuilder(executable, "--add-modules", "jdk.httpserver",
                    "-Djava.awt.headless=true", "-ea", "-classpath", classpath,
                    "org.stillword." + suite).inheritIO().start();
            int result;
            try {
                result = process.waitFor();
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
            if (result != 0) {
                throw new AssertionError(suite + " failed with exit code " + result
                        + " after " + passed + " passing suites.");
            }
            passed++;
        }
        System.out.printf("All %d offline regression suites passed.%n", passed);
    }
}
