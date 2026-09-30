/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 *
 * @author veronique
 */

/** Installs and runs Ollama locally, without adding a login item or system service. */
public final class LocalAiService {
    public static final String MODEL = "stillword-gpt-oss";
    public static final String BASE_MODEL = "gpt-oss:20b";
    public static final int CONTEXT_LENGTH = 32_768;
    public static final String BASE_URL = "http://127.0.0.1:11434/v1";
    private static final String RUNTIME_VERSION = "0.34.4";
    private static final String RUNTIME_SHA256 = "e9c8fddaab5f48f47f2c4ae3d23d0732f5182417125353faeed2188e34a22799";
    private static final URI SERVER = URI.create("http://127.0.0.1:11434");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI server;
    private final ModelDownloader modelDownloader;

    @FunctionalInterface
    interface ModelDownloader { void download(Consumer<String> progress) throws Exception; }

    public LocalAiService() { this(SERVER); }
    LocalAiService(URI server) { this(server, null); }
    LocalAiService(URI server, ModelDownloader modelDownloader) {
        this.server = server;
        this.modelDownloader = modelDownloader;
    }

    /** Lightweight status; does not install, download, or launch anything. */
    public record Status(boolean running, boolean modelReady, String message) { }

    public Status status() throws InterruptedException {
        try {
            Map<?, ?> tags = request("/api/tags", null, Duration.ofSeconds(3));
            if (!(tags.get("models") instanceof List<?>)) return new Status(false, false, "Ollama is not responding correctly.");
            Map<?, ?> model = request("/api/show", Map.of("model", MODEL), Duration.ofSeconds(5));
            if (!hasRequiredContext(model)) return new Status(true, false, "Run Set up local AI to prepare the model context.");
            return new Status(true, true, "gpt-oss 20B is ready.");
        } catch (IOException | IllegalArgumentException exception) {
            boolean running = responds();
            return new Status(running, false, running ? "Run Set up local AI to prepare the free model."
                    : "Local AI is stopped; it starts when you request a summary.");
        }
    }

    /** Starts the server as needed and checks the model before a summary is sent. */
    public void ensureRunning(Consumer<String> progress) throws Exception {
        Consumer<String> report = reporter(progress);
        startServer(report);
        Status status = status();
        if (!status.modelReady()) throw new IOException(status.message());
    }

    /** Downloads the free model only if absent, then creates an explicit 32K-context variant. */
    public void setup(Consumer<String> progress) throws Exception {
        Consumer<String> report = reporter(progress);
        checkInterrupted();
        if (status().modelReady()) {
            report.accept("gpt-oss 20B is already ready.");
            return;
        }
        if (!responds() && findExecutable() == null) installRuntime(report);
        startServer(report);
        if (status().modelReady()) {
            report.accept("gpt-oss 20B is already ready.");
            return;
        }
        if (baseModelPresent()) {
            report.accept("Using the downloaded gpt-oss 20B model…");
        } else {
            report.accept("Downloading gpt-oss 20B (about 13 GB; resumable)…");
            if (modelDownloader != null) modelDownloader.download(report);
            else {
                if (findExecutable() == null) installRuntime(report);
                Path executable = findExecutable();
                if (executable == null) throw new IOException("Ollama's command-line program could not be found. Install Ollama from ollama.com, then retry.");
                run(command(executable, "pull", BASE_MODEL), dataDirectory(), Duration.ofHours(3), report);
            }
        }
        report.accept("Preparing the local model for transcript summaries…");
        request("/api/create", Map.of("model", MODEL, "from", BASE_MODEL,
                "parameters", Map.of("num_ctx", CONTEXT_LENGTH), "stream", false), Duration.ofMinutes(5));
        if (!status().modelReady()) throw new IOException("The model context could not be verified. Please run setup again.");
        report.accept("gpt-oss 20B is ready. Summaries run on this Mac without API fees.");
    }

