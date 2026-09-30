/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 *
 * @author veronique
 */

/** Tests annotation removal without losing original words or Swing-compatible selection offsets. */
public final class SummaryReviewTest {
    private static int checks;

    public static void main(String[] arguments) {
        equal(new SummaryReview.Parsed("", List.of()), SummaryReview.parse(null));
        equal(new SummaryReview.Parsed("", List.of()), SummaryReview.parse(""));
        String plain = "A plain summary — mettā 🪷\nKeep its original text.";
        equal(new SummaryReview.Parsed(plain, List.of()), SummaryReview.parse(plain));

        String prefix = "Before 🪷 ";
        String phrase = "ānāpāna strange sound";
        SummaryReview.Parsed parsed = SummaryReview.parse(prefix + "[[check: " + phrase + "]] after.");
        equal(prefix + phrase + " after.", parsed.text());
        equal(List.of(new SummaryReview.Mark(phrase, prefix.length(), prefix.length() + phrase.length())), parsed.marks());
        validSpans(parsed);

        String first = "mettā 🪷 unsure term", second = "another unclear Pāli phrase";
        parsed = SummaryReview.parse("[[check:  " + first + "  ]]\nNext: [[check:" + second + "]]. Again [[check: " + first + "]]");
        String expected = first + "\nNext: " + second + ". Again " + first;
        equal(expected, parsed.text());
        equal(3, parsed.marks().size());
        equal(0, parsed.marks().get(0).startOffset());
        equal(expected.indexOf(second), parsed.marks().get(1).startOffset());
        equal(expected.lastIndexOf(first), parsed.marks().get(2).startOffset());
        validSpans(parsed);

        parsed = SummaryReview.parse("[[check: one source phrase]][[check: second source phrase]]");
        equal("one source phrasesecond source phrase", parsed.text());
        equal(parsed.marks().get(0).endOffset(), parsed.marks().get(1).startOffset());
        validSpans(parsed);

        parsed = SummaryReview.parse("[[check: a [misheard] Pali term]] and [[check: e\u0301 spaced\nsource phrase]]");
        equal("a [misheard] Pali term and e\u0301 spaced\nsource phrase", parsed.text());
        validSpans(parsed);

        for (String malformed : List.of("[[check:]]", "[[check: \n\t\u2003]]", "[[CHECK: three source words]]",
                "[[review: three source words]]", "[check: three source words]", "[[check three source words]]",
                "[[check: unfinished phrase", "plain text with ]] only",
                "[[check: outer [[check: inner source phrase]] remains]]",
                "[[other: nested [[check: inner source phrase]] remains]]")) {
            equal(new SummaryReview.Parsed(malformed, List.of()), SummaryReview.parse(malformed));
        }
        String malformedPrefix = "[[check: ]] and [[other: keep this]] then ";
        parsed = SummaryReview.parse(malformedPrefix + "[[check: this source phrase]]");
        equal(malformedPrefix + "this source phrase", parsed.text());
        equal(malformedPrefix.length(), parsed.marks().get(0).startOffset());
        validSpans(parsed);

        List<SummaryReview.Mark> mutable = new ArrayList<>();
        SummaryReview.Parsed copied = new SummaryReview.Parsed("text", mutable);
        mutable.add(new SummaryReview.Mark("text", 0, 4));
        equal(0, copied.marks().size());
        try { parsed.marks().clear(); throw new AssertionError("Review marks must be immutable"); }
        catch (UnsupportedOperationException expectedFailure) { checks++; }
        System.out.println("SummaryReviewTest: " + checks + " checks passed");
    }

    private static void validSpans(SummaryReview.Parsed parsed) {
        int previousEnd = 0;
        for (SummaryReview.Mark mark : parsed.marks()) {
            if (mark.startOffset() < previousEnd || mark.endOffset() < mark.startOffset())
                throw new AssertionError("Invalid or overlapping review spans");
            equal(mark.phrase(), parsed.text().substring(mark.startOffset(), mark.endOffset()));
            previousEnd = mark.endOffset();
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }
}
