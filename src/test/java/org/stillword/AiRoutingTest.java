/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.util.List;

/**
 *
 * @author veronique
 */

/** Verifies which configurations may start and use Stillword's managed Ollama server. */
public final class AiRoutingTest {
    private static int checks;

    public static void main(String[] arguments) {
        for (String endpoint : List.of(
                "http://localhost:11434", "http://localhost:11434/", "http://localhost:11434/v1",
                "http://localhost:11434/v1/", "http://localhost:11434/v1/chat/completions",
                "http://localhost:11434/v1/chat/completions/", "http://127.0.0.1:11434",
                "http://127.0.0.1:11434/v1/", "http://127.0.0.1:11434/v1/chat/completions/",
                "  HTTP://LOCALHOST:11434/v1/  ")) {
            check(true, endpoint, LocalAiService.MODEL);
            check(true, endpoint, " " + LocalAiService.MODEL + ":latest ");
        }
        // These target a different listener, route, protocol, or model; starting our server would not help.
        for (String endpoint : List.of(
                "http://localhost:1234/v1", "http://localhost/v1", "https://localhost:11434/v1",
                "http://[::1]:11434/v1", "http://127.0.0.2:11434/v1", "http://example.com:11434/v1",
                "http://localhost:11434/api", "http://localhost:11434/v1/messages",
                "http://localhost:11434/prefix/v1", "http://localhost:11434/%76%31",
                "http://user@localhost:11434/v1", "http://localhost:11434/v1?key=value",
                "http://localhost:11434/v1#fragment", "not an endpoint", "")) {
            check(false, endpoint, LocalAiService.MODEL);
        }
        for (String model : List.of("gpt-oss:20b", "other-model", "stillword-gpt-oss:custom", ""))
            check(false, SummaryService.LOCAL_ENDPOINT, model);
        equal(false, StillWord.isManagedLocal(new SummaryService.Config(SummaryService.LOCAL_ENDPOINT,
                LocalAiService.MODEL, "", SummaryService.Protocol.ANTHROPIC)), "Anthropic protocol must not trigger managed startup");
        equal(false, StillWord.isManagedLocal(null), "Missing config");
        equal(false, StillWord.isManagedLocal(new SummaryService.Config(null, LocalAiService.MODEL, "")), "Missing endpoint");
        equal(false, StillWord.isManagedLocal(new SummaryService.Config(SummaryService.LOCAL_ENDPOINT, null, "")), "Missing model");
        System.out.println("AiRoutingTest: " + checks + " checks passed");
    }

    private static void check(boolean expected, String endpoint, String model) {
        equal(expected, StillWord.isManagedLocal(new SummaryService.Config(endpoint, model, "")), endpoint + " / " + model);
    }

    private static void equal(boolean expected, boolean actual, String description) {
        if (expected != actual) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        checks++;
    }
}
