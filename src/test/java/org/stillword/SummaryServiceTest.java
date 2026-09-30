/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 *
 * @author veronique
 */

/** Offline integration tests. No credentials, external connections, or paid API calls. */
public final class SummaryServiceTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        jsonCodec();
        requestAndResponse();
        localOpenWeightModel();
        localEmptyLectureRecovery();
        sourceTypeReachesEveryRequest();
        anthropicMessages();
        chunksGuidanceAndAutoLength();
        autoLengthPreservesCompleteAnswer();
        summaryStyles();
        excludedSectionsCanHaveEmptyNotes();
        uncertaintyMarkersSurviveNotes();
        recursiveReduction();
        errorsAndValidation();
        cancellation();
        System.out.println("SummaryServiceTest: " + assertions + " assertions passed.");
    }

    private static void jsonCodec() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", "Meditation \"practice\" \\ /\n\t\b\f\r\u0000 — mettā 🙏");
        data.put("empty", null);
        data.put("list", List.of(true, false, Map.of("value", "hello")));
        check(Json.parse(Json.stringify(data)).equals(data), "JSON Unicode and control characters round-trip");
        check(Json.parse("-12.5e2").toString().equals("-1.25E+3"), "JSON exponent number");
        for (String invalid : List.of("{\"x\":1,\"x\":2}", "[1,]", "01", "1e", "1.", "true false", "\"a\nb\"", "\"\\z\"", "{", "", "[".repeat(110))) {
            expect(IllegalArgumentException.class, () -> Json.parse(invalid));
        }
        expect(IllegalArgumentException.class, () -> Json.stringify(Double.NaN));
        check(SummaryService.wordCount("  Kindness\n\tand\u2003patience  ") == 3, "Unicode whitespace word count");
        String unicode = "x".repeat(23_999) + "🙏" + "y".repeat(24_000);
        List<String> chunks = SummaryService.split(unicode);
        check(String.join("", chunks).equals(unicode), "Chunking preserves every Unicode character");
        check(!Character.isHighSurrogate(chunks.get(0).charAt(chunks.get(0).length() - 1)), "Chunk boundary does not cut a surrogate pair");
    }

    private static void requestAndResponse() throws Exception {
        try (MockServer mock = new MockServer(request -> success("A clear summary of mindfulness."))) {
            List<String> progress = new ArrayList<>();
            String source = "First line.\nSecond line with a \"quote\", Unicode mettā, and a backslash \\.";
            SummaryService.Config config = new SummaryService.Config(mock.base() + "/v1/", "test-model", "test-secret");
            String result = new SummaryService().summarize(source, config, "Omit introductions.", progress::add);
            check(result.equals("A clear summary of mindfulness."), "Summary text is read");
            Request request = mock.requests.get(0);
            check(request.path.equals("/v1/chat/completions"), "Base endpoint normalized");
            check("POST".equals(request.method), "POST method");
            check("Bearer test-secret".equals(request.authorization), "API bearer header");
            check(source.equals(request.source()), "Complete source safely JSON encoded");
            check(request.instruction().contains("Omit introductions."), "User guidance included separately");
            check(request.instruction().contains("untrusted data"), "Transcript grounding instructions included");
            check(request.body.get("model").equals("test-model"), "Configured model included");
            check(request.body.get("stream").equals(false), "Non-streamed response requested");
            check(request.body.containsKey("max_tokens"), "Compatible local output limit included");
            check(!Json.stringify(request.body).contains("test-secret"), "API key never enters model input");
            check(!config.toString().contains("test-secret"), "Config string redacts API key");
            check(progress.size() == 1, "Progress delivered");
        }
        try (MockServer mock = new MockServer(request -> new Reply(200, Json.stringify(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", List.of(Map.of("type", "text", "text", "Part one. "), Map.of("type", "text", "text", "Part two.")))))))))) {
            String result = new SummaryService().summarize("Source", new SummaryService.Config(mock.base() + "/v1/chat/completions/", "local", ""), null);
            check(result.equals("Part one. Part two."), "Text content parts supported");
            check(mock.requests.get(0).path.equals("/v1/chat/completions"), "Full endpoint not duplicated");
            check(mock.requests.get(0).authorization == null, "Local requests work without credentials");
        }
    }

    private static void chunksGuidanceAndAutoLength() throws Exception {
        String source = "Beginning teaching. " + "A useful detail. ".repeat(3_100) + " FINAL DISTINCT CONCLUSION.";
        int count = SummaryService.split(source).size();
        try (MockServer mock = new MockServer(request -> {
            if (request.instruction().contains("Make concise, accurate notes")) return success("Notes from a section.");
            return success("word ".repeat(570));
        })) {
            List<String> progress = new ArrayList<>();
            String result = new SummaryService().summarize(source, mock.config(), "Exclude announcements and the closing Q&A.", progress::add);
            check(result.equals("word ".repeat(570).strip()), "Complete long summary is retained without rewriting or cutting it");
            check(mock.requests.size() == count + 1, "Every source section plus one final summary requested");
            StringBuilder reconstructed = new StringBuilder();
            for (int i = 0; i < count; i++) {
                Request request = mock.requests.get(i);
                reconstructed.append(request.source());
                check(request.instruction().contains("There is no fixed word target"), "Intermediate notes have no artificial word target");
                check(request.instruction().contains("Retain named lists and their individual items or remedies"), "Section notes preserve practical remedies and list items");
            }
            check(source.equals(reconstructed.toString()), "Every transcript character sent in sequence");
            for (Request request : mock.requests) {
                check(request.instruction().contains("Exclude announcements and the closing Q&A."), "Guidance included in all phases");
                check(request.instruction().contains(SummarySource.UNKNOWN.instructions()), "Unknown video format retains the topical default in all phases");
                check(request.instruction().contains("A quoted premise is not an endorsement"), "Quoted premises cannot become fabricated speaker endorsements during chunking");
                check(request.instruction().contains("Verify conditions, exceptions, and contrasts between cases"), "Conditional distinctions are checked in every summary phase");
                check(request.instruction().contains("Preserve if/when/unless distinctions"), "Conditional answers cannot become unqualified explanations");
                check(request.instruction().contains("main points, examples, practical steps, complete lists"), "All phases preserve substantive teaching independent of audience questions");
                check(request.instruction().contains("recitation segment does not exclude earlier teaching"), "Excluding a later activity does not remove earlier practical teaching");
                check(request.instruction().contains("Do not describe excluded sections, repeat their content, or announce their omission"), "Every phase silently applies the requested exclusions");
            }
            Request finalRequest = mock.requests.get(mock.requests.size() - 1);
            check(finalRequest.source().equals("Notes from a section.\n\n".repeat(count)), "Intermediate notes are passed without artificial section labels");
            check(finalRequest.instruction().contains("Use one organization") && finalRequest.instruction().contains("Summarize each included distinct point once"), "Final synthesis avoids duplicate highlights and Q&A");
            check(finalRequest.instruction().endsWith("Output contract: only the summary, one organization, plain text, no Markdown, no omission announcement.\n"), "Final task ends with a concise formatting and exclusion contract");
            check(progress.stream().anyMatch(item -> item.contains("Reading transcript section " + count + " of " + count)), "User progress still identifies the processed section count");
            check(progress.stream().noneMatch(item -> item.contains("Shortening")), "Auto mode does not perform word-count rewrites");
            check(SummaryService.wordCount(result) > 550, "Summaries can exceed the former page limit");
        }
    }

    private static void autoLengthPreservesCompleteAnswer() throws Exception {
        String completeAnswer = "Main teaching. " + "A distinct supporting detail. ".repeat(150) + "\nFinal question: What now? Answer: Practice gently.";
        try (MockServer mock = new MockServer(request -> success(completeAnswer))) {
            List<String> progress = new ArrayList<>();
            String result = new SummaryService().summarize("A talk covering several questions and their answers.",
                    new SummaryService.Config(mock.base(), "test-model", ""), progress::add);
            check(result.equals(completeAnswer), "A complete answer beyond 550 words reaches the user intact");
            check(mock.requests.size() == 1, "A longer local model answer needs no shortening requests");
            check(progress.equals(List.of("Writing your summary…")), "Progress no longer promises a one-page result");
            String instruction = mock.requests.get(0).instruction();
            check(instruction.contains("without a fixed word or page limit"), "Auto requests content-appropriate length");
            check(instruction.toLowerCase().contains("never invent questions or answers"), "Auto does not manufacture Q&A structure");
            check(instruction.contains("If a detail is unclear, say it is unclear instead of guessing"), "Unclear captions are not repaired by fabricated explanations");
            check(instruction.contains("without asterisks, hashes, or bold markers"), "Plain-text headings are requested explicitly");
            check(!instruction.contains("550") && !instruction.contains("one-page"), "No former summary cap remains in the final prompt");
        }
    }

    private static void summaryStyles() throws Exception {
        try (MockServer mock = new MockServer(request -> success("A faithful summary."))) {
            SummaryService service = new SummaryService();
            service.summarize("Source", mock.config(), "Guidance", null);
            service.summarize("Source", mock.config(), "Guidance", SummaryStyle.AUTO, null);
            service.summarize("Source", mock.config(), "Guidance", null, null);
            String defaultInstruction = mock.requests.get(0).instruction();
            check(defaultInstruction.equals(mock.requests.get(1).instruction()), "Existing callers retain exactly the explicit Auto instruction");
            check(defaultInstruction.equals(mock.requests.get(2).instruction()), "Null summary style safely defaults to Auto");
        }
        for (SummaryStyle style : SummaryStyle.values()) {
            try (MockServer mock = new MockServer(request -> success("Complete " + style.label() + " summary."))) {
                String result = new SummaryService().summarize("Question and actual answer.", mock.config(),
                        "Omit the closing recitation.", style, null);
                check(result.equals("Complete " + style.label() + " summary."), "Selected-style response reaches the user intact");
                check(mock.requests.size() == 1, "Styles do not add paid rewrite requests or enforce a word count");
                String instruction = mock.requests.get(0).instruction();
                check(instruction.contains("Selected summary style: " + style.label() + "."), "Final request identifies the selected style");
                check(instruction.contains(style.finalInstructions()), "Final request uses the selected style instructions");
                check(instruction.contains("Omit the closing recitation."), "Style preserves editorial guidance");
                check(instruction.contains("Do not guess a replacement term or invent timestamps"), "Every style preserves uncertainty safeguards");
                if (style == SummaryStyle.BRIEF) {
                    check(instruction.contains("short overview focused on the central message"), "Brief asks for the main points");
                    check(instruction.contains("Do not attempt to enumerate every question"), "Brief does not inherit exhaustive Q&A presentation");
                    check(!instruction.contains("Choose the length automatically"), "Brief has no competing Auto instruction");
                } else if (style == SummaryStyle.DETAILED_QA) {
                    check(instruction.contains("retain each distinct actual question"), "Detailed preserves each actual question");
                    check(instruction.contains("practical steps, named lists") && instruction.contains("useful examples"), "Detailed keeps concrete instructional detail");
                    check(instruction.contains("use a detailed topical summary"), "Detailed never manufactures questions for a lecture");
                }
            }
        }
    }

    private static void excludedSectionsCanHaveEmptyNotes() throws Exception {
        String source = "Q ".repeat(12_000) + "EXCLUDED CLOSING BLESSING.";
        for (SummaryService.Protocol protocol : SummaryService.Protocol.values()) {
            try (MockServer mock = new MockServer(request -> {
                String content;
                if (request.instruction().contains("Make concise, accurate notes"))
                    content = request.source().contains("EXCLUDED CLOSING") ? " \n" : "Retained question and answer.";
                else {
                    check(request.source().equals("Retained question and answer.\n\n"), "Only retained notes reach the final summary");
                    content = "The retained answer is summarized.";
                }
                return protocol == SummaryService.Protocol.ANTHROPIC ? anthropicSuccess(content) : success(content);
            })) {
                String result = new SummaryService().summarize(source,
                        new SummaryService.Config(mock.base(), "test-model", "", protocol), "Omit the closing blessing.", null);
                check(result.equals("The retained answer is summarized."), "A fully excluded final section does not discard the retained Q&A");
                check(mock.requests.size() == 3, "Both sections are read and the retained material is finalized once");
            }
        }
        try (MockServer mock = new MockServer(request -> success(""))) {
            IOException error = expect(IOException.class, () -> new SummaryService().summarize(source, mock.config(), "Exclude all supplied content.", null));
            check(error.getMessage().contains("Nothing remains to summarize"), "All-excluded source has a clear explanation");
            check(mock.requests.size() == 2, "All-empty notes cause no final request");
        }
        // Empty text is allowed; missing, rejected, or truncated completions must still fail during note-taking.
        for (Reply invalid : List.of(
                new Reply(200, "{\"choices\":[]}"),
                new Reply(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}"),
                new Reply(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"\"}}]}"),
                new Reply(401, "{\"error\":{\"message\":\"Authentication failed\"}}"))) {
            try (MockServer mock = new MockServer(request -> invalid)) {
                expect(IOException.class, () -> new SummaryService().summarize(source, mock.config(), "Omit the closing blessing.", null));
                check(mock.requests.size() == 1, "Invalid intermediate response fails instead of silently losing source material");
            }
        }
        try (MockServer mock = new MockServer(request -> success(request.instruction().contains("Make concise, accurate notes")
                ? "Long retained note. ".repeat(850) : ""))) {
            IOException error = expect(IOException.class, () -> new SummaryService().summarize(source, mock.config(), "Exclude all remaining notes.", null));
            check(error.getMessage().contains("Nothing remains to summarize"), "All-empty reduction notes are handled clearly");
            check(mock.requests.stream().noneMatch(request -> request.instruction().contains("Produce a cohesive summary")), "Empty reduction causes no final request");
        }
    }

    private static void uncertaintyMarkersSurviveNotes() throws Exception {
        String marker = "[[check: sam uh sati practice]]";
        String source = "A teaching and its question. ".repeat(1_000);
        try (MockServer mock = new MockServer(request -> {
            if (request.instruction().contains("Make concise, accurate notes"))
                return success("The phrase " + marker + " is unclear in the captions.");
            check(request.source().contains(marker), "Exact uncertainty marker reaches final synthesis unchanged");
            return success("Clarify " + marker + " before interpreting the answer.");
        })) {
            String result = new SummaryService().summarize(source, mock.config(), "Retain the Q&A.", null);
            check(result.contains(marker), "Summary service leaves review annotations intact for the UI parser");
            for (Request request : mock.requests) {
                check(request.instruction().contains("CREATE new check markers from original captions")
                        && request.instruction().contains("even when the input has no markers"), "New uncertainty annotations are explicitly requested from unmarked captions");
                check(request.instruction().replace('\n', ' ').contains("exact 3–12-word source phrase"), "Every phase requests a source-matched phrase");
                check(request.instruction().contains("Do not guess a replacement term or invent timestamps"), "Review annotations cannot invent corrections or time positions");
                check(request.instruction().contains("Preserve existing check markers"), "Intermediate review markers are preserved");
            }
            SummaryReview.Parsed parsed = SummaryReview.parse(result);
            check(parsed.marks().size() == 1 && parsed.marks().get(0).phrase().equals("sam uh sati practice"), "Uncertainty annotation becomes an exact editable-text review span");
        }
    }

    private static void localOpenWeightModel() throws Exception {
        check(SummaryService.DEFAULT_ENDPOINT.equals("http://localhost:11434/v1"), "Fresh configuration uses local AI");
        check(SummaryService.DEFAULT_MODEL.equals("stillword-gpt-oss"), "Free OpenAI open-weight preset is default");
        String source = "Opening. " + "A useful teaching. ".repeat(1_100) + " DISTINCT ENDING.";
        for (String model : List.of(SummaryService.DEFAULT_MODEL, "gpt-oss:20b")) {
            try (MockServer mock = new MockServer(request -> {
                if (request.body.containsKey("response_format")) {
                    List<?> numbered = (List<?>) ((Map<?, ?>) Json.parse(request.source())).get("spans");
                    check(!numbered.isEmpty(), "Local boundary detection receives numbered source spans");
                    return success("{\"starts\":[]}");
                }
                return success("Summary from the local model.");
            })) {
                List<String> progress = new ArrayList<>();
                String result = new SummaryService().summarize(source,
                        new SummaryService.Config(mock.base(), model, ""), "Exclude introductions.", progress::add);
                List<LocalSummarySections.Section> expected = LocalSummarySections.splitBounded(source);
                check(result.equals(String.join("\n\n", Collections.nCopies(expected.size(), "Summary from the local model."))), "Local section summaries are assembled without a final rewrite");
                List<String> windows = new ArrayList<>();
                List<String> drafts = new ArrayList<>();
                for (Request request : mock.requests) {
                    check(request.authorization == null && request.apiKey == null, "Free local requests have no authentication header");
                    check(Boolean.TRUE.equals(request.body.get("stream")), "Local generation streams activity");
                    boolean boundary = request.body.containsKey("response_format");
                    check(request.body.get("reasoning_effort").equals("low"), "Reasoning matches bounded processing phase");
                    check(((Number) request.body.get("max_tokens")).intValue() <= 1_800, "Ordinary local output stays within a bounded budget");
                    check(!request.body.containsKey("max_completion_tokens"), "Ollama compatible output limit used");
                    if (boundary) {
                        check(((Number) request.body.get("max_tokens")).intValue() == 1_200, "Boundary detection has a bounded output budget");
                        check(request.instruction().contains("do not summarize"), "Boundary detection cannot rewrite or select the source");
                        List<?> numbered = (List<?>) ((Map<?, ?>) Json.parse(request.source())).get("spans");
                        StringBuilder passage = new StringBuilder();
                        for (int index = 0; index < numbered.size(); index++) {
                            Map<?, ?> span = (Map<?, ?>) numbered.get(index);
                            check(((Number) span.get("id")).intValue() == index, "Source span IDs are sequential and zero-based");
                            passage.append((String) span.get("text"));
                        }
                        windows.add(passage.toString());
                    } else {
                        check(request.instruction().contains("Exclude introductions."), "Every section receives the user guidance");
                        check(request.instruction().contains(SummaryStyle.AUTO.finalInstructions()), "Every section receives the default Auto style");
                        check(((Number) request.body.get("max_tokens")).intValue() == 1_800, "Ordinary section writing has its bounded output budget");
                        drafts.add(request.source());
                    }
                }
                check(String.join("", windows).equals(source), "Boundary detection sees every source character in order");
                check(drafts.equals(expected.stream().map(LocalSummarySections.Section::text).toList()), "All contiguous source sections are drafted without gaps or overlaps");
                check(mock.requests.size() == windows.size() + drafts.size(), "Only boundary detection and one summary request per section are sent");
                check(progress.stream().anyMatch(p -> p.contains("Writing section")), "Section writing progress is visible");
            }
        }
    }

    private static void localEmptyLectureRecovery() throws Exception {
        String lecture = "Mindfulness of breathing develops tranquility and insight together. Observe the natural breath without forcing it.";
        String guidance = "Use American English. Omit closing prayer and guided meditation.";
        AtomicInteger drafts = new AtomicInteger();
        try (MockServer mock = new MockServer(request -> {
            if (request.body.containsKey("response_format")) return success("{\"starts\":[]}");
            return success(drafts.incrementAndGet() == 1 ? "" : "Natural breathing\nObserve the breath as tranquility and insight develop together.");
        })) {
            String summary = new SummaryService().summarize(lecture,
                    new SummaryService.Config(mock.base(), SummaryService.DEFAULT_MODEL, ""), guidance, SummaryStyle.AUTO, null);
            check(summary.startsWith("Natural breathing"), "A valid empty SSE completion recovers into a topical lecture summary");
            check(mock.requests.size() == 3 && drafts.get() == 2, "HTTP integration performs one boundary request and at most two writing requests");
            Request first = mock.requests.get(1), retry = mock.requests.get(2);
            check(first.source().equals(lecture) && retry.source().equals(lecture), "Streaming recovery sends the unchanged complete lecture source again");
            check(retry.instruction().contains(guidance) && retry.instruction().contains(SummaryStyle.AUTO.finalInstructions()),
                    "Streaming recovery preserves American English, exclusions, and Auto style");
            check(first.body.get("max_tokens").equals(retry.body.get("max_tokens")), "Streaming recovery preserves bounded token budget");
            check(first.body.get("reasoning_effort").equals(retry.body.get("reasoning_effort")), "Streaming recovery preserves reasoning effort");
            check(Boolean.TRUE.equals(retry.body.get("stream")), "Recovery uses the same checked streaming transport");
        }
        AtomicInteger blankDrafts = new AtomicInteger();
        try (MockServer mock = new MockServer(request -> {
            if (request.body.containsKey("response_format")) return success("{\"starts\":[]}");
            blankDrafts.incrementAndGet(); return success("");
        })) {
            IOException empty = expect(IOException.class, () -> new SummaryService().summarize(lecture,
                    new SummaryService.Config(mock.base(), SummaryService.DEFAULT_MODEL, ""), "", SummaryStyle.AUTO, null));
            check(blankDrafts.get() == 2, "Persistent empty SSE output stops after one recovery request");
            check(empty.getMessage().toLowerCase().contains("section 1") && empty.getMessage().toLowerCase().contains("empty"),
                    "Persistent empty SSE output identifies the failed section");
            check(!empty.getMessage().contains("Nothing remains to summarize"), "Empty guidance does not produce a false exclusion diagnosis");
        }
    }

    private static void sourceTypeReachesEveryRequest() throws Exception {
        String source = "Why observe breathing? This rhetorical question introduces the teacher's explanation. ".repeat(310);
        String guidance = "Use American English and omit closing prayer.";
        for (String model : List.of("mock-model", SummaryService.DEFAULT_MODEL)) {
            for (SummarySource mode : SummarySource.values()) {
                try (MockServer mock = new MockServer(request -> success(request.body.containsKey("response_format")
                        ? "{\"starts\":[]}" : "The teacher explains mindful observation of breathing."))) {
                    new SummaryService().summarize(source, new SummaryService.Config(mock.base(), model, ""),
                            guidance, SummaryStyle.AUTO, mode, null);
                    check(mock.requests.size() > 1, "Long source exercises more than one model request");
                    for (Request request : mock.requests) {
                        check(request.instruction().contains(mode.instructions()), "Explicit " + mode + " reaches every " + model + " request");
                        if (mode != SummarySource.QA) check(!request.instruction().contains(SummarySource.QA.instructions()),
                                "Rhetorical question text does not select Q&A instructions");
                        if (!request.body.containsKey("response_format")) check(request.instruction().contains(guidance),
                                "Source-type context preserves guidance throughout drafting and final synthesis");
                    }
                }
            }
            try (MockServer mock = new MockServer(request -> success(request.body.containsKey("response_format")
                    ? "{\"starts\":[]}" : "A topical summary."))) {
                SummaryService service = new SummaryService();
                SummaryService.Config config = new SummaryService.Config(mock.base(), model, "");
                service.summarize("Why breathe? The lecture explains.", config, "", SummaryStyle.AUTO, null);
                List<Map<?, ?>> legacy = mock.requests.stream().map(Request::body).toList();
                mock.requests.clear();
                service.summarize("Why breathe? The lecture explains.", config, "", SummaryStyle.AUTO, SummarySource.UNKNOWN, null);
                check(legacy.equals(mock.requests.stream().map(Request::body).toList()),
                        "Existing " + model + " summary overload uses exactly the explicit unknown-source request contract");
            }
        }
    }

    private static void anthropicMessages() throws Exception {
        Reply response = new Reply(200, Json.stringify(Map.of("type", "message", "stop_reason", "end_turn", "content", List.of(
                Map.of("type", "thinking", "thinking", "Private reasoning is not summary text."),
                Map.of("type", "text", "text", "A helpful "), Map.of("type", "text", "text", "Claude summary.")))));
        try (MockServer mock = new MockServer(request -> response)) {
            SummaryService.Config config = new SummaryService.Config(mock.base() + "/v1", "claude-test", "anthropic-test-secret", SummaryService.Protocol.ANTHROPIC);
            String result = new SummaryService().summarize("The complete transcript.", config, "Exclude the opening prayer.", null);
            check(result.equals("A helpful Claude summary."), "Anthropic text blocks extracted without thinking blocks");
            Request request = mock.requests.get(0);
            check(request.path.equals("/v1/messages"), "Anthropic messages endpoint selected");
            check(request.authorization == null, "Anthropic does not receive a second authorization header");
            check(request.apiKey.equals("anthropic-test-secret"), "Anthropic API key sent in supported x-api-key header");
            check(request.version.equals("2023-06-01"), "Anthropic version header included");
            check(((List<?>) request.body.get("messages")).size() == 1, "Anthropic system prompt is separate from messages");
            check(request.instruction().contains("Exclude the opening prayer."), "Anthropic applies editorial guidance");
            check(request.source().equals("The complete transcript."), "Anthropic receives complete safely encoded source");
            check(request.body.containsKey("max_tokens") && !request.body.containsKey("max_completion_tokens"), "Anthropic required output limit included");
            check(!request.body.containsKey("reasoning_effort"), "OpenAI reasoning parameter omitted for Anthropic");
            check(!config.toString().contains("anthropic-test-secret"), "Anthropic Config redacts API key");
        }
        try (MockServer mock = new MockServer(request -> response)) {
            new SummaryService().summarize("Transcript", new SummaryService.Config(mock.base() + "/v1/messages/", "claude-test", "", SummaryService.Protocol.ANTHROPIC), null);
            check(mock.requests.get(0).path.equals("/v1/messages"), "Full Anthropic endpoint not duplicated");
        }
        SummaryService service = new SummaryService();
        check(expect(IllegalArgumentException.class, () -> service.summarize("Source",
                new SummaryService.Config("https://api.anthropic.com/v1", "claude-test", "", SummaryService.Protocol.ANTHROPIC), null))
                .getMessage().contains("API key"), "Anthropic requires a key before network access");
        for (String stopReason : List.of("max_tokens", "model_context_window_exceeded", "refusal", "tool_use", "pause_turn")) {
            try (MockServer mock = new MockServer(request -> new Reply(200, Json.stringify(Map.of("stop_reason", stopReason,
                    "content", List.of(Map.of("type", "text", "text", "Partial text."))))))) {
                expect(IOException.class, () -> service.summarize("Source",
                        new SummaryService.Config(mock.base(), "claude-test", "", SummaryService.Protocol.ANTHROPIC), null));
            }
        }
        try (MockServer mock = new MockServer(request -> new Reply(200, "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"No visible text\"}]}"))) {
            expect(IOException.class, () -> service.summarize("Source", new SummaryService.Config(mock.base(), "claude-test", "", SummaryService.Protocol.ANTHROPIC), null));
        }
        try (MockServer mock = new MockServer(request -> new Reply(401, Json.stringify(Map.of("type", "error", "error", Map.of(
                "type", "authentication_error", "message", "Invalid key anthropic-test-secret")))))) {
            IOException error = expect(IOException.class, () -> service.summarize("Source",
                    new SummaryService.Config(mock.base(), "claude-test", "anthropic-test-secret", SummaryService.Protocol.ANTHROPIC), null));
            check(error.getMessage().contains("401") && !error.getMessage().contains("anthropic-test-secret"), "Anthropic authentication error is useful and redacted");
        }
        try (MockServer mock = new MockServer(request -> success("Compatible provider summary."))) {
            new SummaryService().summarize("Source", new SummaryService.Config(mock.base() + "/v1beta/openai", "other-model", "provider-secret"), null);
            check(mock.requests.get(0).path.equals("/v1beta/openai/chat/completions"), "Custom provider base path preserved");
            check(mock.requests.get(0).authorization.equals("Bearer provider-secret"), "Compatible providers use their supplied API key");
        }
    }

    private static void recursiveReduction() throws Exception {
        String source = "detail ".repeat(10_500);
        for (SummaryStyle style : SummaryStyle.values()) {
            try (MockServer mock = new MockServer(request -> {
                if (request.instruction().contains("Make concise, accurate notes")) return success("Long note. ".repeat(850));
                if (request.instruction().contains("Condense these source notes")) return success("Reduced notes preserve the main points.");
                return success("Final summary.");
            })) {
                String result = new SummaryService().summarize(source, mock.config(), "Focus on practice.", style, null);
                check(result.equals("Final summary."), "Large note set reduced successfully");
                check(mock.requests.stream().anyMatch(request -> request.instruction().contains("Condense these source notes")), "Reduction pass occurs");
                for (Request request : mock.requests)
                    check(request.instruction().contains("Focus on practice."), "Guidance preserved through reduction");
                for (Request request : mock.requests) {
                    check(request.instruction().contains("Selected summary style: " + style.label() + "."), "Selected style survives every section, reduction, and final pass");
                    if (request.instruction().contains("Make concise, accurate notes") || request.instruction().contains("Condense these source notes")) {
                        check(request.instruction().contains("Preserve all substantive source detail"), "Intermediate style instructions preserve content for the final presentation");
                        check(!request.instruction().contains("Write a short overview"), "Brief presentation does not prematurely discard source details during note-taking");
                    }
                    if (request.instruction().contains("Condense these source notes"))
                        check(request.instruction().contains("Preserve every distinct teaching point")
                                && request.instruction().contains("named lists with their individual items or remedies"),
                                "Reduction preserves distinct teachings, concrete lists, and remedies");
                }
            }
        }
    }

    private static void errorsAndValidation() throws Exception {
        SummaryService service = new SummaryService();
        SummaryService.Config official = new SummaryService.Config(SummaryService.OPENAI_ENDPOINT, SummaryService.OPENAI_MODEL, "");
        check(expect(IllegalArgumentException.class, () -> service.summarize("Source", official, null)).getMessage().contains("API key"), "OpenAI requires a key before network access");
        expect(IllegalArgumentException.class, () -> service.summarize(" ", official, null));
        expect(IllegalArgumentException.class, () -> service.summarize("a".repeat(SummaryService.MAX_TRANSCRIPT_CHARACTERS + 1), official, null));
        expect(IllegalArgumentException.class, () -> service.summarize("Source", official, "a".repeat(4001), null));
        IllegalArgumentException invalidKey = expect(IllegalArgumentException.class, () -> service.summarize("Source", new SummaryService.Config("http://localhost:12345/v1", "model", "secret\u0001value"), null));
        check(!invalidKey.getMessage().contains("secret"), "Invalid header key is never exposed by HTTP validation");
        for (String endpoint : List.of("not-a-url", "http://example.com/v1", "https://secret@example.com/v1", "https://example.com/v1?key=secret", "file:///tmp/server", "https://example.com/v1#fragment"))
            expect(IllegalArgumentException.class, () -> service.summarize("Source", new SummaryService.Config(endpoint, "model", "key"), null));
        try (MockServer mock = new MockServer(request -> new Reply(429, Json.stringify(Map.of("error", Map.of("message", "Quota for test-secret with sk-other-secret is exceeded")))))) {
            IOException error = expect(IOException.class, () -> service.summarize("Source", new SummaryService.Config(mock.base(), "model", "test-secret"), null));
            check(error.getMessage().contains("429") && error.getMessage().contains("quota"), "Useful rate or quota error");
            check(!error.getMessage().contains("test-secret") && !error.getMessage().contains("sk-other-secret"), "Credentials redacted from provider errors");
        }
        for (Reply response : List.of(
                new Reply(200, "<html>Not an API</html>"),
                new Reply(200, "{}"),
                new Reply(200, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"Incomplete\"}}]}"),
                new Reply(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"Declined\"}}]}"),
                new Reply(200, "{\"choices\":[{\"message\":{\"content\":\" \"}}]}"),
                new Reply(500, "Secret HTML content"))) {
            try (MockServer mock = new MockServer(request -> response)) {
                IOException error = expect(IOException.class, () -> service.summarize("Source", mock.config(), null));
                check(!error.getMessage().contains("Secret HTML"), "Raw server response not exposed");
            }
        }
        try (MockServer mock = new MockServer(request -> new Reply(200, "x".repeat(1_100_000)))) {
            expect(IOException.class, () -> service.summarize("Source", mock.config(), null));
        }
    }

    private static void cancellation() throws Exception {
        try (MockServer mock = new MockServer(request -> success("Summary"))) {
            Thread.currentThread().interrupt();
            try {
                expect(InterruptedException.class, () -> new SummaryService().summarize("Source", mock.config(), null));
                check(mock.requests.isEmpty(), "Cancellation before request sends nothing");
            } finally { Thread.interrupted(); }
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (MockServer mock = new MockServer(request -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return success("Late summary.");
        })) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { new SummaryService().summarize("Source", new SummaryService.Config(mock.base(), SummaryService.DEFAULT_MODEL, ""), null); }
                catch (Throwable exception) { failure.set(exception); }
            }, "summary-cancellation-test");
            worker.start();
            try {
                check(entered.await(5, TimeUnit.SECONDS), "Request started before cancellation");
                worker.interrupt();
                worker.join(3_000);
                check(!worker.isAlive(), "In-flight request cancels promptly");
                check(failure.get() instanceof InterruptedException, "Cancellation propagates distinctly");
            } finally {
                release.countDown();
                worker.interrupt();
                worker.join(3_000);
            }
        }
    }

    private record Reply(int status, String body) {}

    private record Request(String path, String method, String authorization, String apiKey, String version, Map<?, ?> body) {
        private String instruction() {
            if (body.get("system") instanceof String system) return system;
            return (String) ((Map<?, ?>) ((List<?>) body.get("messages")).get(0)).get("content");
        }
        private String source() {
            String content = (String) ((Map<?, ?>) ((List<?>) body.get("messages")).get(body.containsKey("system") ? 0 : 1)).get("content");
            try {
                if (Json.parse(content) instanceof Map<?, ?> encoded && encoded.get("source_material") instanceof String source)
                    return source;
            } catch (IllegalArgumentException notJson) { /* Managed local requests send raw source text. */ }
            return content;
        }
    }

    private static Reply success(String content) {
        return new Reply(200, Json.stringify(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))))));
    }

    private static Reply anthropicSuccess(String content) {
        return new Reply(200, Json.stringify(Map.of("type", "message", "stop_reason", "end_turn", "content",
                List.of(Map.of("type", "text", "text", content)))));
    }

    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mock-ai-server");
            thread.setDaemon(true);
            return thread;
        });
        private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

        private MockServer(Function<Request, Reply> handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> handle(exchange, handler));
            server.start();
        }

        private void handle(HttpExchange exchange, Function<Request, Reply> handler) throws IOException {
            try (exchange) {
                Request request = new Request(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("x-api-key"),
                        exchange.getRequestHeaders().getFirst("anthropic-version"),
                        (Map<?, ?>) Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                requests.add(request);
                Reply reply = handler.apply(request);
                String responseBody = reply.body;
                boolean streaming = Boolean.TRUE.equals(request.body.get("stream"));
                if (streaming && reply.status == 200) {
                    Map<?, ?> choice = (Map<?, ?>) ((List<?>) ((Map<?, ?>) Json.parse(responseBody)).get("choices")).get(0);
                    responseBody = "data: " + Json.stringify(Map.of("choices", List.of(Map.of("delta", choice.get("message"), "finish_reason", choice.get("finish_reason"))))) + "\n\ndata: [DONE]\n\n";
                }
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(reply.status, bytes.length);
                try { exchange.getResponseBody().write(bytes); }
                catch (IOException ignored) { /* Expected when the client cancels an oversized response. */ }
            }
        }

        private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private SummaryService.Config config() { return new SummaryService.Config(base() + "/v1", "mock-model", ""); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static <T extends Throwable> T expect(Class<T> type, ThrowingRunnable action) throws Exception {
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) { assertions++; return type.cast(error); }
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + error.getClass().getSimpleName(), error);
        }
        throw new AssertionError("Expected " + type.getSimpleName() + " but nothing was thrown.");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        assertions++;
    }
}
