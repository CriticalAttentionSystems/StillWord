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

/** Source type comes from video metadata, not from questions occurring inside a lecture. */
public final class SummarySourceTest {
    private static int checks;

    public static void main(String[] arguments) {
        for (String title : List.of(
                "Anapanasati sutta - part 2 - Q&A - 03/26/23",
                "Part 2: Q & A",
                "Part 3 — Q and A",
                "Breathing meditation Q+A",
                "Q/A: breathing practice",
                "Questions and Answers about the breath",
                "Part 4: QA",
                "qa, March session",
                "Evening q&a session",
                "QUESTIONS AND ANSWERS")) {
            equal(SummarySource.QA, SummarySource.fromTitle(title), "explicit Q&A title: " + title);
        }
        for (String title : List.of(
                "Anapanasati sutta - part 1 - 03/25/23",
                "Why practice mindfulness of breathing?",
                "What is the whole body? A lecture on breathing",
                "A question for reflection: how do we pay attention?",
                "Breathing meditation — main talk",
                "FAQS and the quiet mind",
                "Learning in Qatar",
                "AQA education talk",
                "The QA2 lecture")) {
            equal(SummarySource.LECTURE, SummarySource.fromTitle(title), "a lecture title is not inferred to be Q&A: " + title);
        }
        for (String title : new String[] {null, "", " \n\t "}) {
            equal(SummarySource.UNKNOWN, SummarySource.fromTitle(title), "absent title has an explicit unknown source type");
        }
        for (SummarySource mode : SummarySource.values()) {
            check(!mode.instructions().isBlank(), mode + " has an explicit source-type contract");
        }
        check(!SummarySource.LECTURE.instructions().equals(SummarySource.QA.instructions()), "lecture and Q&A contracts remain distinct");
        check(!SummarySource.UNKNOWN.instructions().equals(SummarySource.QA.instructions()), "unknown source never defaults to the Q&A contract");
        System.out.println("SummarySourceTest: " + checks + " checks passed.");
    }

    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": got " + actual);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
