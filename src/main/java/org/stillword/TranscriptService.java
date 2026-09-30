/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 *
 * @author veronique
 */

/** Downloads caption files with a local yt-dlp installation; never downloads the video. */
public final class TranscriptService {
    public record Result(String title, String text, String language, List<VttParser.Cue> cues, String sourceDescription) {
        public Result(String title, String text, String language) { this(title, text, language, List.of(), "Captions"); }
        public Result(String title, String text, String language, List<VttParser.Cue> cues) { this(title, text, language, cues, "Captions"); }
        public Result { cues = List.copyOf(cues); }
    }

    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
            "music.youtube.com", "youtu.be", "www.youtu.be");
    private static final long MAX_CAPTION_BYTES = 32L * 1024 * 1024;
    private final Duration timeout;

    public TranscriptService() { this(Duration.ofMinutes(2)); }

    TranscriptService(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive.");
        this.timeout = timeout;
    }

    /** Accepts a YouTube video link and returns a canonical HTTPS watch URL. */
    public static String validateUrl(String input) {
        String error = "Enter a YouTube video link, such as https://www.youtube.com/watch?v=VIDEO_ID.";
        if (input == null || input.isBlank()) throw new IllegalArgumentException(error);
        String candidate = input.strip();
        if (!candidate.contains("://")) candidate = "https://" + candidate;
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || !YOUTUBE_HOSTS.contains(host)
                    || uri.getRawUserInfo() != null || uri.getPort() != -1) throw new IllegalArgumentException(error);
            String id = null;
            String path = uri.getPath();
            if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                if (path.matches("/[A-Za-z0-9_-]{11}/?")) id = path.substring(1, 12);
            } else if (path.equals("/watch") || path.equals("/watch/")) {
                String query = uri.getRawQuery();
                if (query != null) for (String part : query.split("&")) {
                    String[] pair = part.split("=", 2);
                    if (URLDecoder.decode(pair[0], StandardCharsets.UTF_8).equals("v") && pair.length == 2) {
                        if (id != null) throw new IllegalArgumentException(error);
                        id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    }
                }
            } else if (path.matches("/(shorts|live|embed)/[A-Za-z0-9_-]{11}/?")) {
                id = path.split("/")[2];
            }
            if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException(error);
            return "https://www.youtube.com/watch?v=" + id;
        } catch (java.net.URISyntaxException | NullPointerException exception) {
            throw new IllegalArgumentException(error, exception);
        }
    }

    public Result fetch(String url, String executable, String language, Consumer<String> progress) throws Exception {
        return fetch(url, executable, language, null, null, progress);
    }

    public Result fetch(String url, String executable, String language, Double startSeconds, Double endSeconds,
                        Consumer<String> progress) throws Exception {
        String normalizedUrl = validateUrl(url);
        VttParser.validateRange(startSeconds, endSeconds);
        String requested = language == null || language.isBlank() ? "en.*" : language.strip();
        if (!requested.equals("en.*") && !requested.matches("en(?:-[A-Za-z0-9]+)*"))
            throw new IllegalArgumentException("Use en.* for all English caption variants, or an English tag such as en-US.");
        Consumer<String> report = progress == null ? message -> { } : progress;
        checkInterrupted();
        String program = resolveExecutable(executable);
        Path working = Files.createTempDirectory("bhavana-captions-");
        try {
            report.accept("Looking for publisher-provided English captions…");
            Path manual = Files.createDirectory(working.resolve("manual"));
            run(program, normalizedUrl, requested, manual, false);
            List<Path> captions = captionFiles(manual);
            Path selectedDirectory = manual;
            String sourceDescription = "Publisher captions";
            if (captions.isEmpty()) {
                checkInterrupted();
                report.accept("Looking for automatic English captions…");
                selectedDirectory = Files.createDirectory(working.resolve("automatic"));
                run(program, normalizedUrl, requested, selectedDirectory, true);
                captions = captionFiles(selectedDirectory);
                sourceDescription = "Automatic captions";
            }
            if (captions.isEmpty()) throw new IOException("This video has no downloadable English captions. "
                    + "Choose Whisper · Free local as the transcript source to transcribe its audio instead.");
            report.accept("Cleaning timestamps and repeated caption overlaps…");
            captions.sort(Comparator.comparingInt(TranscriptService::languageRank).thenComparing(Path::toString));
            for (Path caption : captions) {
                checkInterrupted();
                if (Files.size(caption) > MAX_CAPTION_BYTES)
                    throw new IOException("The caption file is too large to process (maximum 32 MB).");
                VttParser.Parsed parsed = VttParser.parseWithCues(Files.readString(caption, StandardCharsets.UTF_8), startSeconds, endSeconds);
                String text = parsed.text();
                if (!text.isBlank()) {
                    Path titleFile = selectedDirectory.resolve("title.txt");
                    String title = Files.isRegularFile(titleFile, LinkOption.NOFOLLOW_LINKS) && Files.size(titleFile) <= 16_384
                            ? Files.readString(titleFile, StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ") : "YouTube transcript";
                    if (title.isBlank()) title = "YouTube transcript";
                    return new Result(title, text, captionLanguage(caption), parsed.cues(), sourceDescription);
                }
            }
            if (startSeconds != null || endSeconds != null)
                throw new IOException("No readable English captions overlap the selected time range. Check the start and end times.");
            throw new IOException("English caption files were downloaded, but they contained no readable WebVTT cues.");
        } finally {
            deleteTree(working);
        }
    }

    private void run(String executable, String url, String language, Path directory, boolean automatic) throws Exception {
        checkInterrupted();
        List<String> command = new ArrayList<>(List.of(executable,
                "--ignore-config", "--no-plugin-dirs", "--no-playlist", "--skip-download", "--no-simulate",
                "--no-cache-dir", "--no-progress", "--no-color", "--encoding", "utf-8",
                "--socket-timeout", "20", "--retries", "2", "--extractor-retries", "2",
                "--sub-langs", language, "--sub-format", "vtt",
                "--print-to-file", "%(title)s", "title.txt", "-o", "captions.%(ext)s"));
        command.add(automatic ? "--no-write-subs" : "--write-subs");
        command.add(automatic ? "--write-auto-subs" : "--no-write-auto-subs");
        addBundledJavaScriptRuntime(command, executable);
        command.add("--");
        command.add(url);
        Path output = directory.resolve("yt-dlp.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        // Finder-launched Java apps have a short PATH; include usual package-manager locations.
        String inheritedPath = builder.environment().getOrDefault("PATH", "");
        if (!isWindows()) builder.environment().put("PATH", inheritedPath + ":/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin");
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new IOException("Could not start yt-dlp. Install yt-dlp or select its executable in AI & app settings. "
                    + "Selected executable: " + executable, exception);
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("YouTube caption retrieval timed out after " + timeout.toSeconds()
                        + " seconds. Check your connection and retry; updating yt-dlp may also help.");
            }
            checkInterrupted();
            if (process.exitValue() != 0) throw new IOException(explainFailure(readLogTail(output)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            terminate(process);
        }
    }

    private static String explainFailure(String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        String explanation;
        if (lower.contains("sign in") || lower.contains("sign-in") || lower.contains("private video") || lower.contains("members-only"))
            explanation = "YouTube requires sign-in or restricts this video. Try a publicly accessible video.";
        else if (lower.contains("429") || lower.contains("too many requests"))
            explanation = "YouTube is temporarily limiting caption requests. Wait a little, then retry.";
        else if (lower.contains("unavailable") || lower.contains("removed") || lower.contains("does not exist"))
            explanation = "The video is unavailable. Check that the link opens on YouTube.";
        else if (lower.contains("timed out") || lower.contains("network") || lower.contains("resolve") || lower.contains("connection"))
            explanation = "Could not reach YouTube. Check your network connection and retry.";
        else explanation = "yt-dlp could not retrieve the captions. Update yt-dlp and retry; "
                    + "YouTube may require a supported JavaScript runtime such as Deno.";
        return explanation + (output.isBlank() ? "" : "\n\nDetails from yt-dlp:\n" + output);
    }

    private static String readLogTail(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            long size = Files.size(path);
            if (size > 5_000) input.skipNBytes(size - 5_000);
            return new String(input.readNBytes(5_000), StandardCharsets.UTF_8).strip();
        }
    }

    private static List<Path> captionFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return new ArrayList<>(files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().matches("captions\\.en(?:[.-][A-Za-z0-9_-]+)*\\.vtt"))
                    .toList());
        }
    }

    private static int languageRank(Path path) {
        String language = captionLanguage(path);
        if (language.equals("en-orig")) return 0;
        if (language.equals("en")) return 1;
        if (language.equals("en-US")) return 2;
        if (language.equals("en-GB")) return 3;
        return 4;
    }

    private static String captionLanguage(Path path) {
        String name = path.getFileName().toString();
        return name.substring("captions.".length(), name.length() - ".vtt".length());
    }

    static String resolveExecutable(String requested) throws IOException {
        if (requested != null && !requested.isBlank()) {
            String value = requested.strip();
            if (value.contains("/") || value.contains("\\")) {
                Path path = Path.of(value).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toString();
                throw new IOException("The selected yt-dlp executable does not exist or cannot be run: " + path);
            }
            String found = findOnPath(value);
            if (found != null) return found;
            throw new IOException("Cannot find the executable '" + value + "'. Choose the full path to yt-dlp in AI & app settings.");
        }
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("user.dir")).toAbsolutePath());
        try { roots.add(Path.of(TranscriptService.class.getProtectionDomain().getCodeSource().getLocation().toURI())); }
        catch (Exception ignored) { }
        for (Path initial : System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? roots : List.<Path>of()) {
            for (Path root = initial; root != null; root = root.getParent()) {
                for (Path candidate : List.of(root.resolve("tools/yt-dlp/yt-dlp_macos"), root.resolve("Code/tools/yt-dlp/yt-dlp_macos"))) {
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toString();
                }
            }
        }
        String found = findOnPath(isWindows() ? "yt-dlp.exe" : "yt-dlp");
        if (found != null) return found;
        String userHome = System.getProperty("user.home", "");
        for (String location : List.of("/opt/homebrew/bin/yt-dlp", "/usr/local/bin/yt-dlp", "/usr/bin/yt-dlp",
                userHome + "/.local/bin/yt-dlp", userHome + "/bin/yt-dlp")) {
            Path path = Path.of(location);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toString();
        }
        throw new IOException("yt-dlp was not found. Install it (on macOS: brew install yt-dlp), "
                + "then retry, or select its executable in AI & app settings.");
    }

    /** Adds the JavaScript runtime packaged beside StillWord's downloader, when present. */
    static void addBundledJavaScriptRuntime(List<String> command, String executable) {
        Path downloader = Path.of(executable).toAbsolutePath().normalize();
        Path downloaderDirectory = downloader.getParent();
        if (downloaderDirectory == null || downloaderDirectory.getFileName() == null
                || !downloader.getFileName().toString().equals("yt-dlp_macos")
                || !downloaderDirectory.getFileName().toString().equals("yt-dlp")) return;
        Path tools = downloaderDirectory.getParent();
        if (tools == null || tools.getFileName() == null || !tools.getFileName().toString().equals("tools")) return;
        for (String runtime : List.of("node", "deno")) {
            Path program = tools.resolve(runtime).resolve(runtime);
            if (!Files.isRegularFile(program) || !Files.isExecutable(program)) continue;
            // Otherwise yt-dlp may choose an unrelated Deno installation over bundled Node.
            command.add("--no-js-runtimes");
            command.add("--js-runtimes");
            // Keep the complete path in one argument, including spaces in the installation path.
            command.add(runtime + ":" + program);
            return;
        }
    }

    private static String findOnPath(String executable) {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry).resolve(executable).toAbsolutePath().normalize();
            if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toString();
        }
        return null;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Caption retrieval cancelled.");
    }

    private static void terminate(Process process) {
        if (!process.isAlive()) return;
        try {
            // Process enumeration can be disallowed in a macOS sandbox. Always kill the main process.
            process.descendants().forEach(descendant -> {
                try { descendant.destroyForcibly(); }
                catch (RuntimeException ignored) { /* Best effort for child processes. */ }
            });
        } catch (RuntimeException ignored) { /* Restricted process enumeration. */ }
        finally { process.destroyForcibly(); }
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { path.toFile().deleteOnExit(); }
            });
        } catch (IOException ignored) { directory.toFile().deleteOnExit(); }
    }
}
