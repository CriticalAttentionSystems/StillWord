/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.util.regex.Pattern;

/**
 *
 * @author veronique
 */

/** Video format, separate from the user's requested summary length. */
public enum SummarySource {
    LECTURE("""
            Source video type: lecture or talk, not a Q&A video.
            For summaries, organize the substantive teaching by topic in explanatory prose.
            Questions used by the speaker to explain an idea are rhetorical teaching devices,
            not audience Q&A. Do not turn them into a question-and-answer structure or focus
            the summary on questions. Cover the whole teaching, including examples and practical
            instructions. Even the Detailed style means a detailed topical summary for this video.
            For boundary detection, find substantive topic changes, not individual rhetorical questions.
            """),
    QA("""
            Source video type: Q&A video.
            For summaries, keep actual audience questions associated with the answers actually given,
            in source order, with the amount of detail requested by the selected summary style.
            Preserve substantive teaching before or between exchanges. A question's premise is not
            the speaker's claim. Preserve corrections, redirects, lack of understanding or knowledge,
            and unanswered questions. If a question is reread or corrected, use the corrected reading.
            Follow the entire answer, including its practical steps, examples, conditions, and lists.
            Do not invent questions or answers. For boundary detection, keep restatements and
            clarifications attached to the same question and its answer.
            """),
    UNKNOWN("""
            Source video type: unknown (no video title available).
            For summaries, default to topical explanatory prose. Do not assume that questions in
            the transcript make it a Q&A video; rhetorical questions remain part of the teaching.
            Use Q&A organization only when the source clearly establishes an actual audience
            question-and-answer session. Never invent questions or answers. Cover all substantive
            content even when no questions are asked. For boundary detection, find substantive
            topic changes and keep rhetorical questions with the explanation they introduce.
            """);

    private static final Pattern QA_TITLE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:q\\s*(?:&|＆|\\+|/|and)\\s*a|q\\s*[.．]?\\s*a[.．]?|questions?\\s*(?:&|and)\\s*answers?)(?![\\p{L}\\p{N}])");
    private final String instructions;

    SummarySource(String instructions) { this.instructions = instructions; }

    /** Classifies metadata only; the raw, untrusted title never becomes a model instruction. */
    public static SummarySource fromTitle(String title) {
        if (title == null || title.isBlank()) return UNKNOWN;
        return QA_TITLE.matcher(title).find() ? QA : LECTURE;
    }

    String instructions() { return instructions; }
}
