/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author veronique
 */

/** Offline boundary validation: the injected model can choose boundaries but can never omit source text. */
public final class LocalSummarySectionsTest {
    private static final String NONE = "{\"starts\":[]}";
    private static int checks;

    public static void main(String[] args) throws Exception {
        exactCoverageAndUnicode();
        anchoredBoundaries();
        overlappingAnchor();
        tolerantAnchors();
        windows();
        invalidBoundaries();
        cancellationAndFailure();
        System.out.println("LocalSummarySectionsTest: " + checks + " checks passed.");
    }

    private static void exactCoverageAndUnicode() throws Exception {
        for (String source : List.of("", "Short source.\n\nSecond paragraph.",
                "An ordinary source paragraph with several useful details.\n\n".repeat(380),
                "x".repeat(399) + "🙏" + "y".repeat(5_598) + "🪷" + "z".repeat(30_000),
                " \n\t".repeat(2_100))) {
            List<String> spans = LocalSummarySections.sourceSpans(source);
            equal(source, String.join("", spans), "Numbered spans preserve every source character");
            for (String span : spans) check(!span.isEmpty() && span.length() <= 400, "Numbered spans are bounded and nonempty");
            List<LocalSummarySections.Section> bounded = LocalSummarySections.splitBounded(source);
            checkSections(source, bounded);
            List<LocalSummarySections.Section> inferred = LocalSummarySections.segment(source, input -> NONE);
            equal(bounded, inferred, "No semantic starts falls back to the same complete bounded partition");
        }
        AtomicInteger calls = new AtomicInteger();
        equal(List.of(), LocalSummarySections.segment("", input -> { calls.incrementAndGet(); return NONE; }), "Empty input has no sections");
        equal(0, calls.get(), "Empty input does not call a model");
    }

    private static void anchoredBoundaries() throws Exception {
        String first = "Opening context stays in the first section.\n\n";
        String question = "What are the practical steps for listening carefully today?";
        String answer = "\n\nThe answer includes several specific steps and qualifications.\n\n";
        String second = "How can the same idea apply in daily life?";
        String source = first + question + answer + second + "\n\nFinal answer and closing text are retained.";
        List<LocalSummarySections.Section> sections = LocalSummarySections.segment(source, input -> {
            List<String> spans = numbered(input);
            // Reversed and duplicate model starts must never reorder or duplicate content.
            return Json.stringify(Map.of("starts", List.of(start(idAt(spans, source.indexOf(second)), second),
                    start(idAt(spans, first.length()), "What\tare the practical\nsteps for listening carefully today?"),
                    start(idAt(spans, first.length()), question))));
        });
        equal(3, sections.size(), "Two distinct verified boundaries form three sections");
        equal(first, sections.get(0).text(), "Introductory source is retained even when no model start points to it");
        equal(question + answer, sections.get(1).text(), "Question and following answer remain contiguous and unchanged");
        check(sections.get(2).text().startsWith(second), "Last section starts at the exact original anchor offset");
        checkSections(source, sections);
    }

    private static void overlappingAnchor() throws Exception {
        String anchor = "Are there six distinct reasons to practise each day?";
        String source = "x".repeat(370) + " " + anchor + " " + "Later explanation. ".repeat(35);
        List<String> spans = LocalSummarySections.sourceSpans(source);
        check(spans.get(0).length() < source.indexOf(anchor) + anchor.length(), "Fixture anchor crosses its 400-character source span");
        List<LocalSummarySections.Section> sections = LocalSummarySections.segment(source,
                input -> Json.stringify(Map.of("starts", List.of(start(0, anchor)))));
        equal(source.indexOf(anchor), sections.get(0).text().length(), "An anchor continuing across nearby source spans resolves exactly");
        check(sections.get(1).text().startsWith(anchor), "The anchor is not duplicated or lost at a cross-span boundary");
        checkSections(source, sections);
    }

