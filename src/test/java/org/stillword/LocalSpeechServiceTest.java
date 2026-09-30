/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 *
 * @author veronique
 */

/** Isolated native-process contract tests: no network, downloaded model, or macOS audio hardware. */
public final class LocalSpeechServiceTest {
    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String VTT = """
            WEBVTT

            00:00:00.000 --> 00:00:02.000
            Welcome to this discussion.

            00:01:00.125 --> 00:01:02.000
            Bring your attention to the breath.

            00:01:02.000 --> 00:01:04.500
            Notice how the mind responds.
            """;
    private static int checks;

    public static void main(String[] arguments) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            System.out.println("LocalSpeechServiceTest: POSIX process fixtures skipped on Windows.");
            return;
        }
        successfulTranscription();
        exactAudioSelection();
        validationAndReadiness();
        failedStages();
        cancellationAndTimeout();
        setupLifecycle();
        packagedRuntimeLocations();
        packagedRuntimeSetup();
        System.out.println("LocalSpeechServiceTest: " + checks + " checks passed.");
    }

    private static void successfulTranscription() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> progress = new ArrayList<>();
            var result = fixture.service().transcribe("youtu.be/dQw4w9WgXcQ?t=60", fixture.download.toString(), progress::add);
            equal("Dharma ' talk $(not-a-command)", result.title(), "title preserved as data");
            equal(VttParser.parse(VTT), result.text(), "complete transcript text");
            equal("en", result.language(), "recognition language");
            equal("Whisper audio", result.sourceDescription(), "audio source provenance");
            equal(3, result.cues().size(), "all original cue positions retained");
            equal(60.125, result.cues().get(1).startSeconds(), "original cue time");
            equal("Bring your attention to the breath.", result.text().substring(result.cues().get(1).startOffset(),
                    result.cues().get(1).endOffset()), "offsets refer to cleaned text");
            check(progress.stream().anyMatch(value -> value.contains("Whisper")), "recognition progress published");
            List<String> download = fixture.arguments("download");
            equal(URL, download.get(download.size() - 1), "canonical URL passed as one argument");
            equal("--", download.get(download.size() - 2), "URL option boundary");
            equal("bestaudio[ext=m4a][acodec^=mp4a]", after(download, "-f"), "AAC-only audio selection");
            equal("never", after(download, "--fixup"), "no ffmpeg postprocessing");
            check(download.contains("--ignore-config") && download.contains("--no-plugin-dirs"), "untrusted downloader config disabled");
            equal("!is_live", after(download, "--match-filters"), "unbounded live streams excluded");
            List<String> converter = fixture.arguments("convert");
            equal(List.of("-f", "WAVE", "-d", "LEI16@16000", "-c", "1"), converter.subList(0, 6), "16 kHz mono PCM conversion");
            List<String> whisper = fixture.arguments("whisper");
            equal(fixture.model.toString(), after(whisper, "-m"), "model path with spaces is one argument");
            check(whisper.contains("-ovtt") && whisper.contains("-pp"), "timed output and progress requested");
            equal("en", after(whisper, "-l"), "English recognition");
            check(!whisper.contains("-ot") && !whisper.contains("-d"), "full video has no range flags");
            fixture.assertCleaned();

            fails(IOException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), 100.0, null, null), "range beyond media fails before recognition");
            fixture.assertCleaned();

            fixture.recognizer("""
                    WEBVTT

                    00:00:00.000 --> 00:00:01.875
                    Bring your attention to the breath.

                    00:00:01.875 --> 00:00:01.875
                    A brief word.

                    00:00:01.875 --> 00:00:14.000
                    Notice how the mind responds.

                    00:00:04.375 --> 00:00:06.000
                    Closing announcement outside selection.
                    """);
            var ranged = fixture.service().transcribe(URL, fixture.download.toString(), 60.125, 64.5, null);
            equal("Bring your attention to the breath. A brief word. Notice how the mind responds.", ranged.text(), "selected time range only");
            equal(3, ranged.cues().size(), "selected timed spans preserve instantaneous words");
            equal(60.125, ranged.cues().get(0).startSeconds(), "range start not rebased");
            equal(62.0, ranged.cues().get(1).startSeconds(), "instantaneous cue keeps reported start");
            equal(62.0, ranged.cues().get(1).endSeconds(), "instantaneous cue has no invented length");
            equal(64.5, ranged.cues().get(2).endSeconds(), "range end not rebased");
            check(!fixture.arguments("whisper").contains("-ot") && !fixture.arguments("whisper").contains("-d"), "recognizer receives only physically clipped audio");
            equal("selected.wav", Path.of(after(fixture.arguments("whisper"), "-f")).getFileName().toString(), "recognizer uses selected audio");
            try (AudioInputStream selected = AudioSystem.getAudioInputStream(fixture.root.resolve("recognized input.wav").toFile())) {
                equal(70_000L, selected.getFrameLength(), "selected audio has exact 4.375 second frame count");
            }
            fixture.assertCleaned();

            fixture.recognizer(VTT);
            fixture.service().transcribe(URL, fixture.download.toString(), null, 2.0, null);
            try (AudioInputStream selected = AudioSystem.getAudioInputStream(fixture.root.resolve("recognized input.wav").toFile())) {
                equal(32_000L, selected.getFrameLength(), "end-only selection starts at zero");
            }
            check(!fixture.arguments("whisper").contains("-ot"), "zero offset flag omitted");
            fixture.assertCleaned();
        }
    }

    private static void exactAudioSelection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path selected = fixture.root.resolve("selected test.wav");
            var clip = LocalSpeechService.clipWav(fixture.wav, selected, 0.1250001, 0.2250626);
            equal(1600L, clip.frameCount(), "fractional boundaries round inward to whole frames");
            equal(2001 / 16_000.0, clip.startSeconds(), "reported origin matches first included frame");
            equal(3601 / 16_000.0, clip.endSeconds(), "reported end matches exclusive final frame");
            try (AudioInputStream input = AudioSystem.getAudioInputStream(selected.toFile())) {
                byte[] audio = input.readAllBytes();
                equal(3200, audio.length, "exact PCM byte count");
                equal(2001, (audio[0] & 255) | ((audio[1] & 255) << 8), "first source sample copied");
                equal(3600, (audio[audio.length - 2] & 255) | ((audio[audio.length - 1] & 255) << 8), "last included sample precedes exclusive end");
            }
            var tail = LocalSpeechService.clipWav(fixture.wav, selected, 64.5, null);
            equal(8000L, tail.frameCount(), "start-only selection ends at actual audio duration");
            equal(65.0, tail.endSeconds(), "actual source end reported");
            var capped = LocalSpeechService.clipWav(fixture.wav, selected, 64.5, 100.0);
            equal(65.0, capped.endSeconds(), "requested end beyond source clamped");
            var immediate = LocalSpeechService.parseClippedVtt("WEBVTT\n\n00:00:00.000 --> 00:00:00.000\nA word.\n\n00:00:00.500 --> 00:00:00.500\nOutside.\n", tail);
            equal("A word.", immediate.text(), "zero-length first cue retained and exact end boundary excluded");
            equal(64.5, immediate.cues().get(0).startSeconds(), "zero-length first cue maps to selection origin");
            fails(IOException.class, () -> LocalSpeechService.clipWav(fixture.wav, selected, 100.0, null), "selection beyond audio rejected");
            check(!Files.exists(selected), "failed clipping removes partial output");
            try {
                Thread.currentThread().interrupt();
                fails(InterruptedException.class, () -> LocalSpeechService.clipWav(fixture.wav, selected, 0.0, 1.0), "audio clipping is cancellable");
            } finally { Thread.interrupted(); }
            check(!Files.exists(selected), "cancelled clipping leaves no audio output");
        }
    }

    private static void validationAndReadiness() throws Exception {
        try (Fixture fixture = new Fixture()) {
            check(fixture.service().isReady(), "complete local runtime ready");
            fails(IllegalArgumentException.class, () -> fixture.service().transcribe("https://evil.test/a", fixture.download.toString(), null), "unsupported URL rejected");
            fails(IllegalArgumentException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), 10.0, 9.0, null), "reversed range rejected");
            fails(IllegalArgumentException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), Double.NaN, null, null), "NaN range rejected");
            check(!Files.exists(fixture.root.resolve("download.args")), "validation precedes download");
            Files.writeString(fixture.model, "");
            check(!fixture.service().isReady(), "empty model not ready");
            fails(IOException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), null), "missing model explains setup");
            Files.writeString(fixture.model, "fake model");
            Files.delete(fixture.convert);
            check(fixture.service().status().message().contains("afconvert"), "converter availability diagnosed");
        }
    }

    private static void failedStages() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.script(fixture.download, "download", "printf 'ERROR: Requested format is not available\\n' >&2\nexit 1\n");
            Exception error = fails(IOException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), null), "download failure surfaced");
            check(error.getMessage().contains("AAC/m4a"), "format failure useful message");
            check(!Files.exists(fixture.root.resolve("convert.args")), "failed download prevents conversion");
            fixture.assertCleaned();
        }
        try (Fixture fixture = new Fixture()) {
            fixture.script(fixture.convert, "convert", "echo 'damaged audio' >&2\nexit 2\n");
            Exception error = fails(IOException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), null), "conversion failure surfaced");
            check(error.getMessage().contains("damaged audio"), "conversion diagnostics included");
            check(!Files.exists(fixture.root.resolve("whisper.args")), "failed conversion prevents recognition");
            fixture.assertCleaned();
        }
        try (Fixture fixture = new Fixture()) {
            fixture.script(fixture.whisper, "whisper", "i=0\nwhile [ \"$i\" -lt 3000 ]; do printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\\n'; i=$((i+1)); done\necho 'final recognition failure'\nexit 3\n");
            Exception error = fails(IOException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), null), "recognizer failure surfaced");
            check(error.getMessage().length() < 3300, "diagnostic output bounded");
            check(error.getMessage().contains("final recognition failure"), "last diagnostic retained");
            fixture.assertCleaned();
        }
        try (Fixture fixture = new Fixture()) {
            fixture.script(fixture.whisper, "whisper", "exit 0\n");
            fails(IOException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), null), "missing VTT rejected even with success exit");
            fixture.assertCleaned();
            fixture.recognizer("WEBVTT\n\n");
            Exception error = fails(IOException.class, () -> fixture.service().transcribe(URL, fixture.download.toString(), null), "silence rejected");
            check(error.getMessage().contains("No readable speech"), "silence useful message");
            fixture.assertCleaned();
        }
    }

    private static void cancellationAndTimeout() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.script(fixture.whisper, "whisper", "sleep 30 &\nprintf '%s' \"$!\" > " + quote(fixture.root.resolve("child.pid")) + "\nwait\n");
            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { fixture.service().transcribe(URL, fixture.download.toString(), null); }
                catch (Throwable error) { caught.set(error); }
            });
            worker.start();
            awaitFile(fixture.root.resolve("child.pid"));
            long child = Long.parseLong(Files.readString(fixture.root.resolve("child.pid")));
            boolean canInspectChildren = ProcessHandle.current().descendants().anyMatch(handle -> handle.pid() == child);
            worker.interrupt(); worker.join(4000);
            check(!worker.isAlive(), "cancelled transcription worker stops promptly");
            check(caught.get() instanceof InterruptedException, "cancellation remains interruption");
            if (canInspectChildren) check(ProcessHandle.of(child).map(handle -> !handle.isAlive()).orElse(true), "recognizer child stopped");
            else {
                // Restricted macOS test sandboxes may deny process-tree inspection; the desktop app is unrestricted.
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
                System.out.println("Child-tree assertion unavailable in this sandbox; verify this test outside the sandbox.");
            }
            fixture.assertCleaned();
        }
        try (Fixture fixture = new Fixture()) {
            fixture.script(fixture.download, "download", "sleep 30\n");
            Exception error = fails(IOException.class, () -> fixture.service(Duration.ofSeconds(2)).transcribe(URL, fixture.download.toString(), null), "hung download times out");
            check(error.getMessage().contains("timed out"), "timeout explained");
            fixture.assertCleaned();
        }
    }

    private static void setupLifecycle() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.delete(fixture.whisper); Files.delete(fixture.model);
            Files.writeString(fixture.setup, "#!/bin/sh\nprintf '#!/bin/sh\\nexit 0\\n' > " + quote(fixture.whisper)
                    + "\nchmod 700 " + quote(fixture.whisper) + "\nprintf model > " + quote(fixture.model) + "\n");
            Files.writeString(fixture.setup, Files.readString(fixture.setup).replace("#!/bin/sh\n", "#!/bin/sh\nprintf '%s\\n' \"$@\" > " + quote(fixture.root.resolve("setup.args")) + "\n"));
            List<String> messages = new ArrayList<>();
            fixture.service().setup(messages::add);
            check(fixture.service().isReady(), "setup installs local fixture runtime");
            equal(List.of("--speech-dir", fixture.root.toString()), fixture.arguments("setup"), "setup receives the exact writable runtime directory");
            check(messages.get(messages.size() - 1).contains("ready"), "setup reports ready after validation");
        }
        try (Fixture fixture = new Fixture()) {
            Files.delete(fixture.model);
            Path marker = fixture.root.resolve("cleanup marker"), started = fixture.root.resolve("setup started");
            String handler = "kill \"$child\" 2>/dev/null || true; wait \"$child\" 2>/dev/null || true; printf cleaned > " + quote(marker) + "; exit 143";
            Files.writeString(fixture.setup, "#!/bin/sh\nchild=\ntrap " + quote(handler)
                    + " TERM\nsleep 30 &\nchild=$!\nprintf started > " + quote(started) + "\nwait \"$child\"\n");
            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { fixture.service().setup(null); } catch (Throwable error) { caught.set(error); }
            });
            worker.start(); awaitFile(started); worker.interrupt(); worker.join(4000);
            check(!worker.isAlive(), "setup cancellation returns promptly");
            check(caught.get() instanceof InterruptedException, "setup cancellation propagated");
            check(Files.exists(marker), "setup TERM trap gets cleanup opportunity");
        }
    }

    private static void packagedRuntimeLocations() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path bundle = fixture.root.resolve("StillWord.app");
            Path jar = bundle.resolve("Contents/app/StillWord.jar");
            Path home = fixture.root.resolve("User Home");
            Path project = fixture.root.resolve("Source Project");
            Files.createDirectories(project.resolve("src/main/java"));
            Files.writeString(project.resolve("build.sh"), "");
            Files.writeString(project.resolve("setup-local-speech.sh"), "");
            var installed = LocalSpeechService.runtimeFiles(jar, project, home, null);
            Path data = home.resolve("Library/Application Support/Stillword/speech");
            equal(data, installed.directory(), "installed app uses per-user speech storage even when launched from source tree");
            equal(data.resolve("bin/whisper-cli"), installed.whisper(), "installed runtime under writable data root");
            equal(data.resolve("models/ggml-large-v3-turbo.bin"), installed.model(), "installed model under writable data root");
            equal(bundle.resolve("Contents/app/setup-local-speech.sh"), installed.setup(), "setup script comes from this app bundle");
            var development = LocalSpeechService.runtimeFiles(project.resolve("target/classes"), fixture.root, home, null);
            equal(project.resolve("tools/speech"), development.directory(), "source checkout retains local speech storage");
            Path explicit = fixture.root.resolve("Custom Speech");
            equal(explicit, LocalSpeechService.runtimeFiles(jar, project, home, explicit.toString()).directory(), "explicit storage override preserved");
            equal(data, LocalSpeechService.runtimeFiles(jar, project, home, bundle.resolve("Contents/app/tools/speech").toString()).directory(), "bundle storage override cannot make app write into its bundle");
        }
    }

    private static void packagedRuntimeSetup() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return;
        try (Fixture fixture = new Fixture()) {
            Path bundle = fixture.root.resolve("StillWord.app/Contents/app");
            Path bundledSpeech = bundle.resolve("tools/speech");
            Files.createDirectories(bundledSpeech.resolve("bin"));
            Path script = bundle.resolve("setup-local-speech.sh");
            Files.copy(Path.of("setup-local-speech.sh"), script);
            Path cli = bundledSpeech.resolve("bin/whisper-cli");
            fixture.script(cli, "bundled-help", "exit 0\n");
            String architecture = new String(new ProcessBuilder("/usr/bin/uname", "-m").start().getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(cli)));
            Path receipt = bundledSpeech.resolve("bin/build-receipt.txt");
            Files.writeString(receipt, "whisper.cpp 1.9.4 " + architecture + " static embedded-metal\n" + hash + "\n");
            Files.writeString(bundledSpeech.resolve("WHISPER_LICENSE"), "fixture license");
            Path data = fixture.root.resolve("User Data/speech");
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", script.toString(), "--speech-dir", data.toString(), "--prepare-runtime-only");
            builder.environment().put("PATH", "/usr/bin:/bin");
            builder.environment().put("STILLWORD_SPEECH_DIR", fixture.root.resolve("Wrong Data").toString());
            Process process = builder.redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            equal(0, process.waitFor(), "bundled setup needs no developer tools or model download: " + output);
            equal(hash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data.resolve("bin/whisper-cli")))), "bundled CLI copied without changes");
            equal(Files.readString(receipt), Files.readString(data.resolve("bin/build-receipt.txt")), "verified receipt follows CLI");
            equal("fixture license", Files.readString(data.resolve("WHISPER_LICENSE")), "engine license copied");
            check(!Files.exists(data.resolve("downloads")), "installed setup creates no build/download cache");
            check(!Files.exists(data.resolve(".setup-lock")), "setup lock removed on success");
            check(!Files.exists(bundledSpeech.resolve("models")), "installed setup does not modify bundle");
            check(!Files.exists(fixture.root.resolve("Wrong Data")), "explicit directory takes precedence over environment");
            Files.writeString(receipt, "whisper.cpp 1.9.4 " + architecture + " static embedded-metal\n" + "0".repeat(64) + "\n");
            Path rejected = fixture.root.resolve("Rejected Data");
            Process invalid = new ProcessBuilder("/bin/sh", script.toString(), "--speech-dir", rejected.toString(), "--prepare-runtime-only").redirectErrorStream(true).start();
            String invalidOutput = new String(invalid.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(invalid.waitFor() != 0 && invalidOutput.contains("failed verification"), "corrupt bundled runtime rejected before use or download");
            check(!Files.exists(rejected.resolve("bin/whisper-cli")), "corrupt runtime not installed");
            check(!Files.exists(rejected.resolve(".setup-lock")), "setup lock removed on failure");
            Path link = fixture.root.resolve("Alias");
            Files.createSymbolicLink(link, bundle);
            Process aliased = new ProcessBuilder("/bin/sh", script.toString(), "--speech-dir", link.resolve("new-speech").toString(), "--prepare-runtime-only").redirectErrorStream(true).start();
            String aliasOutput = new String(aliased.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(aliased.waitFor() != 0 && aliasOutput.contains("outside the application bundle"), "symlink target inside app rejected");
            check(!Files.exists(bundle.resolve("new-speech")), "symlink validation happens before any bundle modification");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("stillword speech fixtures ");
        final Path download = root.resolve("fake downloader"), convert = root.resolve("fake converter"),
                whisper = root.resolve("fake recognizer"), model = root.resolve("speech model $(literal).bin"), setup = root.resolve("setup-local-speech.sh");
        final Path wav = root.resolve("converted source.wav");
        Fixture() throws Exception {
            Files.writeString(model, "fake speech model");
            byte[] pcm = new byte[65 * 16_000 * 2];
            for (int frame = 0; frame < pcm.length / 2; frame++) {
                int sample = frame % 30_000;
                pcm[frame * 2] = (byte) sample; pcm[frame * 2 + 1] = (byte) (sample >> 8);
            }
            try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm),
                    new AudioFormat(16_000, 16, 1, true, false), pcm.length / 2)) {
                AudioSystem.write(input, AudioFileFormat.Type.WAVE, wav.toFile());
            }
            script(download, "download", "printf audio > audio.m4a\nprintf '%s\\n' \"Dharma ' talk \\$(not-a-command)\" > title.txt\n");
            script(convert, "convert", "for arg do output=$arg; done\ncp " + quote(wav) + " \"$output\"\n");
            recognizer(VTT);
        }
        LocalSpeechService service() { return service(Duration.ofSeconds(10)); }
        LocalSpeechService service(Duration timeout) { return new LocalSpeechService(whisper, model, convert, setup, timeout); }
        void recognizer(String text) throws Exception {
            script(whisper, "whisper", "while [ \"$#\" -gt 0 ]; do case \"$1\" in -of) shift; output=$1;; -f) shift; input=$1;; esac; shift; done\n"
                    + "cp \"$input\" " + quote(root.resolve("recognized input.wav")) + "\n"
                    + "cat > \"$output.vtt\" <<'SPEECH_FIXTURE_EOF'\n" + text + "\nSPEECH_FIXTURE_EOF\n");
        }
        void script(Path executable, String name, String body) throws Exception {
            Files.writeString(executable, "#!/bin/sh\nset -eu\nprintf '%s\\n' \"$PWD\" > " + quote(root.resolve(name + ".cwd"))
                    + "\nprintf '%s\\n' \"$@\" > " + quote(root.resolve(name + ".args")) + "\n" + body, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(executable, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
        List<String> arguments(String name) throws IOException { return Files.readAllLines(root.resolve(name + ".args")); }
        void assertCleaned() throws IOException {
            Path temporary = Path.of(Files.readString(root.resolve("download.cwd")).strip());
            check(!Files.exists(temporary), "temporary media and transcript removed");
        }
        public void close() throws IOException {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void awaitFile(Path file) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!Files.exists(file) && System.nanoTime() < until) Thread.sleep(20);
        if (!Files.exists(file)) throw new AssertionError("Child process did not start: " + file);
    }
    private static String quote(Path path) { return quote(path.toString()); }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }
    private static String after(List<String> values, String flag) {
        int index = values.indexOf(flag);
        if (index < 0 || index + 1 >= values.size()) throw new AssertionError("Missing flag " + flag + " in " + values);
        return values.get(index + 1);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + ": expected " + expected + ", got " + actual);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static Exception fails(Class<? extends Exception> expected, Checked action, String message) throws Exception {
        try { action.run(); } catch (Exception error) {
            check(expected.isInstance(error), message + ": wrong exception " + error);
            return error;
        }
        throw new AssertionError(message + ": no exception");
    }
}
