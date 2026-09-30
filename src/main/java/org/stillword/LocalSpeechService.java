/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 *
 * @author veronique
 */

/** Downloads audio and transcribes it with a separate local Whisper model; no hosted AI API is used. */
public final class LocalSpeechService {
    public static final String SOURCE_DESCRIPTION = "Whisper audio";
    private static final long MAX_AUDIO_BYTES = 1_073_741_824L;
    private static final long MAX_WAV_BYTES = 4_000_000_000L;
    private static final long MAX_VTT_BYTES = 32L * 1024 * 1024;
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:\\.\\d+)?)%");
    private final Path whisper, model, converter, setupScript, speechDirectory;
    private final Duration downloadTimeout, conversionTimeout, transcriptionTimeout, setupTimeout;

    public record Status(boolean ready, String message) { }

    public LocalSpeechService() {
        this(runtimeFiles(), Path.of("/usr/bin/afconvert"), Duration.ofMinutes(30), Duration.ofMinutes(10),
                Duration.ofHours(3), Duration.ofHours(2));
    }

    private LocalSpeechService(RuntimeFiles files, Path converter, Duration downloadTimeout,
                               Duration conversionTimeout, Duration transcriptionTimeout, Duration setupTimeout) {
        this.whisper = files.whisper(); this.model = files.model(); this.setupScript = files.setup();
        this.speechDirectory = files.directory();
        this.converter = converter;
        this.downloadTimeout = positive(downloadTimeout); this.conversionTimeout = positive(conversionTimeout);
        this.transcriptionTimeout = positive(transcriptionTimeout); this.setupTimeout = positive(setupTimeout);
    }

    /** Injected local fixtures keep tests independent of downloads, the GPU, and macOS conversion. */
    LocalSpeechService(Path whisper, Path model, Path converter, Path setupScript, Duration timeout) {
        this(new RuntimeFiles(whisper, model, setupScript, whisper.toAbsolutePath().getParent()), converter, timeout, timeout, timeout, timeout);
    }

    public boolean isReady() { return status().ready(); }

    public Status status() {
        if (!executable(converter)) return new Status(false, "Local audio transcription needs the macOS audio converter (afconvert).");
        if (!executable(whisper) || !nonemptyFile(model))
            return new Status(false, "Set up the separate Whisper large-v3-turbo speech model before transcribing audio.");
        return new Status(true, "Whisper large-v3-turbo is ready for local audio transcription.");
    }

    public void setup(Consumer<String> progress) throws Exception {
        checkInterrupted();
        Consumer<String> report = reporter(progress);
        if (isReady()) { report.accept(status().message()); return; }
        if (setupScript == null || !Files.isRegularFile(setupScript))
            throw new IOException("The speech setup script was not found. Run setup-local-speech.sh from the StillWord project folder, then retry.");
        report.accept("Setting up the separate free speech model…");
        run(List.of("/bin/sh", setupScript.toString(), "--speech-dir", speechDirectory.toString()),
                setupScript.getParent(), setupTimeout, "Speech setup", report);
        if (!isReady()) throw new IOException(status().message());
        report.accept(status().message());
    }

    public TranscriptService.Result transcribe(String url, String ytDlpExecutable, Consumer<String> progress) throws Exception {
        return transcribe(url, ytDlpExecutable, null, null, progress);
    }

    /** Blocking; interrupt the worker to cancel. Recognition range times remain relative to the full video. */
    public TranscriptService.Result transcribe(String url, String ytDlpExecutable, Double startSeconds,
                                               Double endSeconds, Consumer<String> progress) throws Exception {
        String link = TranscriptService.validateUrl(url);
        VttParser.validateRange(startSeconds, endSeconds);
        checkInterrupted();
        Status readiness = status();
        if (!readiness.ready()) throw new IOException(readiness.message());
        String downloader = TranscriptService.resolveExecutable(ytDlpExecutable);
        Consumer<String> report = reporter(progress);
        Path working = Files.createTempDirectory("stillword-speech-");
        try {
            Path audio = working.resolve("audio.m4a"), wav = working.resolve("audio.wav");
            Path output = working.resolve("recognized"), vtt = working.resolve("recognized.vtt");
            report.accept("Downloading the video's audio for local transcription…");
            List<String> download = new ArrayList<>(List.of(downloader,
                    "--ignore-config", "--no-plugin-dirs", "--no-playlist", "--no-simulate", "--no-cache-dir",
                    "--newline", "--no-color", "--encoding", "utf-8", "--socket-timeout", "20",
                    "--retries", "2", "--fragment-retries", "2", "--extractor-retries", "2",
                    "--match-filters", "!is_live", "--max-filesize", "1G", "--fixup", "never",
                    "-f", "bestaudio[ext=m4a][acodec^=mp4a]", "--print-to-file", "%(title)s", "title.txt",
                    "-o", "audio.%(ext)s"));
            TranscriptService.addBundledJavaScriptRuntime(download, downloader);
            download.add("--"); download.add(link);
            run(download, working, downloadTimeout, "Audio download", report);
            requireOutput(audio, MAX_AUDIO_BYTES, "No AAC/m4a audio was downloaded. Use a completed public YouTube video with an available AAC audio track (maximum 1 GB).");
            checkInterrupted();
            report.accept("Preparing 16 kHz mono audio on this Mac…");
            run(List.of(converter.toString(), "-f", "WAVE", "-d", "LEI16@16000", "-c", "1",
                    audio.toString(), wav.toString()), working, conversionTimeout, "Audio conversion", report);
            requireOutput(wav, MAX_WAV_BYTES, "The audio could not be converted to a supported WAV file (maximum 4 GB).");
            checkInterrupted();
            Path recognitionWav = wav;
            Clip clip = null;
            if (startSeconds != null || endSeconds != null) {
                report.accept("Selecting only the requested audio portion…");
                recognitionWav = working.resolve("selected.wav");
                clip = clipWav(wav, recognitionWav, startSeconds, endSeconds);
            }
            report.accept("Transcribing audio with Whisper large-v3-turbo on this Mac…");
            List<String> recognize = new ArrayList<>(List.of(whisper.toString(), "-f", recognitionWav.toString(), "-m", model.toString(),
                    "-l", "en", "-ovtt", "-of", output.toString(), "-t", Integer.toString(threads()), "-pp"));
            run(recognize, working, transcriptionTimeout, "Local speech recognition", report);
            requireOutput(vtt, MAX_VTT_BYTES, "Whisper did not produce a readable transcript. Check the speech model and try again.");
            String recognized = Files.readString(vtt, StandardCharsets.UTF_8);
            VttParser.Parsed parsed = clip == null ? VttParser.parseWithCues(recognized) : parseClippedVtt(recognized, clip);
            if (parsed.text().isBlank()) throw new IOException(startSeconds != null || endSeconds != null
                    ? "No readable speech was recognized in the selected time range. Check the times or select a longer passage."
                    : "No readable speech was recognized. The recording may contain silence or unclear audio.");
            String title = "YouTube audio transcript";
            Path titleFile = working.resolve("title.txt");
            if (Files.isRegularFile(titleFile, LinkOption.NOFOLLOW_LINKS) && Files.size(titleFile) <= 16_384) {
                String value = Files.readString(titleFile, StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
                if (!value.isBlank()) title = value;
            }
            checkInterrupted();
            return new TranscriptService.Result(title, parsed.text(), "en", parsed.cues(), SOURCE_DESCRIPTION);
        } finally { deleteTree(working); }
    }

    record Clip(double startSeconds, double endSeconds, long frameCount) { }

    /** Streams exact PCM frames into a standalone WAV so the decoder cannot hear outside the selection. */
    static Clip clipWav(Path source, Path destination, Double from, Double to) throws Exception {
        VttParser.validateRange(from, to);
        checkInterrupted();
        boolean complete = false;
        try (AudioInputStream input = AudioSystem.getAudioInputStream(source.toFile())) {
            AudioFormat format = input.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.getSampleRate() != 16_000
                    || format.getFrameRate() != 16_000 || format.getSampleSizeInBits() != 16 || format.getChannels() != 1
                    || format.getFrameSize() != 2 || format.isBigEndian() || input.getFrameLength() < 0)
                throw new IOException("Audio selection requires the converted 16 kHz mono PCM WAV file.");
            long available = input.getFrameLength();
            long start = (long) Math.ceil((from == null ? 0 : from) * 16_000);
            long end = to == null ? available : Math.min(available, (long) Math.floor(to * 16_000));
            if (start >= end) throw new IOException("The selected time range contains no audio. Check the times against the video duration.");
            long remaining = start * 2;
            byte[] buffer = new byte[16_384];
            while (remaining > 0) {
                checkInterrupted();
                long skipped = input.skip(remaining);
                if (skipped == 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("The audio ended before the selected starting time.");
                    skipped = count;
                }
                remaining -= skipped;
            }
            long frames = end - start;
            try (AudioInputStream selection = new AudioInputStream(new FilterInputStream(input) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audio selection cancelled.");
                    return in.read(bytes, offset, length);
                }
                @Override public int read() throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audio selection cancelled.");
                    return in.read();
                }
            }, format, frames)) {
                AudioSystem.write(selection, AudioFileFormat.Type.WAVE, destination.toFile());
            } catch (InterruptedIOException exception) { throw new InterruptedException("Audio selection cancelled."); }
            checkInterrupted();
            try (AudioInputStream written = AudioSystem.getAudioInputStream(destination.toFile())) {
                if (written.getFrameLength() != frames) throw new IOException("The selected audio could not be fully written.");
            }
            complete = true;
            return new Clip(start / 16_000.0, end / 16_000.0, frames);
        } finally { if (!complete) Files.deleteIfExists(destination); }
    }

    static VttParser.Parsed parseClippedVtt(String source, Clip clip) {
        VttParser.Parsed parsed = VttParser.parseWithCues(source);
        List<VttParser.Cue> cues = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int previousEnd = 0;
        for (VttParser.Cue cue : parsed.cues()) {
            double start = Math.max(clip.startSeconds(), clip.startSeconds() + cue.startSeconds());
            double end = Math.min(clip.endSeconds(), clip.startSeconds() + cue.endSeconds());
            if (cue.startSeconds() < clip.endSeconds() - clip.startSeconds() && cue.endSeconds() >= 0
                    && cue.endSeconds() >= cue.startSeconds()) {
                if (!text.isEmpty()) text.append(parsed.text(), previousEnd, cue.startOffset());
                int first = text.length();
                text.append(parsed.text(), cue.startOffset(), cue.endOffset());
                cues.add(new VttParser.Cue(start, end, first, text.length()));
            }
            previousEnd = cue.endOffset();
        }
        return new VttParser.Parsed(text.toString(), cues);
    }

    private static int threads() { return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2)); }
    private static Duration positive(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("Timeout must be positive.");
        return value;
    }

    private static void requireOutput(Path path, long maximum, String message) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0 || Files.size(path) > maximum)
            throw new IOException(message);
    }

    record RuntimeFiles(Path whisper, Path model, Path setup, Path directory) { }

    private static RuntimeFiles runtimeFiles() {
        Path codeLocation = null;
        try { codeLocation = Path.of(LocalSpeechService.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception ignored) { }
        return runtimeFiles(codeLocation, Path.of(System.getProperty("user.dir")),
                Path.of(System.getProperty("user.home")), System.getenv("STILLWORD_SPEECH_DIR"));
    }

    /** Installed apps never choose their signed bundle or the launch directory as writable storage. */
    static RuntimeFiles runtimeFiles(Path codeLocation, Path workingDirectory, Path userHome, String override) {
        Path installed = userHome.resolve("Library/Application Support/Stillword/speech").toAbsolutePath().normalize();
        Path directory = override == null || override.isBlank() ? null : Path.of(override).toAbsolutePath().normalize();
        if (directory != null && appBundle(directory) != null) directory = null;
        Path bundle = codeLocation == null ? null : appBundle(codeLocation);
        Path script = null;
        if (bundle != null) {
            script = bundle.resolve("Contents/app/setup-local-speech.sh");
            if (directory == null) directory = installed;
        } else {
            List<Path> roots = new ArrayList<>();
            if (codeLocation != null) roots.add(codeLocation.toAbsolutePath().normalize());
            roots.add(workingDirectory.toAbsolutePath().normalize());
            Path project = null;
            for (Path initial : roots) {
                for (Path root = initial; root != null && project == null; root = root.getParent()) {
                    for (Path possible : List.of(root, root.resolve("Code"))) {
                        if (Files.isRegularFile(possible.resolve("setup-local-speech.sh"))
                                && Files.isRegularFile(possible.resolve("build.sh"))
                                && Files.isDirectory(possible.resolve("src/main/java"))) {
                            project = possible;
                            break;
                        }
                    }
                }
                if (project != null) break;
            }
            if (project != null) {
                script = project.resolve("setup-local-speech.sh");
                if (directory == null) directory = project.resolve("tools/speech");
            } else if (directory == null) directory = installed;
        }
        return new RuntimeFiles(directory.resolve("bin/whisper-cli"),
                directory.resolve("models/ggml-large-v3-turbo.bin"), script, directory);
    }

    private static Path appBundle(Path path) {
        for (Path root = path.toAbsolutePath().normalize(); root != null; root = root.getParent()) {
            if (root.getFileName() != null && root.getFileName().toString().endsWith(".app")) return root;
        }
        return null;
    }

    private static boolean executable(Path file) { return file != null && Files.isRegularFile(file) && Files.isExecutable(file); }
    private static boolean nonemptyFile(Path file) {
        try { return file != null && Files.isRegularFile(file) && Files.isReadable(file) && Files.size(file) > 0; }
        catch (IOException exception) { return false; }
    }

    /** Drains subprocess output continuously while keeping only an 8 KiB diagnostic tail in memory. */
    private static void run(List<String> command, Path directory, Duration timeout, String phase, Consumer<String> report) throws Exception {
        checkInterrupted();
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("PATH", builder.environment().getOrDefault("PATH", "") + ":/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin");
        Process process;
        try { process = builder.start(); }
        catch (IOException exception) { throw new IOException(phase + " could not start. Check that its executable is installed and runnable.", exception); }
        Tail tail = new Tail();
        Thread drain = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = input.read(bytes)) != -1) tail.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }, "stillword-speech-output");
        drain.setDaemon(true); drain.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        String previous = "";
        try {
            process.getOutputStream().close();
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                checkInterrupted();
                if (System.nanoTime() >= deadline) throw new IOException(phase + " timed out. Try a shorter video or selected portion.");
                String progress = progressText(tail.text(), phase);
                if (!progress.isBlank() && !progress.equals(previous)) { report.accept(progress); previous = progress; }
            }
            drain.join(1000); checkInterrupted();
            if (process.exitValue() != 0) throw new IOException(failureMessage(phase, tail.text()));
        } finally { terminate(process, phase.equals("Speech setup")); }
    }

    private static String progressText(String output, String phase) {
        var matcher = PERCENT.matcher(output);
        String percent = "";
        while (matcher.find()) if (Double.parseDouble(matcher.group(1)) <= 100) percent = matcher.group(1);
        if (!percent.isEmpty()) return phase + "… " + percent + "%";
        if (phase.equals("Speech setup")) {
            String cleaned = cleanOutput(output).strip();
            int newline = Math.max(cleaned.lastIndexOf('\n'), cleaned.lastIndexOf('\r'));
            String last = cleaned.substring(newline + 1).strip();
            return last.length() <= 200 ? last : last.substring(last.length() - 200);
        }
        return "";
    }

    private static String failureMessage(String phase, String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        String message = phase + " failed.";
        if (phase.equals("Audio download")) {
            if (lower.contains("sign in") || lower.contains("private") || lower.contains("members-only"))
                message = "YouTube requires sign-in or restricts this video. Use a publicly accessible video.";
            else if (lower.contains("requested format") || lower.contains("no formats"))
                message = "An AAC/m4a audio track was not available. Update yt-dlp or try another video.";
            else message += " Check the link and connection, and update yt-dlp if needed.";
        } else if (phase.equals("Audio conversion")) message += " The downloaded audio may be unsupported or damaged.";
        else if (phase.equals("Local speech recognition")) message += " Check the speech model and available memory; try a shorter portion.";
        String details = cleanOutput(output).strip();
        if (details.length() > 3000) details = details.substring(details.length() - 3000);
        return message + (details.isBlank() ? "" : "\n\nDetails:\n" + details);
    }

    private static String cleanOutput(String value) {
        return value.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "").replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", "");
    }

    private static final class Tail {
        private final StringBuilder text = new StringBuilder();
        synchronized void append(String value) { text.append(value); if (text.length() > 8192) text.delete(0, text.length() - 8192); }
        synchronized String text() { return text.toString(); }
    }

    private static void terminate(Process process, boolean graceful) {
        if (!process.isAlive()) return;
        // The setup shell traps TERM to remove its lock and stop build/download children.
        // Clear and restore interruption so cancellation still allows that cleanup window.
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> children;
        try { children = process.descendants().toList(); }
        catch (RuntimeException ignored) { children = List.of(); }
        if (graceful) {
            process.destroy();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        try {
            for (ProcessHandle handle : children) {
                try { if (handle.isAlive()) handle.destroyForcibly(); }
                catch (RuntimeException ignored) { }
            }
            // Let a waiting wrapper reap its children before terminating the wrapper itself.
            if (!children.isEmpty() && process.isAlive()) {
                try { process.waitFor(250, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (process.isAlive()) process.destroyForcibly();
            try { process.waitFor(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { interrupted = true; }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void deleteTree(Path directory) {
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { path.toFile().deleteOnExit(); }
            }
        } catch (IOException ignored) { directory.toFile().deleteOnExit(); }
    }

    private static Consumer<String> reporter(Consumer<String> report) { return report == null ? message -> { } : report; }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Local audio transcription cancelled.");
    }
}