    private static void tolerantAnchors() throws Exception {
        String first = "How should attention return?";
        String second = "What happens after the initial step when the mind becomes quieter during practice?";
        String source = "Opening. " + "x".repeat(850) + " " + first + " " + "y".repeat(850) + " " + second + " The full answer follows.";
        List<String> spans = LocalSummarySections.sourceSpans(source);
        List<LocalSummarySections.Section> result = LocalSummarySections.segment(source, input -> Json.stringify(Map.of("starts", List.of(
                start(idAt(spans, source.indexOf(first)) + 1, "HOW SHOULD\tATTENTION RETURN?"),
                start(idAt(spans, source.indexOf(second)) - 1, second),
                start(0, "An invented boundary cannot remove content"),
                start(0, "Only two")))));
        equal(3, result.size(), "Nearby IDs, four-word and thirteen-word anchors preserve other valid boundaries");
        equal(source.indexOf(first), result.get(0).text().length(), "Case-insensitive anchor resolves backward from a nearby span ID");
        check(result.get(2).text().startsWith(second), "Anchor resolves forward from a nearby span ID");
        checkSections(source, result);
        String outside = "x".repeat(1_500) + " " + first;
        equal(LocalSummarySections.splitBounded(outside), LocalSummarySections.segment(outside,
                input -> Json.stringify(Map.of("starts", List.of(start(0, first))))), "Anchor beyond 800 characters of context is skipped without losing source");
        for (String anchor : List.of("Three exact words", "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twentyone twentytwo twentythree twentyfour")) {
            String boundedSource = "Opening. " + anchor + " Answer.";
            List<LocalSummarySections.Section> bounded = LocalSummarySections.segment(boundedSource,
                    input -> Json.stringify(Map.of("starts", List.of(start(0, anchor)))));
            equal(2, bounded.size(), "Both three-word and twenty-four-word anchors are accepted");
            equal("Opening. ", bounded.get(0).text(), "Allowed anchor bounds retain the actual original start");
            checkSections(boundedSource, bounded);
        }
        String acrossWindow = "p".repeat(23_950) + " " + first + "\n\n" + "x".repeat(500);
        AtomicInteger calls = new AtomicInteger();
        List<LocalSummarySections.Section> confined = LocalSummarySections.segment(acrossWindow, input ->
                calls.incrementAndGet() == 1 ? NONE : Json.stringify(Map.of("starts", List.of(start(0, first)))));
        equal(2, calls.get(), "Window-edge fixture uses two boundary requests");
        equal(LocalSummarySections.splitBounded(acrossWindow), confined, "A nearby anchor in the previous window cannot establish a boundary for the current window");
        checkSections(acrossWindow, confined);
    }

