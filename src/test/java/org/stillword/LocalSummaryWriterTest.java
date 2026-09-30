/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author veronique
 */

/** Deterministic orchestration checks; no model, network, or saved preferences. */
public final class LocalSummaryWriterTest {
    private static int checks;
    private static final String DETAILS = " Supporting detail retains the conditions and examples from this answer.".repeat(16);
    private static final String FIRST = "First question asks why awareness fades. The corrected transcript says practice mettā with patience, and the answer distinguishes knowing from assuming." + DETAILS + "\n\n";
    private static final String SECOND = "Second question asks how breath counting ends. Count until attention becomes steady, then stop counting and observe the breath directly." + DETAILS + "\n\n";
    private static final String THIRD = "Third question asks about daily practice routines. The reply gives a careful practical sequence, explains its limits, and keeps each instruction tied to its conditions." + DETAILS;
    private static final String SOURCE = FIRST + SECOND + THIRD;
    private static final String GUIDANCE = "Keep actual Q&A answers, exclude the closing recitation, and retain uncertainty.";

    public static void main(String[] arguments) throws Exception {
        orderedSections();
        joinedSections();
        boundedReadingEffort();
        fallbackCoversCompleteSource();
        exclusionsAndEmptyResults();
        emptyLectureRecovery();
        sourceTypeSurvivesRecovery();
        rejectedOmissionRecovery();
        noPartialSummaryAfterEmptySection();
        recoveryFailuresAndCancellation();
        failuresAndCancellation();
        plainTextAndMarkers();
        System.out.println("LocalSummaryWriterTest: " + checks + " checks passed.");
    }

