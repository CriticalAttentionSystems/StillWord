/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @author veronique
 */

/** Standalone regression tests. Run with assertions enabled or normally; no test dependency is needed. */
public final class TranscriptServiceTest {
    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        urlValidation();
        captionParsing();
        blankDisplayLines();
        ranges();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) processIntegration();
        else System.out.println("POSIX fake-process tests skipped on Windows.");
        System.out.println("TranscriptServiceTest: " + checks + " checks passed.");
    }

    private static void urlValidation() throws Exception {
        for (String valid : List.of(URL, "youtu.be/dQw4w9WgXcQ?t=4", "http://m.youtube.com/watch?v=dQw4w9WgXcQ&list=abc",
                "https://www.youtube.com/shorts/dQw4w9WgXcQ", "https://youtube.com/live/dQw4w9WgXcQ?si=test",
                "https://www.youtube.com/embed/dQw4w9WgXcQ/", "https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
            equal(URL, TranscriptService.validateUrl(valid), "canonical URL");
        for (String invalid : List.of("", "--exec=oops", "https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ",
                "https://evil.test/?url=https://youtube.com/watch?v=dQw4w9WgXcQ", "file:///tmp/test",
                "https://youtube.com@evil.test/watch?v=dQw4w9WgXcQ", "https://me@youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtube.com:8080/watch?v=dQw4w9WgXcQ", "https://youtube.com/playlist?list=abc",
                "https://youtube.com/watch?v=short", "https://youtu.be/dQw4w9WgXcQ/extra",
                "https://youtube.com/watch?v=dQw4w9WgXcQ&v=aaaaaaaaaaa", "https://youtube.com/watch?v=$(touch-file)",
                "https://youtube.com/watch?v=dQw4w9WgXcQ%00"))
            fails(IllegalArgumentException.class, () -> TranscriptService.validateUrl(invalid), "invalid URL " + invalid);
        fails(IllegalArgumentException.class, () -> TranscriptService.validateUrl(null), "null URL");
    }

    private static void captionParsing() throws Exception {
        String metadata = """
                WEBVTT
                Kind: captions
                Language: en

                NOTE This is not spoken.
                extra notes

                STYLE
                ::cue { color: red; }

                REGION
                id:metadata

                intro-cue
                00:00:00.000 --> 00:00:01.000
                <v Bhante><i>Breathe</i> &amp; relax.<br/>It&#39;s peaceful &#x1F64F;.</v>

                2
                00:00:01.000 --> 00:00:02.000
                Kind: this is spoken text.
                108
                """;
        equal("Breathe & relax. It's peaceful 🙏. Kind: this is spoken text. 108", VttParser.parse(metadata), "metadata and entity removal");
        equal("", VttParser.parse("WEBVTT\n\nNOTE silence"), "no text");
        equal("", VttParser.parse(null), "null VTT");
        equal(List.of(), VttParser.parseWithCues(null).cues(), "null VTT has no timed spans");
        equal(List.of(), VttParser.parseWithCues("WEBVTT\n\nNOTE silence").cues(), "metadata has no timed spans");
        VttParser.Parsed metadataParsed = VttParser.parseWithCues(metadata);
        equal(2, metadataParsed.cues().size(), "cleaned metadata span count");
        cue(metadataParsed, 0, "Breathe & relax. It's peaceful 🙏.", 0.0, 1.0);
        cue(metadataParsed, 1, "Kind: this is spoken text. 108", 1.0, 2.0);
        equal(metadataParsed.text().indexOf(" Kind:"), metadataParsed.cues().get(0).endOffset(), "emoji offsets use Java UTF-16 coordinates");
        fails(UnsupportedOperationException.class, () -> metadataParsed.cues().clear(), "parsed cues immutable");
        String repetition = """
                WEBVTT

                00:00:00.000 --> 00:00:01.000
                Breathe in.

                00:00:01.000 --> 00:00:02.000
                Breathe in.

                00:00:06.000 --> 00:00:07.000
                Breathe in.
                """;
        equal("Breathe in. Breathe in.\n\nBreathe in.", VttParser.parse(repetition), "legitimate repeated sentences preserved");
        VttParser.Parsed repeated = VttParser.parseWithCues(repetition);
        equal(3, repeated.cues().size(), "repeated sentences retain separate source times");
        cue(repeated, 0, "Breathe in.", 0.0, 1.0);
        cue(repeated, 1, "Breathe in.", 1.0, 2.0);
        cue(repeated, 2, "Breathe in.", 6.0, 7.0);
        equal("\n\n", repeated.text().substring(repeated.cues().get(1).endOffset(), repeated.cues().get(2).startOffset()), "paragraph separators excluded from cue ranges");
        String rolling = """
                WEBVTT

                00:00:00.000 --> 00:00:02.000 align:start position:0%
                Bring<00:00:00.500><c> your</c>

                00:00:01.000 --> 00:00:03.000 align:start position:0%
                Bring your attention

                00:00:02.000 --> 00:00:04.000 align:start position:0%
                your attention to breath.

                00:00:04.000 --> 00:00:04.010 align:start position:0%
                your attention to breath.

                00:00:04.010 --> 00:00:05.000 align:start position:0%
                to breath. Relax.

                00:00:10.000 --> 00:00:12.000 align:start position:0%
                Bring your attention to breath.
                """;
        equal("Bring your attention to breath. Relax.\n\nBring your attention to breath.", VttParser.parse(rolling), "rolling overlap removed locally");
        VttParser.Parsed rolled = VttParser.parseWithCues(rolling);
        equal(5, rolled.cues().size(), "fully deduplicated rolling cue emits no timed span");
        cue(rolled, 0, "Bring your", 0.0, 2.0);
        cue(rolled, 1, "attention", 1.0, 3.0);
        cue(rolled, 2, "to breath.", 2.0, 4.0);
        cue(rolled, 3, "Relax.", 4.01, 5.0);
        cue(rolled, 4, "Bring your attention to breath.", 10.0, 12.0);
        equal(new VttParser.Cue(1.0, 3.0, 11, 20), rolled.cues().get(1), "deduplicated addition offset and source time");
        equal("A < B &unknown;", VttParser.parse("\uFEFFWEBVTT\r\n\r\n00:00.000 --> 00:01.000\r\nA &lt; B &unknown;\r\n"), "BOM, CRLF, short timestamps");
    }

    private static void cue(VttParser.Parsed parsed, int index, String text, double start, double end) {
        VttParser.Cue cue = parsed.cues().get(index);
        truth(cue.startOffset() >= 0 && cue.endOffset() > cue.startOffset() && cue.endOffset() <= parsed.text().length(), "cue offsets are valid and nonempty");
        equal(text, parsed.text().substring(cue.startOffset(), cue.endOffset()), "cue maps final cleaned text");
        equal(start, cue.startSeconds(), "cue source start");
        equal(end, cue.endSeconds(), "cue source end");
        if (index > 0) truth(parsed.cues().get(index - 1).endOffset() <= cue.startOffset(), "emitted cue offsets are ordered and disjoint");
    }

    private static void blankDisplayLines() throws Exception {
        // Captured shape from YouTube: a space-only first display line is followed by
        // speech that may never be repeated in a later rolling caption.
        String youtube = "WEBVTT\n\n"
                + "00:19:48.059 --> 00:19:52.310 align:start position:0%\n"
                + "interesting undigested food and so forth\nwe<00:19:48.960><c> can</c> at least imagine\n\n"
                + "00:19:52.310 --> 00:19:52.320 align:start position:0%\n \n \n\n"
                + "00:19:52.320 --> 00:19:57.490 align:start position:0%\n \n"
                + "and<00:19:53.280><c> as</c><00:19:54.059><c> they</c><00:19:54.480><c> are</c><00:19:54.660><c> functioning</c>\n\n"
                + "00:19:57.490 --> 00:19:57.500 align:start position:0%\n \n \n\n"
                + "00:19:57.500 --> 00:19:59.630 align:start position:0%\n \nwe<00:19:58.500><c> live</c>\n\n"
                + "00:19:59.630 --> 00:19:59.640 align:start position:0%\nwe live\n \n\n"
                + "00:19:59.640 --> 00:20:02.870 align:start position:0%\nwe live\nthe function means taking is changing\n";
        VttParser.Parsed recovered = VttParser.parseWithCues(youtube);
        equal("interesting undigested food and so forth we can at least imagine and as they are functioning we live the function means taking is changing",
                recovered.text(), "space-only display lines retain previously omitted speech");
        equal(4, recovered.cues().size(), "blank cues emit no spans and rolling repeats stay deduplicated");
        cue(recovered, 1, "and as they are functioning", 1192.32, 1197.49);
        cue(recovered, 2, "we live", 1197.5, 1199.63);
        cue(recovered, 3, "the function means taking is changing", 1199.64, 1202.87);
        VttParser.Parsed range = VttParser.parseWithCues(youtube, 1192.32, 1197.49);
        equal("and as they are functioning", range.text(), "range selects speech following blank display line");
        cue(range, 0, "and as they are functioning", 1192.32, 1197.49);

        String separators = "WEBVTT\n\n"
                + "00:00:00.000 --> 00:00:01.000\nFirst.\n \n"
                + "00:00:01.000 --> 00:00:02.000\nSecond.\n\t\n"
                + "third-cue\n00:00:02.000 --> 00:00:03.000\nThird.\n \t \n"
                + "NOTE not spoken\n00:09:00.000 --> 00:09:01.000\nAlso not spoken.\n \n"
                + "00:00:03.000 --> 00:00:04.000\nFourth.\n";
        VttParser.Parsed separated = VttParser.parseWithCues(separators);
        equal("First. Second. Third. Fourth.", separated.text(), "whitespace separators do not swallow following cue headers or metadata");
        equal(4, separated.cues().size(), "whitespace-separated cues retain individual timestamps");
        cue(separated, 2, "Third.", 2.0, 3.0);
        cue(separated, 3, "Fourth.", 3.0, 4.0);
        equal("First. Second.", VttParser.parse("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nFirst.\n"
                + "00:00:01.000 --> 00:00:02.000\nSecond.\n"), "adjacent cue headers are never spoken text");
        equal("First line second line third line", VttParser.parse("WEBVTT\n\n"
                + "00:00:00.000 --> 00:00:01.000\n \t \nFirst line\n \nsecond line\n\t\nthird line\n \n"),
                "leading internal and trailing whitespace display lines retain all text");
        String clearedDisplay = "WEBVTT\n\n"
                + "00:00:00.000 --> 00:00:02.000 align:start position:0%\nBreathe gently.\n\n"
                + "00:00:02.000 --> 00:00:02.010 align:start position:0%\n \n \n\n"
                + "00:00:02.010 --> 00:00:04.000 align:start position:0%\n \nBreathe gently.\n\n"
                + "00:00:04.000 --> 00:00:04.010 align:start position:0%\nBreathe gently.\n \n\n";
        VttParser.Parsed repeatedAfterClear = VttParser.parseWithCues(clearedDisplay);
        equal("Breathe gently. Breathe gently.", repeatedAfterClear.text(), "new speech after a cleared display can repeat the preceding words");
        equal(2, repeatedAfterClear.cues().size(), "only the repeated rolling display is deduplicated");
        cue(repeatedAfterClear, 0, "Breathe gently.", 0.0, 2.0);
        cue(repeatedAfterClear, 1, "Breathe gently.", 2.01, 4.0);
    }

    private static String rangeFixture() {
        return """
                WEBVTT

                00:00:00.000 --> 00:00:02.000
                First thought.

                00:00:02.000 --> 00:00:04.000
                Middle thought.

                00:00:04.000 --> 00:00:06.000
                Final thought.
                """;
    }

    private static void ranges() throws Exception {
        equal("Middle thought.", VttParser.parse(rangeFixture(), 2.0, 4.0), "exact start and end boundaries");
        equal("Middle thought.", VttParser.parse(rangeFixture(), 2.5, 3.5), "partial cue retained");
        equal("First thought.", VttParser.parse(rangeFixture(), null, 2.0), "end only");
        equal("Final thought.", VttParser.parse(rangeFixture(), 4.0, null), "start only");
        equal("", VttParser.parse(rangeFixture(), 6.0, null), "no overlapping cue");
        equal("First thought. Middle thought. Final thought.", VttParser.parse(rangeFixture(), null, null), "all cues");
        VttParser.Parsed selected = VttParser.parseWithCues(rangeFixture(), 2.5, 3.5);
        equal("Middle thought.", selected.text(), "timed parser retains partial overlapping cue");
        equal(List.of(new VttParser.Cue(2.0, 4.0, 0, 15)), selected.cues(), "range offsets rebase to selected text but retain original cue times");
        equal(List.of(), VttParser.parseWithCues(rangeFixture(), 6.0, null).cues(), "empty selected range has no timed spans");
        fails(IllegalArgumentException.class, () -> VttParser.parse(rangeFixture(), -1.0, null), "negative start");
        fails(IllegalArgumentException.class, () -> VttParser.parse(rangeFixture(), 4.0, 2.0), "backward range");
        fails(IllegalArgumentException.class, () -> VttParser.parse(rangeFixture(), 2.0, 2.0), "empty range");
        fails(IllegalArgumentException.class, () -> VttParser.parse(rangeFixture(), Double.NaN, null), "NaN start");
        fails(IllegalArgumentException.class, () -> VttParser.parse(rangeFixture(), null, Double.POSITIVE_INFINITY), "infinite end");
        fails(IllegalArgumentException.class, () -> VttParser.parse(rangeFixture(), null, 0.0), "zero end");
    }

    private static void processIntegration() throws Exception {
        Path fixture = Files.createTempDirectory("bhavana fake yt-dlp ");
        try {
            Path callLog = fixture.resolve("calls.txt");
            Path argv = fixture.resolve("argv.txt");
            String preamble = "printf '%s\\n' \"$PWD\" >> " + quote(callLog) + "\nprintf '%s\\n' \"$@\" > " + quote(argv) + "\n";
            Path success = fake(fixture, "manual-tool", preamble
                    + "printf 'A gentle lesson\\n' > title.txt\ncat > captions.en.vtt <<'VTT'\n" + rangeFixture() + "VTT\n"
                    + "cat > captions.en-US.vtt <<'VTT'\nWEBVTT\n\n00:00.000 --> 00:01.000\nWrong language preference.\nVTT\n");
            List<String> progress = new ArrayList<>();
            TranscriptService.Result result = new TranscriptService().fetch(URL, success.toString(), "en.*", progress::add);
            equal("A gentle lesson", result.title(), "downloaded title");
            equal("First thought. Middle thought. Final thought.", result.text(), "downloaded and parsed text");
            equal("en", result.language(), "preferred English tag");
            equal("Publisher captions", result.sourceDescription(), "manual captions identify their publisher source");
            equal(VttParser.parseWithCues(rangeFixture()).cues(), result.cues(), "service returns matching caption spans");
            TranscriptService.Result downloaded = result;
            fails(UnsupportedOperationException.class, () -> downloaded.cues().clear(), "service cues immutable");
            equal(List.of(), new TranscriptService.Result("pasted", "text", "en").cues(), "backward-compatible result constructor");
            equal("Captions", new TranscriptService.Result("pasted", "text", "en").sourceDescription(), "three-argument result keeps a neutral caption source");
            equal("Captions", new TranscriptService.Result("pasted", "text", "en", List.of()).sourceDescription(), "four-argument result keeps a neutral caption source");
            equal("Whisper · Free local", new TranscriptService.Result("audio", "text", "en", List.of(), "Whisper · Free local").sourceDescription(), "result can identify a distinct transcription source");
            equal(1, Files.readAllLines(callLog).size(), "manual subtitles prevent automatic fallback");
            truth(progress.size() >= 2, "progress reported");
            List<String> arguments = Files.readAllLines(argv);
            truth(arguments.containsAll(List.of("--ignore-config", "--skip-download", "--no-playlist", "--write-subs", "--no-write-auto-subs")), "safe yt-dlp options");
            equal(URL, arguments.get(arguments.size() - 1), "URL remains one final argument");
            equal("--", arguments.get(arguments.size() - 2), "option terminator");
            assertCleaned(callLog);

            result = new TranscriptService().fetch(URL, success.toString(), "en.*", 2.0, 4.0, null);
            equal("Middle thought.", result.text(), "service range filtering");
            equal("Publisher captions", result.sourceDescription(), "range selection preserves caption provenance");
            equal(List.of(new VttParser.Cue(2.0, 4.0, 0, 15)), result.cues(), "service returns selected cue time and offsets");
            Throwable emptyRange = fails(IOException.class, () -> new TranscriptService().fetch(URL, success.toString(), "en.*", 9.0, 10.0, null), "range outside video");
            truth(emptyRange.getMessage().contains("selected time range"), "actionable range error");

            Files.delete(callLog);
            Path automatic = fake(fixture, "automatic-tool", preamble
                    + "case \" $* \" in\n*' --write-auto-subs '*)\ncat > captions.en-orig.vtt <<'VTT'\n" + rangeFixture() + "VTT\n;;\nesac\n");
            result = new TranscriptService().fetch(URL, automatic.toString(), "", null);
            equal("en-orig", result.language(), "automatic fallback");
            equal("Automatic captions", result.sourceDescription(), "automatic fallback is visibly distinguished from publisher captions");
            equal(2, Files.readAllLines(callLog).size(), "two extraction passes");
            assertCleaned(callLog);

            Path empty = fake(fixture, "empty-tool", preamble + "exit 0\n");
            Throwable noCaptions = fails(IOException.class, () -> new TranscriptService().fetch(URL, empty.toString(), "en.*", null), "no captions");
            truth(noCaptions.getMessage().contains("no downloadable English captions"), "actionable missing captions error");
            truth(noCaptions.getMessage().contains("Whisper"), "missing captions point to the audio transcription alternative");
            Path denied = fake(fixture, "denied-tool", preamble + "printf 'ERROR: Sign in to confirm your age.\\n' >&2\nexit 1\n");
            Throwable restriction = fails(IOException.class, () -> new TranscriptService().fetch(URL, denied.toString(), "en.*", null), "restricted video");
            truth(restriction.getMessage().contains("requires sign-in"), "actionable restriction error");

            Path sleepy = fake(fixture, "slow-tool", preamble + "exec sleep 30\n");
            long started = System.nanoTime();
            Throwable timeout = fails(IOException.class, () -> new TranscriptService(Duration.ofMillis(150)).fetch(URL, sleepy.toString(), "en.*", null), "bounded timeout");
            truth(timeout.getMessage().contains("timed out"), "timeout message");
            truth(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "timeout does not hang");

            AtomicReference<Throwable> cancellation = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { new TranscriptService().fetch(URL, sleepy.toString(), "en.*", null); }
                catch (Throwable exception) { cancellation.set(exception); }
            });
            worker.start();
            Thread.sleep(150);
            worker.interrupt();
            worker.join(4_000);
            truth(!worker.isAlive(), "cancellation terminates worker");
            truth(cancellation.get() instanceof InterruptedException, "cancellation propagates interrupt");
            assertCleaned(callLog);
            fails(IOException.class, () -> new TranscriptService().fetch(URL, fixture.resolve("not-here").toString(), "en.*", null), "missing executable");
            fails(IllegalArgumentException.class, () -> new TranscriptService().fetch(URL, success.toString(), "fr.*", null), "only English requested");
            bundledExecutableDiscovery(fixture, success);
            bundledJavaScriptRuntime(fixture, success);
        } finally {
            try (var files = Files.walk(fixture)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void bundledExecutableDiscovery(Path fixture, Path explicit) throws Exception {
        Path project = Files.createDirectories(fixture.resolve("app project"));
        Path toolFolder = Files.createDirectories(project.resolve("Code/tools/yt-dlp"));
        Path bundled = fake(toolFolder, "yt-dlp_macos", "exit 0\n");
        String originalDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", project.toString());
            equal(bundled.toString(), TranscriptService.resolveExecutable(null), "project root discovers its bundled yt-dlp before PATH");
            System.setProperty("user.dir", project.resolve("Code/build/preview").toString());
            equal(bundled.toString(), TranscriptService.resolveExecutable(""), "nested preview launch discovers bundled yt-dlp in a project ancestor");
            equal(explicit.toString(), TranscriptService.resolveExecutable(explicit.toString()), "explicit executable overrides bundled discovery");
            fails(IOException.class, () -> TranscriptService.resolveExecutable(project.resolve("missing-tool").toString()),
                    "invalid explicit executable is not silently replaced by a discovered tool");
        } finally {
            if (originalDirectory == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", originalDirectory);
        }
    }

    private static void bundledJavaScriptRuntime(Path fixture, Path customExecutable) throws Exception {
        Path app = Files.createDirectories(fixture.resolve("Installed Apps/StillWord.app/Contents/app"));
        Path tools = Files.createDirectories(app.resolve("tools"));
        Path downloaderDirectory = Files.createDirectories(tools.resolve("yt-dlp"));
        Path argumentsFile = fixture.resolve("bundled-arguments.txt");
        Path downloader = fake(downloaderDirectory, "yt-dlp_macos",
                "printf '%s\\n' \"$@\" > " + quote(argumentsFile) + "\n"
                + "cat > captions.en.vtt <<'VTT'\n" + rangeFixture() + "VTT\n");
        List<String> arguments = new ArrayList<>(List.of(downloader.toString()));
        TranscriptService.addBundledJavaScriptRuntime(arguments, downloader.toString());
        equal(List.of(downloader.toString()), arguments, "missing bundled runtimes preserve normal runtime discovery");

        Path denoDirectory = Files.createDirectories(tools.resolve("deno"));
        Path deno = fake(denoDirectory, "deno", "exit 0\n");
        TranscriptService.addBundledJavaScriptRuntime(arguments, downloader.toString());
        equal(List.of(downloader.toString(), "--no-js-runtimes", "--js-runtimes", "deno:" + deno), arguments,
                "bundled Deno path with spaces remains one argument");

        Path nodeDirectory = Files.createDirectories(tools.resolve("node"));
        Path node = fake(nodeDirectory, "node", "exit 0\n");
        List<String> nodeArguments = new ArrayList<>(List.of(downloader.toString()));
        TranscriptService.addBundledJavaScriptRuntime(nodeArguments, downloader.toString());
        equal(List.of(downloader.toString(), "--no-js-runtimes", "--js-runtimes", "node:" + node), nodeArguments,
                "bundled Node is preferred with its path preserved as one argument");

        List<String> customArguments = new ArrayList<>(List.of(customExecutable.toString()));
        TranscriptService.addBundledJavaScriptRuntime(customArguments, customExecutable.toString());
        equal(List.of(customExecutable.toString()), customArguments, "custom executable keeps its own runtime discovery");

        String originalDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", app.toString());
            equal(downloader.toString(), TranscriptService.resolveExecutable(null), "installed app discovers its own downloader");
            equal("First thought. Middle thought. Final thought.",
                    new TranscriptService().fetch(URL, null, "en.*", null).text(),
                    "installed layout extracts captions without external downloader dependencies");
            List<String> actual = Files.readAllLines(argumentsFile);
            int optionIndex = actual.indexOf("--js-runtimes");
            truth(optionIndex >= 0, "caption subprocess receives explicit bundled runtime option");
            equal("node:" + node, actual.get(optionIndex + 1), "caption subprocess receives full Node path");
            truth(actual.indexOf("--no-js-runtimes") >= 0 && actual.indexOf("--no-js-runtimes") < optionIndex,
                    "caption subprocess clears default runtime priority before enabling bundled Node");
            truth(optionIndex < actual.indexOf("--"), "runtime option precedes video option terminator");
            equal(URL, actual.get(actual.size() - 1), "bundled runtime leaves source URL unchanged");
        } finally {
            if (originalDirectory == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", originalDirectory);
        }
        Files.setPosixFilePermissions(node, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        List<String> fallbackArguments = new ArrayList<>(List.of(downloader.toString()));
        TranscriptService.addBundledJavaScriptRuntime(fallbackArguments, downloader.toString());
        equal(List.of(downloader.toString(), "--no-js-runtimes", "--js-runtimes", "deno:" + deno), fallbackArguments,
                "unrunnable Node falls back to bundled Deno");
        Files.setPosixFilePermissions(deno, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        List<String> unavailableArguments = new ArrayList<>(List.of(downloader.toString()));
        TranscriptService.addBundledJavaScriptRuntime(unavailableArguments, downloader.toString());
        equal(List.of(downloader.toString()), unavailableArguments, "unrunnable bundled runtimes preserve PATH fallback");
    }

    private static Path fake(Path fixture, String name, String body) throws IOException {
        Path script = fixture.resolve(name);
        Files.writeString(script, "#!/bin/sh\nset -eu\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(script, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    private static String quote(Path path) { return "'" + path.toString().replace("'", "'\\''") + "'"; }

    private static void assertCleaned(Path log) throws IOException {
        for (String directory : Files.readAllLines(log)) truth(!Files.exists(Path.of(directory)), "temporary caption folder removed");
    }

    private static void equal(Object expected, Object actual, String label) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(label + "\nExpected: " + expected + "\nActual: " + actual);
    }

    private static void truth(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static Throwable fails(Class<? extends Throwable> type, CheckedRunnable operation, String label) throws Exception {
        checks++;
        try { operation.run(); }
        catch (Throwable exception) {
            if (type.isInstance(exception)) return exception;
            throw new AssertionError(label + ": unexpected exception", exception);
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }

    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
}