    private static void windows() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 500; i++) content.append("Topic ").append(i)
                .append(" asks how this example can be understood today?\n\n")
                .append("The answer gives concrete steps, qualifications, and useful context.\n\n");
        String source = content.toString();
        List<String> windows = new ArrayList<>();
        List<LocalSummarySections.Section> sections = LocalSummarySections.segment(source, input -> {
            List<String> spans = numbered(input);
            String window = String.join("", spans); windows.add(window);
            check(window.length() <= 24_000, "Each model window contains at most 24,000 source characters");
            String anchor = spans.stream().filter(text -> text.startsWith("Topic ")).findFirst().orElseThrow().strip();
            return Json.stringify(Map.of("starts", List.of(start(spans.indexOf(spans.stream().filter(text -> text.startsWith("Topic ")).findFirst().orElseThrow()), anchor))));
        });
        check(windows.size() > 2, "Long source exercises multiple boundary requests");
        equal(source, String.join("", windows), "All model windows cover the source exactly once");
        checkSections(source, sections);
    }

    private static void invalidBoundaries() throws Exception {
        String anchor = "What are the practical steps for listening carefully today?";
        String source = anchor + " The full answer follows.";
        for (Object id : List.of(-1, 1, 0.5, "0", true, 4_294_967_296L))
            rejects(source, Json.stringify(Map.of("starts", List.of(Map.of("id", id, "anchor", anchor)))), "Invalid IDs never acquire a source boundary");
        for (String invalid : List.of("not JSON", "[]", "{}", "{\"starts\":null}",
                "{\"starts\":[],\"extra\":true}", "{\"starts\":[{\"id\":0}]}",
                "{\"starts\":[{\"id\":0,\"anchor\":null}]}",
                Json.stringify(Map.of("starts", List.of(Map.of("id", 0, "anchor", anchor, "extra", 1)))),
                Json.stringify(Map.of("starts", java.util.Collections.nCopies(257, start(0, anchor))))))
            rejects(source, invalid, "Boundary JSON fields and count are strict");
        for (String unmatchedAnchor : List.of("practical steps", "What are invented steps for listening carefully today?",
                "What are the practical ... carefully today?", "word ".repeat(25))) {
            equal(LocalSummarySections.splitBounded(source), LocalSummarySections.segment(source,
                    input -> Json.stringify(Map.of("starts", List.of(start(0, unmatchedAnchor))))), "Unmatched or unsuitable optional anchors leave all source content intact");
        }
        String repeatedSource = "Opening context. " + anchor + " " + anchor + " The answer follows both repetitions.";
        List<LocalSummarySections.Section> repeated = LocalSummarySections.segment(repeatedSource,
                input -> Json.stringify(Map.of("starts", List.of(start(0, anchor)))));
        equal(2, repeated.size(), "A repeated local anchor still provides one deterministic boundary");
        equal("Opening context. ", repeated.get(0).text(), "A repeated anchor uses its earliest exact local occurrence");
        equal(anchor + " " + anchor + " The answer follows both repetitions.", repeated.get(1).text(), "Both repetitions remain with the answer");
        checkSections(repeatedSource, repeated);
        check(LocalSummarySections.boundaryInstruction().contains("do not summarize"), "Boundary instructions do not ask the model to select source content");
        check(LocalSummarySections.boundaryInstruction().contains("restatements and clarifications"), "Boundary instructions keep a question and its clarifications together");
        Map<?, ?> schema = (Map<?, ?>) ((Map<?, ?>) LocalSummarySections.responseFormat().get("json_schema")).get("schema");
        equal(false, schema.get("additionalProperties"), "Boundary response schema disallows extra root fields");
    }

    private static void cancellationAndFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            try { LocalSummarySections.segment("Some source text.", input -> { calls.incrementAndGet(); return NONE; }); throw new AssertionError("Expected cancellation"); }
            catch (InterruptedException expected) { checks++; }
            equal(0, calls.get(), "Already-cancelled work does not call the boundary model");
        } finally { Thread.interrupted(); }
        try {
            try {
                LocalSummarySections.segment("Source text. ".repeat(5_000), input -> { calls.incrementAndGet(); Thread.currentThread().interrupt(); return NONE; });
                throw new AssertionError("Expected cancellation");
            } catch (InterruptedException expected) { checks++; }
            equal(1, calls.get(), "Cancellation stops after the in-flight boundary request");
        } finally { Thread.interrupted(); }
        calls.set(0);
        try {
            LocalSummarySections.segment("A source with no removed content.", input -> { calls.incrementAndGet(); throw new IOException("Synthetic service failure"); });
            throw new AssertionError("Expected service failure");
        } catch (IOException expected) {
            equal("Synthetic service failure", expected.getMessage(), "Service failure propagates");
            check(!(expected instanceof LocalSummarySections.InvalidBoundariesException), "Transport failures are distinct from invalid model boundaries");
        }
        equal(1, calls.get(), "The segmenter does not retry failed model calls");
    }

    private static Map<String, Object> start(int id, String anchor) { return Map.of("id", id, "anchor", anchor); }

    private static int idAt(List<String> spans, int offset) {
        int through = 0;
        for (int id = 0; id < spans.size(); id++) { through += spans.get(id).length(); if (offset < through) return id; }
        throw new AssertionError("Missing source offset");
    }

    private static List<String> numbered(String input) {
        List<?> values = (List<?>) ((Map<?, ?>) Json.parse(input)).get("spans");
        List<String> spans = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<?, ?> value = (Map<?, ?>) values.get(i);
            equal(i, ((Number) value.get("id")).intValue(), "Span IDs restart from zero in each source window");
            spans.add((String) value.get("text"));
        }
        return spans;
    }

    private static void rejects(String source, String response, String description) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try {
            LocalSummarySections.segment(source, input -> { calls.incrementAndGet(); return response; });
            throw new AssertionError(description + ": unexpectedly accepted");
        } catch (LocalSummarySections.InvalidBoundariesException expected) { check(expected.getMessage().contains("boundaries"), description); }
        equal(1, calls.get(), "Invalid boundaries fail once for the caller to choose a complete fallback");
        checkSections(source, LocalSummarySections.splitBounded(source));
    }

    private static void checkSections(String source, List<LocalSummarySections.Section> sections) {
        equal(source, String.join("", sections.stream().map(LocalSummarySections.Section::text).toList()), "Sections cover every original character once and in order");
        int offset = 0;
        for (var section : sections) {
            check(!section.text().isEmpty() && section.text().length() <= 6_000, "Final sections are nonempty and bounded to 6,000 characters");
            offset += section.text().length();
            check(offset == source.length() || !(Character.isHighSurrogate(source.charAt(offset - 1))
                    && Character.isLowSurrogate(source.charAt(offset))), "Section boundaries do not split Unicode surrogate pairs");
        }
    }

    private static void equal(Object expected, Object actual, String description) {
        check(Objects.equals(expected, actual), description + ": expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String description) { checks++; if (!condition) throw new AssertionError(description); }
}