    private static void joinedSections() throws Exception {
        String introduction = "An introduction with mettā and a pause.\n\n";
        String restatedQuestion = "Second question asks how breath counting ends. Let me restate that question.\n\n";
        String answer = "Third question asks about daily practice routines. This is the full explanation." + DETAILS;
        String ending = "A brief ending, preserved exactly. 🙏";
        List<LocalSummarySections.Section> input = sections(introduction, restatedQuestion, answer, FIRST, ending);
        List<LocalSummarySections.Section> joined = LocalSummaryWriter.joinShortSections(input);
        equal(List.of(introduction + restatedQuestion + answer, FIRST, ending), joined.stream().map(LocalSummarySections.Section::text).toList(),
                "short introduction and restated question join the following answer without moving the ending");
        equal(join(input), join(joined), "coalescing preserves all characters, whitespace, Unicode and source order");
        check(joined.stream().allMatch(section -> section.text().length() <= LocalSummarySections.SECTION_CHARACTERS), "coalesced sections respect the source bound");
        equal(5, input.size(), "coalescing leaves the input list unchanged");
        equal(List.of(), LocalSummaryWriter.joinShortSections(List.of()), "empty section list stays empty");

        String shortPart = "a".repeat(999);
        var exact = LocalSummaryWriter.joinShortSections(sections(shortPart, "b".repeat(5001)));
        equal(1, exact.size(), "join is allowed at exactly 6000 characters");
        equal(6000, exact.get(0).text().length(), "exact-boundary join has no added separator");
        var tooLarge = LocalSummaryWriter.joinShortSections(sections(shortPart, "b".repeat(5002)));
        equal(2, tooLarge.size(), "short section is retained separately if joining exceeds 6000 characters");
        equal(shortPart + "b".repeat(5002), join(tooLarge), "over-boundary refusal still preserves exact coverage");
        equal(2, LocalSummaryWriter.joinShortSections(sections("a".repeat(1000), "b".repeat(1000))).size(),
                "an already substantial section is not joined to the next topic");
        var threshold = LocalSummaryWriter.joinShortSections(sections(shortPart, "b", "c".repeat(200)));
        equal(List.of(shortPart + "b", "c".repeat(200)), threshold.stream().map(LocalSummarySections.Section::text).toList(),
                "joining stops once the short section reaches 1000 characters");

        String source = introduction + restatedQuestion + answer;
        List<String> drafts = new ArrayList<>(), progress = new ArrayList<>();
        AtomicInteger completionCalls = new AtomicInteger();
        String result = LocalSummaryWriter.summarize(source, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    completionCalls.incrementAndGet();
                    if (format != null) return boundaries(text);
                    if (!phase.startsWith("Writing")) throw new AssertionError("Unexpected rewrite");
                    drafts.add(text); return "One complete discussion.";
                }, progress::add);
        equal(List.of(source), drafts, "production writer receives the joined original discussion in one request");
        equal(2, completionCalls.get(), "joined discussion uses one boundary call and one writing call");
        equal("One complete discussion.", result, "joined output is assembled directly");
        check(progress.contains("Writing section 1 of 1…"), "progress reflects joined section count");
    }

    private static List<LocalSummarySections.Section> sections(String... values) {
        return java.util.Arrays.stream(values).map(LocalSummarySections.Section::new).toList();
    }
    private static String join(List<LocalSummarySections.Section> values) {
        return String.join("", values.stream().map(LocalSummarySections.Section::text).toList());
    }

    private static void boundedReadingEffort() throws Exception {
        for (String source : List.of("I don't understand that question. I will explain what I can about the breath.",
                "We cannot know the answer to that question from this discussion.",
                "I can’t really explain that. Here is what we observe in practice.")) {
            List<Call> drafts = new ArrayList<>();
            LocalSummaryWriter.summarize(source, GUIDANCE, SummaryStyle.AUTO,
                    (instruction, text, tokens, format, effort, phase) -> {
                        if (format != null) return "{\"starts\":[]}";
                        drafts.add(new Call(instruction, text, tokens, format, effort, phase));
                        return "A source-limited response.";
                    }, ignored -> { });
            equal(1, drafts.size(), "careful section is still written only once");
            equal("medium", drafts.get(0).effort(), "explicit first-person uncertainty gets more reasoning");
            check(drafts.get(0).tokens() > 1800 && drafts.get(0).tokens() <= 3200, "careful section receives bounded additional allowance");
            equal(source, drafts.get(0).source(), "uncertainty source is passed without rewriting");
        }
        check(!LocalSummaryWriter.needsCarefulReading("The speaker explains how to understand the breath."), "ordinary explanation does not trigger extra reasoning");
    }

    private static void orderedSections() throws Exception {
        for (SummaryStyle style : SummaryStyle.values()) {
            List<Call> calls = new ArrayList<>();
            List<String> progress = new ArrayList<>();
            AtomicInteger draft = new AtomicInteger();
            String result = LocalSummaryWriter.summarize(SOURCE, GUIDANCE, style,
                    (instruction, source, tokens, format, effort, phase) -> {
                        calls.add(new Call(instruction, source, tokens, format, effort, phase));
                        if (format != null) return boundaries(source);
                        if (phase.startsWith("Writing")) return "Section answer " + draft.incrementAndGet();
                        throw new AssertionError("Unexpected second pass or global rewrite: " + phase);
                    }, progress::add);
            equal("Section answer 1\n\nSection answer 2\n\nSection answer 3", result, style + " assembles sections in source order");
            equal(4, calls.size(), "one boundary call and exactly one draft per section");
            List<Call> drafts = calls.stream().filter(call -> call.phase().startsWith("Writing")).toList();
            equal(List.of(FIRST, SECOND, THIRD), drafts.stream().map(Call::source).toList(), "whole questions and answers passed in source order");
            equal(SOURCE, String.join("", drafts.stream().map(Call::source).toList()), "every original source character covered once");
            for (int i = 0; i < 3; i++) {
                Call write = drafts.get(i);
                check(write.instruction().contains(GUIDANCE), "guidance reaches every source section");
                check(write.instruction().contains(style.finalInstructions()), "selected style reaches every source section");
                check(write.tokens() > 0 && write.tokens() <= 3200, "individual request budgets remain bounded");
                equal("low", write.effort(), "draft effort");
                if (i < 2) check(calls.indexOf(write) < calls.indexOf(drafts.get(i + 1)), "sections processed in source order");
            }
            check(drafts.get(0).source().contains("practice mettā with patience"), "manual source correction reaches writer");
            check(progress.contains("Writing section 3 of 3…"), "section progress covers final source section");
            check(progress.stream().noneMatch(value -> value.startsWith("Checking")), "progress does not claim an AI factual verification pass");
        }
    }

    private static void fallbackCoversCompleteSource() throws Exception {
        String source = "Complete source sentence with corrected mettā instructions. ".repeat(240);
        List<String> drafts = new ArrayList<>(), progress = new ArrayList<>();
        String result = LocalSummaryWriter.summarize(source, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return "not valid boundary JSON";
                    if (!phase.startsWith("Writing")) throw new AssertionError("Unexpected second pass");
                    drafts.add(text); return "Section " + drafts.size();
                }, progress::add);
        equal(source, String.join("", drafts), "invalid model boundaries trigger complete deterministic coverage");
        check(drafts.size() > 1, "fallback source is split into bounded requests");
        check(drafts.stream().allMatch(text -> text.length() <= LocalSummarySections.SECTION_CHARACTERS), "fallback respects per-section source limit");
        check(result.startsWith("Section 1") && result.endsWith("Section " + drafts.size()), "fallback output retains first and last sections");
        check(progress.contains("Using complete transcript passages…"), "fallback is reported to caller");
    }

    private static void exclusionsAndEmptyResults() throws Exception {
        AtomicInteger written = new AtomicInteger();
        String result = LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return boundaries(text);
                    if (!phase.startsWith("Writing")) throw new AssertionError("Unexpected second pass");
                    int section = written.incrementAndGet();
                    return section == 2 ? LocalSummaryWriter.OMIT_EXCLUDED : "Included answer " + section;
                }, ignored -> { });
        equal("Included answer 1\n\nIncluded answer 3", result, "explicitly excluded section produces no filler and later answer remains");
        equal(3, written.get(), "explicit omission does not stop remaining source processing or retry valid omissions");

        AtomicInteger omittedDrafts = new AtomicInteger();
        IOException empty = failure(IOException.class, () -> LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.BRIEF,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return boundaries(text);
                    omittedDrafts.incrementAndGet();
                    return LocalSummaryWriter.OMIT_EXCLUDED;
                }, ignored -> { }));
        check(empty.getMessage().toLowerCase().contains("local ai") && empty.getMessage().toLowerCase().contains("marked"),
                "all-omitted error attributes the classification to the model rather than asserting exclusions as fact");
        equal(3, omittedDrafts.get(), "all source sections considered before reporting the model omitted everything");
        AtomicInteger fillerDrafts = new AtomicInteger();
        failure(IOException.class, () -> LocalSummaryWriter.summarize("Hello. Thank you. Goodbye.", "", SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return "{\"starts\":[]}";
                    fillerDrafts.incrementAndGet(); return LocalSummaryWriter.OMIT_FILLER;
                }, ignored -> { }));
        equal(1, fillerDrafts.get(), "explicit filler can be omitted without user guidance or a retry");
        failure(IOException.class, () -> LocalSummaryWriter.summarize(FIRST, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> format != null ? "{\"starts\":[]}"
                        : "## \n", ignored -> { }));
    }

    private static void emptyLectureRecovery() throws Exception {
        String lecture = "Mindfulness of breathing develops tranquility and insight together. Choose a quiet place and a steady posture. "
                + "Observe whether a breath is long or short without forcing it. The teacher compares training attention to a calf tied to a post.";
        for (SummaryStyle style : SummaryStyle.values()) {
            for (String guidance : List.of("", "Use American English. Omit closing meditation and prayer, but retain instruction about practice.")) {
                List<Call> drafts = new ArrayList<>();
                String result = LocalSummaryWriter.summarize(lecture, guidance, style,
                        (instruction, text, tokens, format, effort, phase) -> {
                            if (format != null) return "{\"starts\":[]}";
                            drafts.add(new Call(instruction, text, tokens, format, effort, phase));
                            return drafts.size() == 1 ? " \n\t" : "Breathing practice\nThe teaching joins tranquility and insight through observation of natural breathing.";
                        }, ignored -> { });
                equal(2, drafts.size(), "a blank lecture result receives exactly one bounded recovery attempt");
                equal("Breathing practice\nThe teaching joins tranquility and insight through observation of natural breathing.", result,
                        "recovered lecture output reaches the user without empty-section placeholders");
                equal(lecture, drafts.get(0).source(), "lecture source reaches the initial writer unchanged");
                equal(lecture, drafts.get(1).source(), "recovery keeps the entire same source unchanged");
                equal(drafts.get(0).tokens(), drafts.get(1).tokens(), "recovery retains the bounded output budget");
                equal(drafts.get(0).effort(), drafts.get(1).effort(), "recovery retains reasoning effort");
                check(drafts.get(1).instruction().contains(style.finalInstructions()), "recovery preserves the selected style");
                if (!guidance.isBlank()) check(drafts.get(1).instruction().contains(guidance), "recovery preserves user exclusions and American English preference");
                check(!drafts.get(0).instruction().equals(drafts.get(1).instruction()), "recovery adds an explicit instruction rather than blindly repeating the request");
            }
        }
    }

    private static void sourceTypeSurvivesRecovery() throws Exception {
        String source = "Why do we watch the breath? The teacher uses this rhetorical question to introduce mindful attention. "
                + "How is that attention sustained? He compares the mind to a calf tied to a post and continues the lecture.";
        for (SummarySource mode : SummarySource.values()) {
            for (SummaryStyle style : SummaryStyle.values()) {
                List<Call> calls = new ArrayList<>();
                AtomicInteger attempts = new AtomicInteger();
                String result = LocalSummaryWriter.summarize(source, GUIDANCE, style, mode,
                        (instruction, text, tokens, format, effort, phase) -> {
                            calls.add(new Call(instruction, text, tokens, format, effort, phase));
                            if (format != null) return "{\"starts\":[]}";
                            return attempts.incrementAndGet() == 1 ? "" : "Mindful attention\nThe teacher illustrates sustained attention with the calf analogy.";
                        }, ignored -> { });
                equal(3, calls.size(), "source-aware lecture uses one boundary request, one first draft, and one recovery");
                for (Call call : calls) {
                    check(call.instruction().contains(mode.instructions()), mode + " context reaches boundary, drafting, and recovery requests");
                    if (mode != SummarySource.QA) check(!call.instruction().contains(SummarySource.QA.instructions()),
                            "rhetorical questions cannot replace explicit lecture or unknown context with Q&A");
                }
                List<Call> drafts = calls.stream().filter(call -> call.format() == null).toList();
                for (Call draft : drafts) {
                    equal(source, draft.source(), "rhetorical questions remain exact source data throughout recovery");
                    check(draft.instruction().contains(style.finalInstructions()) && draft.instruction().contains(GUIDANCE),
                            "source context augments rather than replaces selected style and guidance");
                }
                check(result.startsWith("Mindful attention"), "source-aware recovered output is retained");
            }
        }
        List<Call> legacy = new ArrayList<>(), explicit = new ArrayList<>();
        LocalSummaryWriter.Completion legacyCompletion = (instruction, text, tokens, format, effort, phase) -> {
            legacy.add(new Call(instruction, text, tokens, format, effort, phase));
            return format != null ? "{\"starts\":[]}" : "A topical summary.";
        };
        LocalSummaryWriter.Completion explicitCompletion = (instruction, text, tokens, format, effort, phase) -> {
            explicit.add(new Call(instruction, text, tokens, format, effort, phase));
            return format != null ? "{\"starts\":[]}" : "A topical summary.";
        };
        LocalSummaryWriter.summarize(source, "", SummaryStyle.AUTO, legacyCompletion, ignored -> { });
        LocalSummaryWriter.summarize(source, "", SummaryStyle.AUTO, SummarySource.UNKNOWN, explicitCompletion, ignored -> { });
        equal(explicit, legacy, "legacy writer overload delegates to the explicit unknown-source contract");
    }

    private static void rejectedOmissionRecovery() throws Exception {
        for (String invalid : List.of(LocalSummaryWriter.OMIT_EXCLUDED, "[[omit:unknown]]",
                LocalSummaryWriter.OMIT_FILLER + " More text", "Heading\n" + LocalSummaryWriter.OMIT_EXCLUDED)) {
            AtomicInteger drafts = new AtomicInteger();
            String result = LocalSummaryWriter.summarize(FIRST, "", SummaryStyle.AUTO,
                    (instruction, text, tokens, format, effort, phase) -> {
                        if (format != null) return "{\"starts\":[]}";
                        return drafts.incrementAndGet() == 1 ? invalid : "Substantive teaching retained.";
                    }, ignored -> { });
            equal(2, drafts.get(), "unauthorized or malformed omission requests one recovery attempt");
            equal("Substantive teaching retained.", result, "invalid omission marker cannot silently delete source or leak into the summary");
        }
        AtomicInteger drafts = new AtomicInteger();
        IOException invalid = failure(IOException.class, () -> LocalSummaryWriter.summarize(FIRST, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return "{\"starts\":[]}";
                    drafts.incrementAndGet(); return "[[omit:unknown]]";
                }, ignored -> { }));
        equal(2, drafts.get(), "persistent invalid markers stop after one retry");
        check(invalid.getMessage().toLowerCase().contains("section 1") && invalid.getMessage().toLowerCase().contains("invalid"),
                "persistent invalid result identifies the affected section and actual failure");
    }

    private static void noPartialSummaryAfterEmptySection() throws Exception {
        List<String> drafted = new ArrayList<>();
        IOException error = failure(IOException.class, () -> LocalSummaryWriter.summarize(SOURCE, "", SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return boundaries(text);
                    drafted.add(text);
                    return text.equals(FIRST) ? "A valid first section." : "";
                }, ignored -> { }));
        equal(List.of(FIRST, SECOND, SECOND), drafted, "blank section retries in place, then stops without publishing partial success or processing later material");
        String message = error.getMessage().toLowerCase();
        check(message.contains("section 2") && message.contains("local ai") && message.contains("empty"), "persistent blank error names the local AI, empty result, and section");
        check(!message.contains("nothing remains") && !message.contains("requested exclusions"), "blank guidance cannot produce an exclusion accusation");

        List<String> successfulDrafts = new ArrayList<>();
        String result = LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return boundaries(text);
                    successfulDrafts.add(text);
                    if (text.equals(FIRST)) return LocalSummaryWriter.OMIT_FILLER;
                    if (text.equals(SECOND) && java.util.Collections.frequency(successfulDrafts, SECOND) == 1) return "";
                    return text.equals(SECOND) ? "Recovered middle teaching." : "Final teaching.";
                }, ignored -> { });
        equal(List.of(FIRST, SECOND, SECOND, THIRD), successfulDrafts, "explicit omission plus recovery preserves source order and reaches later content");
        equal("Recovered middle teaching.\n\nFinal teaching.", result, "valid recovered sections appear exactly once after omission");
    }

    private static void recoveryFailuresAndCancellation() throws Exception {
        IOException transport = new IOException("The local server disconnected during recovery.");
        AtomicInteger attempts = new AtomicInteger();
        IOException actual = failure(IOException.class, () -> LocalSummaryWriter.summarize(FIRST, "", SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return "{\"starts\":[]}";
                    if (attempts.incrementAndGet() == 1) return "";
                    throw transport;
                }, ignored -> { }));
        check(actual == transport, "recovery transport error retains its cause instead of becoming an empty-result error");
        equal(2, attempts.get(), "recovery transport error cannot start another retry");

        InterruptedException cancelled = new InterruptedException("Cancel recovery.");
        AtomicInteger cancelledAttempts = new AtomicInteger();
        InterruptedException actualCancellation = failure(InterruptedException.class, () -> LocalSummaryWriter.summarize(FIRST, "", SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return "{\"starts\":[]}";
                    if (cancelledAttempts.incrementAndGet() == 1) return "";
                    throw cancelled;
                }, ignored -> { }));
        check(actualCancellation == cancelled, "recovery cancellation propagates unchanged");
        equal(2, cancelledAttempts.get(), "recovery cancellation cannot trigger another completion");

        AtomicInteger beforeRecovery = new AtomicInteger();
        try {
            failure(InterruptedException.class, () -> LocalSummaryWriter.summarize(FIRST, "", SummaryStyle.AUTO,
                    (instruction, text, tokens, format, effort, phase) -> {
                        if (format != null) return "{\"starts\":[]}";
                        beforeRecovery.incrementAndGet(); Thread.currentThread().interrupt(); return "";
                    }, ignored -> { }));
        } finally { Thread.interrupted(); }
        equal(1, beforeRecovery.get(), "interrupt after the empty first result prevents a recovery request");
    }

    private static void failuresAndCancellation() throws Exception {
        IOException transport = new IOException("The local server disconnected.");
        AtomicInteger calls = new AtomicInteger();
        IOException actual = failure(IOException.class, () -> LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> { calls.incrementAndGet(); throw transport; }, ignored -> { }));
        check(actual == transport, "boundary transport failure propagates unchanged");
        equal(1, calls.get(), "transport failure cannot become a silent boundary fallback");

        AtomicInteger written = new AtomicInteger();
        IOException later = new IOException("Second section failed.");
        IOException partial = failure(IOException.class, () -> LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return boundaries(text);
                    if (!phase.startsWith("Writing")) throw new AssertionError("Unexpected second pass");
                    if (written.incrementAndGet() == 2) throw later;
                    return "Earlier section answer";
                }, ignored -> { }));
        check(partial == later && written.get() == 2, "failure after an answer returns failure instead of a partial summary");

        InterruptedException cancelled = new InterruptedException("Cancelled section writer");
        InterruptedException interruption = failure(InterruptedException.class, () -> LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> {
                    if (format != null) return boundaries(text);
                    throw cancelled;
                }, ignored -> { }));
        check(interruption == cancelled, "completion cancellation propagates without fallback or partial result");
        try {
            failure(InterruptedException.class, () -> LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.AUTO,
                    (instruction, text, tokens, format, effort, phase) -> {
                        if (format != null) return boundaries(text);
                        Thread.currentThread().interrupt();
                        return "Generated text";
                    }, ignored -> { }));
        } finally { Thread.interrupted(); }
        AtomicInteger beforeStart = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            failure(InterruptedException.class, () -> LocalSummaryWriter.summarize(SOURCE, GUIDANCE, SummaryStyle.AUTO,
                    (instruction, text, tokens, format, effort, phase) -> { beforeStart.incrementAndGet(); return "Unexpected"; }, ignored -> { }));
        } finally { Thread.interrupted(); }
        equal(0, beforeStart.get(), "already cancelled work makes no completion requests");
    }

    private static void plainTextAndMarkers() throws Exception {
        String marker = "[[check: practice **mettā** with patience]]";
        String input = "## Topic\n**Important detail**\n* Retain " + marker + "\n";
        String expected = "Topic\nImportant detail\n• Retain " + marker;
        equal(expected, LocalSummaryWriter.plainText(input), "plain-text cleanup preserves exact uncertainty source quotes");
        equal("Already plain — mettā 🙏.", LocalSummaryWriter.plainText(" Already plain — mettā 🙏. \n"), "plain Unicode content unchanged");
        String result = LocalSummaryWriter.summarize(FIRST, "", SummaryStyle.AUTO,
                (instruction, text, tokens, format, effort, phase) -> format != null ? "{\"starts\":[]}"
                        : input, ignored -> { });
        equal(expected, result, "returned output uses marker-safe cleanup");
    }

    private static String boundaries(String numberedSource) {
        Map<?, ?> object = (Map<?, ?>) Json.parse(numberedSource);
        List<?> spans = (List<?>) object.get("spans");
        StringBuilder full = new StringBuilder();
        for (Object item : spans) full.append(((Map<?, ?>) item).get("text"));
        List<Object> starts = new ArrayList<>();
        for (String anchor : List.of("Second question asks how breath counting", "Third question asks about daily practice")) {
            int position = full.indexOf(anchor);
            if (position < 0) continue;
            int offset = 0;
            for (Object item : spans) {
                Map<?, ?> span = (Map<?, ?>) item;
                String text = (String) span.get("text");
                if (position >= offset && position < offset + text.length()) {
                    starts.add(Map.of("id", span.get("id"), "anchor", anchor)); break;
                }
                offset += text.length();
            }
        }
        return Json.stringify(Map.of("starts", starts));
    }

    private record Call(String instruction, String source, int tokens, Map<String, Object> format, String effort, String phase) { }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static <T extends Exception> T failure(Class<T> expected, Action action) throws Exception {
        try { action.run(); } catch (Exception error) {
            check(expected.isInstance(error), "expected " + expected.getSimpleName() + ", got " + error);
            return expected.cast(error);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + "; partial output must not be success");
    }
    private static void equal(Object expected, Object actual, String message) { check(java.util.Objects.equals(expected, actual), message + ": got " + actual); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
