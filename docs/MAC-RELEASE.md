# StillWord Mac release

The release is version 1.0.0, developed by Veronique. Website: https://criticalattentionsystems.org. Bug reports: criticalattentionsystems@gmail.com. These values are in `src/main/resources/stillword.properties`; the About panel includes email and website buttons plus a copyable bug report.

## Build

From the project directory, run:

```sh
./test.sh
./package-macos.sh
```

The native cache is `tools/distribution`. To use another verified cache, pass `--native-dir /absolute/path`. Use `--arch arm64` or `--arch x64` to build one architecture. The default builds both on an Apple silicon Mac with Rosetta already installed. An Intel build host can build the Intel version; it cannot execute an Apple silicon JDK. Packaging requires Python 3.9 or later and Apple command-line tools. These are developer requirements only.

The script bundles only the application JAR, public artwork and metadata, Java runtime, downloader, Node.js, Whisper engine, speech setup script, and license notices. It does not copy developer preferences, API keys, transcripts, models, logs, or the entire project tools directory. DMGs are written under `dist`, together with SHA-256 checksums, a release manifest, and installation notes. Existing DMGs are not overwritten; use a new output directory or archive the older release first.

The runtime cache contains pinned Temurin17 arm64/x64 JDKs, official standalone Node.js arm64/x64 binaries, the universal yt-dlp executable, and a universal whisper.cpp engine built for macOS 13. Download URLs, checksums, build commands, and notices are recorded in its manifests. Preserve this cache to rebuild the same release. The source archive and build flags for Whisper are retained with it.

The installer deliberately excludes model weights. Installed speech setup copies its verified engine into `~/Library/Application Support/Stillword/speech` before downloading its model. Local summary setup uses `~/Library/Application Support/Stillword/local-ai`. The application bundle stays read-only.

## Signing and public release

The current Mac has no Developer ID Application signing identity. The generated files therefore say `unsigned` in their names and have local ad-hoc signatures only. They have not been notarized. This is not a completed public release, and Gatekeeper can block a downloaded copy.

For public release, install the developer's **Developer ID Application** certificate and private key in Keychain and configure an Apple notarization credential using the developer's own Apple account. Do not put passwords, private keys, or notarization secrets in this repository. Apple documents the process in [Notarizing macOS software before distribution](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution).

The release signing process must cover all nested Mach-O executables and libraries with the Developer ID identity and hardened-runtime settings appropriate to Java and Node.js. Sign the Whisper executable before generating its SHA-256 build receipt, then seal the app bundle. Submit the resulting artifact with `notarytool`, inspect its result, and staple the ticket after approval. Verify the final app and disk image with `codesign`, `spctl`, and `stapler`. Do not relabel the current ad-hoc build as signed or notarized, and do not disable macOS security to distribute it.

## Validation scope

Run the offline regression suite once after changes, inspect the About and main-window previews, and verify the actual mounted DMGs. Smoke checks should launch each packaged executable using its bundled JVM, resolve bundled yt-dlp and Node.js, and run speech setup into a fresh directory with spaces without downloading a model or writing into the bundle. Check the signature seals and the final speech receipt after packaging.

Minimum system metadata is macOS 13.0. Native binary deployment metadata is inspected, but the current host cannot establish that every feature works on every older macOS release. Test the final public release on a physical Intel Mac and the oldest supported macOS version. Local AI model/runtime requirements and speed vary by machine; installed API/browser options do not require a local model.
