/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

/**
 *
 * @author veronique
 */

/** The user's requested summary length and organization, independent of the AI provider. */
public enum SummaryStyle {
    AUTO("Auto", "Let the content determine the summary's length and organization.", """
            Choose the length automatically based on the source's breadth and complexity,
            without a fixed word or page limit. Be concise while covering the central message,
            distinct discussion topics, main supporting points, useful examples, practical
            guidance, and conclusions or unresolved questions where present.
            Preserve important qualifications and keep actual questions associated with their answers.
            Use fewer words for a short or repetitive source. Combine repeated ideas and avoid padding.
            Follow the source video type: topical paragraphs for lectures; actual questions and
            their answers for Q&A videos. Rhetorical questions do not make a lecture into Q&A.
            """),
    BRIEF("Brief", "A short overview of the main ideas and most useful takeaways.", """
            Write a short overview focused on the central message and most useful takeaways.
            Aim for a few compact paragraphs or a short plain-text list, with less detail than
            a full discussion summary. Combine related points and omit secondary examples,
            digressions, and repetition. Do not attempt to enumerate every question.
            Keep qualifications, conditions, uncertainty, and practical steps essential to
            understanding the main ideas; brevity must not change what the speaker actually said.
            Use the length needed for a clear short overview, without a fixed word or page limit.
            """),
    DETAILED_QA("Detailed", "Detailed teaching and examples, with questions and answers for Q&A videos.", """
            For a Q&A source, retain each distinct actual question and the answer actually given,
            in source order. Combine only repeated questions with the same substantive answer.
            Preserve distinctions, conditions, practical steps, named lists and their individual
            items or remedies, useful examples, clarifications, redirects, and uncertainty.
            State when a question was not answered or was only partly answered, while retaining
            the explanation the speaker did offer. Preserve teaching between questions in its
            relevant context. Give each answer enough space to be understood and used.
            For a lecture or talk, use a detailed topical summary even if its speaker asks rhetorical questions.
            Never invent questions or answers or turn a lecture into a fictional Q&A.
            Be detailed without repetition or padding; there is no fixed word or page limit.
            """);

    private final String label;
    private final String description;
    private final String finalInstructions;

    SummaryStyle(String label, String description, String finalInstructions) {
        this.label = label;
        this.description = description;
        this.finalInstructions = finalInstructions;
    }

    public String label() { return label; }
    public String description() { return description; }
    @Override public String toString() { return label; }

    String finalInstructions() {
        return "Selected summary style: " + label + ".\n" + finalInstructions;
    }

    String notesInstructions() {
        return "Selected summary style: " + label + ".\n"
                + "These are intermediate notes, not the final summary. Preserve all substantive source detail "
                + "and uncertainty now; apply the selected style's length and organization only when writing "
                + "the final summary. Do not discard an answer, condition, or correction to make these notes "
                + "fit a short final overview.\n";
    }
}
