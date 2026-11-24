/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeoutException;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
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
    private static final long MAX_AUDIO_BYTES = AudioInput.MAX_FILE_BYTES;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
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

    /** Blocking; interrupt the worker to cancel. Recognition range times remain relative to the full recording. */
    public TranscriptService.Result transcribe(String url, String ytDlpExecutable, Double startSeconds,
                                               Double endSeconds, Consumer<String> progress) throws Exception {
        AudioInput input = AudioInput.parse(url);
        String link = input.value();
        VttParser.validateRange(startSeconds, endSeconds);
        checkInterrupted();
        Status readiness = status();
        if (!readiness.ready()) throw new IOException(readiness.message());

        Consumer<String> report = reporter(progress);
        Path working = Files.createTempDirectory("stillword-speech-");
        try {
            Path audio, wav = working.resolve("audio.wav");
            Path output = working.resolve("recognized"), vtt = working.resolve("recognized.vtt");
            String title = input.title();
            String playbackSource = link;
            if (input.kind() == AudioInput.Kind.AUDIO_FILE) {
                audio = Path.of(link);
                playbackSource = audio.toUri().toString();
                report.accept("Reading the selected audio file for local transcription…");
                requireOutput(audio, MAX_AUDIO_BYTES, "The selected audio file is empty, unreadable, or larger than 1 GB.");
            } else if (input.kind() == AudioInput.Kind.YOUTUBE) {
                String downloader = TranscriptService.resolveExecutable(ytDlpExecutable);
                audio = working.resolve("audio.m4a");
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
                Path titleFile = working.resolve("title.txt");
                if (Files.isRegularFile(titleFile, LinkOption.NOFOLLOW_LINKS) && Files.size(titleFile) <= 16_384) {
                    String value = Files.readString(titleFile, StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
                    if (!value.isBlank()) title = value;
                }
            } else {
                URI remote = URI.create(link);
                audio = working.resolve("downloaded.audio");
                report.accept("Downloading the audio source…");
                Downloaded downloaded = download(remote, audio, MAX_AUDIO_BYTES, downloadTimeout,
                        input.kind() == AudioInput.Kind.AUDIO_URL);
                if (downloaded.html()) {
                    report.accept("Finding the page’s audio recording…");
                    ResolvedAudio resolved = resolveAudioPage(downloaded.uri(), Files.readString(audio, StandardCharsets.UTF_8));
                    remote = resolved.uri(); title = resolved.title();
                    audio = working.resolve("recording.audio");
                    report.accept("Downloading the audio recording for local transcription…");
                    download(remote, audio, MAX_AUDIO_BYTES, downloadTimeout, true);
                }
                playbackSource = remote.toString();
                requireOutput(audio, MAX_AUDIO_BYTES, "No readable audio was downloaded (maximum 1 GB).");
            }
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
            checkInterrupted();
            return new TranscriptService.Result(title, parsed.text(), "en", parsed.cues(), SOURCE_DESCRIPTION, playbackSource);
        } finally { deleteTree(working); }
    }

    record ResolvedAudio(URI uri, String title) { }

    /** Resolves standard embedded audio and download links without executing page scripts. */
    static ResolvedAudio resolveAudioPage(URI page, String html) throws IOException {
        List<URI> embedded = new ArrayList<>(), downloads = new ArrayList<>();
        StringBuilder title = new StringBuilder();
        HTMLEditorKit.ParserCallback callback = new HTMLEditorKit.ParserCallback() {
            private boolean inTitle;
            private void inspect(HTML.Tag tag, MutableAttributeSet attrs) {
                String name = tag.toString();
                if (name.equals("audio")) add(attrs.getAttribute(HTML.Attribute.SRC), embedded, false);
                if (name.equals("source")) {
                    Object type = attrs.getAttribute(HTML.Attribute.TYPE);
                    add(attrs.getAttribute(HTML.Attribute.SRC), embedded, type == null || !type.toString().startsWith("audio/"));
                }
                if (tag == HTML.Tag.A) add(attrs.getAttribute(HTML.Attribute.HREF), downloads, true);
                if (tag == HTML.Tag.META) {
                    Object property = attrs.getAttribute("property");
                    if (property == null) property = attrs.getAttribute(HTML.Attribute.NAME);
                    if (property != null && (property.toString().equalsIgnoreCase("og:audio")
                            || property.toString().equalsIgnoreCase("og:audio:url")
                            || property.toString().equalsIgnoreCase("og:audio:secure_url")))
                        add(attrs.getAttribute(HTML.Attribute.CONTENT), embedded, false);
                }
            }
            private void add(Object value, List<URI> into, boolean requireExtension) {
                if (value == null) return;
                try {
                    URI candidate = page.resolve(value.toString());
                    if (("https".equalsIgnoreCase(candidate.getScheme()) || "http".equalsIgnoreCase(candidate.getScheme()))
                            && candidate.getHost() != null && candidate.getRawUserInfo() == null
                            && (!requireExtension || AudioInput.supportedName(candidate.getPath()))) {
                        String withoutFragment = candidate.toString().split("#", 2)[0];
                        into.add(URI.create(withoutFragment));
                    }
                } catch (IllegalArgumentException ignored) { }
            }
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
                if (tag == HTML.Tag.TITLE) inTitle = true;
                inspect(tag, attrs);
            }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) { inspect(tag, attrs); }
            @Override public void handleEndTag(HTML.Tag tag, int pos) { if (tag == HTML.Tag.TITLE) inTitle = false; }
            @Override public void handleText(char[] text, int pos) { if (inTitle && title.length() < 16_384) title.append(text).append(' '); }
        };
        String document = Pattern.compile("(?i)<(?:html|head|body)\\b").matcher(html).find()
                ? html : "<html><head>" + html + "</head><body></body></html>";
        new ParserDelegator().parse(new StringReader(document), callback, true);
        // Swing's HTML 3.2 parser drops unknown HTML5 tags before an implied body.
        // A second body-scoped pass recovers audio/source tags in HTML fragments.
        // The original pass retains the authoritative title and head metadata.
        if (embedded.isEmpty() && Pattern.compile("(?i)<(?:audio|source)\\b").matcher(html).find()) {
            String originalTitle = title.toString();
            new ParserDelegator().parse(new StringReader("<html><body>" + html + "</body></html>"), callback, true);
            if (!originalTitle.isBlank()) { title.setLength(0); title.append(originalTitle); }
        }
        List<URI> unique = (embedded.isEmpty() ? downloads : embedded).stream().distinct().toList();
        if (unique.size() != 1) throw new IOException(unique.isEmpty()
                ? "No audio recording was found in this page. Copy its audio Download link or download the recording and click Choose file. Pages requiring scripts or sign-in may not expose audio directly."
                : "This page contains more than one audio recording. Copy the specific audio Download link or choose a downloaded file.");
        String name = title.toString().strip().replaceAll("\\s+", " ");
        if (name.isBlank()) name = "Audio transcript";
        return new ResolvedAudio(unique.get(0), name);
    }

    record Downloaded(URI uri, boolean html) { }

    /** A bounded asynchronous body keeps Cancel effective even while the remote server stalls. */
    private static Downloaded download(URI uri, Path destination, long maximum, Duration timeout, boolean requireAudio) throws Exception {
        checkInterrupted();
        var html = new java.util.concurrent.atomic.AtomicBoolean();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "StillWord/1.0 (audio transcription)").GET().build();
        var pending = HTTP.sendAsync(request, info -> {
            if (info.statusCode() < 200 || info.statusCode() >= 300)
                throw new UncheckedIOException(new IOException("Audio source returned HTTP " + info.statusCode() + ". Check that the link is publicly accessible."));
            String contentType = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            html.set(contentType.contains("html"));
            if (requireAudio && (contentType.startsWith("text/") || html.get() || contentType.contains("json")))
                throw new UncheckedIOException(new IOException("The link returned a web page instead of audio. Use its audio download link or choose the downloaded file."));
            long limit = html.get() ? Math.min(maximum, 2L * 1024 * 1024) : maximum;
            if (info.headers().firstValueAsLong("Content-Length").orElse(0) > limit)
                throw new UncheckedIOException(new IOException("The download exceeds the allowed size (" + limit / 1024 / 1024 + " MB)."));
            return new LimitedFileSubscriber(destination, maximum, html, requireAudio);
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                checkInterrupted();
                if (System.nanoTime() >= deadline) throw new IOException("Audio download timed out. Check your connection and retry.");
                try { var response = pending.get(250, TimeUnit.MILLISECONDS); return new Downloaded(response.uri(), html.get()); }
                catch (TimeoutException ignored) { }
                catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof UncheckedIOException io) throw io.getCause();
                    if (cause instanceof IOException io) throw io;
                    throw new IOException("Could not download the audio source: " + cause.getMessage(), cause);
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }

    private static final class LimitedFileSubscriber implements HttpResponse.BodySubscriber<Path> {
        private final HttpResponse.BodySubscriber<Path> output;
        private final long maximum;
        private final java.util.concurrent.atomic.AtomicBoolean html;
        private final boolean requireAudio;
        private final java.io.ByteArrayOutputStream prefix = new java.io.ByteArrayOutputStream();
        private long bytes;
        private Flow.Subscription subscription;
        private boolean stopped;
        LimitedFileSubscriber(Path file, long maximum, java.util.concurrent.atomic.AtomicBoolean html, boolean requireAudio) {
            output = HttpResponse.BodySubscribers.ofFile(file);
            this.maximum = maximum; this.html = html; this.requireAudio = requireAudio;
        }
        @Override public CompletionStage<Path> getBody() { return output.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; output.onSubscribe(value); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (stopped) return;
            for (ByteBuffer buffer : buffers) {
                bytes += buffer.remaining();
                if (prefix.size() < 512) {
                    ByteBuffer copy = buffer.duplicate();
                    byte[] leading = new byte[Math.min(512 - prefix.size(), copy.remaining())];
                    copy.get(leading); prefix.writeBytes(leading);
                }
            }
            String start = prefix.toString(StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
            if (start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<head")
                    || start.startsWith("<body") || start.startsWith("<audio")) html.set(true);
            long limit = html.get() ? Math.min(maximum, 2L * 1024 * 1024) : maximum;
            if ((requireAudio && html.get()) || bytes > limit) {
                stopped = true; subscription.cancel();
                output.onError(new IOException(requireAudio && html.get()
                        ? "The link returned HTML instead of audio. Use its audio download link."
                        : "The download exceeded the maximum allowed size."));
            } else output.onNext(buffers);
        }
        @Override public void onError(Throwable error) { stopped = true; output.onError(error); }
        @Override public void onComplete() { if (!stopped) output.onComplete(); }
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
            if (start >= end) throw new IOException("The selected time range contains no audio. Check the times against the recording duration.");
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
                if (System.nanoTime() >= deadline) throw new IOException(phase + " timed out. Try a shorter recording or selected portion.");
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
        } else if (phase.equals("Audio conversion")) message += " The audio codec may be unsupported by this Mac or the recording may be damaged. Try exporting it as WAV, MP3, or M4A.";
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