    private void startServer(Consumer<String> report) throws Exception {
        checkInterrupted();
        if (responds()) return;
        Path executable = findExecutable();
        if (executable == null) throw new IOException("Choose Set up local AI first to install the free local model.");
        Path directory = dataDirectory();
        Files.createDirectories(directory);
        Path log = directory.resolve("server.log");
        Process started;
        synchronized (LocalAiService.class) {
            if (responds()) return;
            report.accept("Starting local AI on this Mac…");
            ProcessBuilder builder = new ProcessBuilder(command(executable, "serve"));
            configure(builder, directory);
            builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            started = builder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(started), "stillword-local-ai-stop"));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean ready = false;
        try {
            while (System.nanoTime() < deadline) {
                checkInterrupted();
                if (responds()) { ready = true; return; }
                if (!started.isAlive())
                    throw new IOException("Local AI could not start. See " + log + " for details.");
                Thread.sleep(250);
            }
            throw new IOException("Local AI did not start within 30 seconds. See " + log + " for details.");
        } finally { if (!ready) stop(started); }
    }

    private boolean responds() throws InterruptedException {
        try { return request("/api/tags", null, Duration.ofSeconds(2)).get("models") instanceof List<?>; }
        catch (IOException | IllegalArgumentException exception) { return false; }
    }

    private boolean baseModelPresent() throws IOException, InterruptedException {
        try {
            request("/api/show", Map.of("model", BASE_MODEL), Duration.ofSeconds(10));
            return true;
        } catch (ApiException exception) {
            if (exception.statusCode == 404) return false;
            throw exception;
        }
    }

    private static final class ApiException extends IOException {
        private final int statusCode;
        ApiException(int statusCode) { super("Local AI returned HTTP " + statusCode + "."); this.statusCode = statusCode; }
    }

    private Map<?, ?> request(String path, Object payload, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.resolve(path)).timeout(timeout);
        if (payload == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ApiException(response.statusCode());
        Object value;
        try { value = Json.parse(response.body()); }
        catch (IllegalArgumentException exception) { throw new IOException("Local AI returned an unreadable response.", exception); }
        if (!(value instanceof Map<?, ?> result)) throw new IOException("Local AI returned an unexpected response.");
        if (result.get("error") instanceof String error) throw new IOException(error);
        return result;
    }

    static boolean hasRequiredContext(Map<?, ?> model) {
        if (!(model.get("parameters") instanceof String parameters)) return false;
        for (String line : parameters.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2 && parts[0].equals("num_ctx")) {
                try { return Integer.parseInt(parts[1]) >= CONTEXT_LENGTH; }
                catch (NumberFormatException ignored) { return false; }
            }
        }
        return false;
    }

    /** Searches source builds, preview .apps, normal Ollama installations, and PATH. */
    static Path findExecutable() {
        List<Path> candidates = new ArrayList<>();
        String override = System.getenv("STILLWORD_OLLAMA");
        if (override != null && !override.isBlank()) candidates.add(Path.of(override));
        for (Path root : searchRoots()) {
            candidates.add(root.resolve("tools/ollama/bin/ollama"));
            candidates.add(root.resolve("tools/ollama/ollama"));
            candidates.add(root.resolve("tools/ollama/Ollama.app/Contents/Resources/ollama"));
        }
        candidates.add(applicationDirectory().resolve("runtime/bin/ollama"));
        candidates.add(applicationDirectory().resolve("runtime/ollama"));
        Path data = dataDirectory();
        if (data.getParent() != null) {
            candidates.add(data.getParent().resolve("ollama/bin/ollama"));
            candidates.add(data.getParent().resolve("ollama/ollama"));
        }
        candidates.add(Path.of("/Applications/Ollama.app/Contents/Resources/ollama"));
        candidates.add(Path.of("/opt/homebrew/bin/ollama"));
        candidates.add(Path.of("/usr/local/bin/ollama"));
        for (String path : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
            if (!path.isBlank()) candidates.add(Path.of(path).resolve("ollama"));
        return candidates.stream().filter(path -> Files.isRegularFile(path) && Files.isExecutable(path)).findFirst().orElse(null);
    }

    private static List<Path> searchRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("user.dir")).toAbsolutePath());
        try { roots.add(Path.of(LocalAiService.class.getProtectionDomain().getCodeSource().getLocation().toURI())); }
        catch (Exception ignored) { }
        List<Path> ancestors = new ArrayList<>();
        for (Path root : roots) {
            for (int i = 0; root != null && i < 9; i++, root = root.getParent()) {
                ancestors.add(root);
                ancestors.add(root.resolve("Code"));
            }
        }
        return ancestors;
    }

    private static Path applicationDirectory() {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support", "Stillword", "local-ai");
    }

    static Path dataDirectory() {
        String override = System.getenv("STILLWORD_LOCAL_AI_DIR");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
        for (Path root : searchRoots()) {
            if (Files.isRegularFile(root.resolve("build.sh")) && Files.isDirectory(root.resolve("src/main/java")))
                return root.resolve("tools/local-ai");
        }
        return applicationDirectory();
    }

    private void installRuntime(Consumer<String> report) throws Exception {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (!os.contains("mac")) throw new IOException("Install Ollama from ollama.com for this operating system, then run setup again.");
        Path directory = dataDirectory();
        Files.createDirectories(directory);
        Path runtime = directory.equals(applicationDirectory()) ? directory.resolve("runtime")
                : directory.getParent().resolve("ollama");
        Files.createDirectories(runtime);
        Path archive = directory.resolve("ollama-darwin.tgz");
        report.accept("Downloading the official Ollama runtime (about 160 MB)…");
        run(List.of("/usr/bin/curl", "--fail", "--location", "--silent", "--show-error", "--connect-timeout", "20",
                "--max-time", "900", "--retry", "2", "https://github.com/ollama/ollama/releases/download/v"
                        + RUNTIME_VERSION + "/ollama-darwin.tgz", "--output", archive.toString()),
                directory, Duration.ofMinutes(16), report);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(archive)) {
            byte[] bytes = new byte[64 * 1024];
            int count;
            while ((count = input.read(bytes)) != -1) { checkInterrupted(); digest.update(bytes, 0, count); }
        }
        if (!RUNTIME_SHA256.equals(HexFormat.of().formatHex(digest.digest())))
            throw new IOException("The Ollama download did not match its official checksum. Please retry setup.");
        report.accept("Installing the verified local AI runtime…");
        run(List.of("/usr/bin/tar", "-xzf", archive.toString(), "-C", runtime.toString()), directory, Duration.ofMinutes(2), report);
        Files.deleteIfExists(archive);
        if (findExecutable() == null) throw new IOException("The Ollama runtime could not be found after installation.");
    }

    private static List<String> command(Path executable, String... arguments) {
        List<String> result = new ArrayList<>();
        result.add(executable.toString());
        result.addAll(List.of(arguments));
        return result;
    }

    static void configure(ProcessBuilder builder, Path directory) throws IOException {
        Files.createDirectories(directory.resolve("models"));
        builder.environment().put("OLLAMA_HOST", "127.0.0.1:11434");
        builder.environment().put("OLLAMA_MODELS", directory.resolve("models").toString());
        builder.environment().put("OLLAMA_CONTEXT_LENGTH", Integer.toString(CONTEXT_LENGTH));
        builder.environment().put("OLLAMA_NUM_PARALLEL", "1");
        builder.environment().put("OLLAMA_NO_CLOUD", "1");
        builder.environment().put("NO_COLOR", "1");
    }

    /** Bounded and interruptible subprocess, retaining only the most recent progress line. */
    static void run(List<String> command, Path directory, Duration timeout, Consumer<String> report) throws Exception {
        checkInterrupted();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        configure(builder, directory);
        Process process = builder.start();
        AtomicReference<String> latest = new AtomicReference<>("");
        Thread reader = new Thread(() -> {
            try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    String clean = cleanProgress(line);
                    if (!clean.isBlank()) latest.set(clean);
                }
            } catch (IOException ignored) { }
        }, "stillword-local-ai-progress");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime() + timeout.toNanos();
        String reported = "";
        try {
            while (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                checkInterrupted();
                if (System.nanoTime() >= deadline) throw new IOException("Local AI setup took too long. Retry to resume the download.");
                String message = latest.get();
                if (!message.isBlank() && !message.equals(reported)) { report.accept(message); reported = message; }
            }
            reader.join(1000);
            checkInterrupted();
            if (process.exitValue() != 0) throw new IOException("Local AI setup failed: " + latest.get());
        } finally { if (process.isAlive()) stop(process); }
    }

    static String cleanProgress(String value) {
        String clean = value.replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "").replaceAll("[\\p{Cntrl}&&[^\\t]]", "").strip();
        var download = java.util.regex.Pattern.compile("pulling [a-f0-9]+:\\s*(\\d+)%.*?([\\d.]+\\s*[KMGT]?B)/\\s*([\\d.]+\\s*[KMGT]?B)").matcher(clean);
        if (download.find()) return "Downloading local model: " + download.group(1) + "% (" + download.group(2) + " / " + download.group(3) + ")";
        if (clean.startsWith("pulling manifest")) return "Checking the model download…";
        return clean.length() <= 400 ? clean : clean.substring(clean.length() - 400);
    }

    private static void stop(Process process) {
        if (process == null) return;
        // Give Ollama a brief chance to save resumable download state before forcing exit.
        boolean interrupted = Thread.interrupted();
        List<ProcessHandle> children = process.descendants().toList();
        try {
            children.forEach(ProcessHandle::destroy);
            process.destroy();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { interrupted = true; }
            children.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static Consumer<String> reporter(Consumer<String> progress) { return progress == null ? message -> { } : progress; }
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Local AI setup was cancelled.");
    }

    public static void main(String[] arguments) throws Exception {
        LocalAiService service = new LocalAiService();
        if (arguments.length == 1 && arguments[0].equals("--status")) System.out.println(service.status().message());
        else service.setup(System.out::println);
    }
}
