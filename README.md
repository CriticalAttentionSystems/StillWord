# StillWord

A Java desktop app for reading, editing, and saving YouTube transcripts and AI summaries. The interface features a watercolor of Bhavana Society's main meditation hall, with editable **Transcript** and **Summary** panels.

**Run on this Mac**

Open this project folder (`/Users/veronique/BASE/Code/StillWord`) in NetBeans using **File → Open Project**. Open the folder containing `pom.xml`, not just `src/main/java`. Choose **Clean and Build**, then **Run Project**. The main class is `org.stillword.StillWord`.

The Java sources are in `src/main/java/org/stillword`, the artwork is in `src/main/resources/images`, and regression tests are in `src/test/java/org/stillword`. The project uses Java 17 language/API compatibility and works with the installed JDK 25. Maven packages a runnable JAR:

```sh
mvn clean package
java -jar target/StillWord.jar
```

`mvn test` runs all fifteen offline regression suites. `mvn exec:java` also opens the app. To package without running tests, use `mvn -DskipTests package`. Build plugins may need an internet connection on their first Maven use. The shell commands below remain available if you prefer to build with the JDK alone.

The port includes the existing locally installed caption, AI, and speech tools under the Git-ignored `tools` directory. These are development runtime files, not Java source or distributable bundles. Your original source project and existing installed/preview app remain separate; rebuilding this project creates a new JAR. DMG packaging remains a later step.

Open `run.command` in this folder, or run these commands from the StillWord project directory:

```sh
./run.command
```

The launcher builds the app before opening it, so it needs a **JDK 17 or newer**. Java 25.0.2, yt-dlp 2026.07.04, and Node.js 2.9.3 were available on the development Mac. The application uses only the Java standard library. Maven is configured for this project; the shell build also works without Maven or extra Java libraries.

To run an already built JAR, a **Java 17 or newer runtime** is enough:

```sh
java -jar build/StillWord.jar
```

Caption and audio retrieval need a current `yt-dlp`. The app prefers its project-local binary when present, then detects common installation locations including Homebrew; an explicit executable path in **AI & settings** takes precedence. Older versions can retrieve captions yet fail audio downloads with HTTP 403, so keep yt-dlp current. The development installation now includes the verified macOS binary for yt-dlp 2026.08.19 under `tools/yt-dlp`. Node.js is recommended for YouTube's JavaScript challenges and is enabled by default in yt-dlp. See the [official yt-dlp dependencies](https://github.com/yt-dlp/yt-dlp#dependencies). On another Mac with Homebrew, install these caption dependencies with:

```sh
brew install yt-dlp deno
```

**Use the app**

1. Paste a YouTube video link and choose a transcript source. **YouTube captions** prefers publisher-provided English captions and falls back to automatic English captions. **Whisper · Free local** transcribes the video’s audio on your Mac with Whisper large-v3-turbo instead of reusing the captions. Watch, share, Shorts, and live-video links are accepted. Caption mode downloads only captions and metadata; local speech mode temporarily downloads the audio, without downloading the video.
2. To extract part of a talk, enable **Select a portion**, then enter a start and optional end time. Use seconds, `MM:SS`, or `HH:MM:SS`; a blank end means the video end. Caption selection keeps whole subtitle cues that overlap the range, so a few words can fall outside the exact boundary. Local speech mode physically clips the converted audio to the selected range before recognition; it still downloads the full audio first. A timestamp embedded in the pasted URL does not set this range.
3. **Click any word in the transcript to show its video link.** A strip below the editor displays **▶ Play video at 12:34** and a passage excerpt. Click that link to open YouTube at the source cue, or choose **Copy link** to copy the full timestamped URL. Clicking or selecting text alone keeps normal editing behavior and does not open a browser. No AI ambiguity flag is required. Loaded transcripts retain their source timestamps through ordinary edits; pasted text without timing metadata has no link. **Play passage**, **⌘/Ctrl-click**, and **Play video here** remain available as shortcuts. The link seeks to the cue’s start, not an independently aligned word, and the browser may require you to press Play. Copy, paste, select all, and undo/redo are available; on macOS use **⌘Z** and **⇧⌘Z**. You can also paste your own transcript, but pasted plain text does not supply caption timestamps.
4. Add optional **Summary guidance**, such as “Omit introductions, announcements, and the closing Q&A. Focus on practical teachings.” Choose **Auto**, **Brief**, or **Detailed** in the **Summary** panel, then choose an AI model or website; each model choice identifies local use, API fees, or your website plan. For a local/API model, **Summarize** processes the current edited transcript, applying guidance throughout the summary process. For a website, use **Prepare prompt** and the browser workflow below.
5. Review the summary. When the AI flags an unclear source phrase and the app can map that exact phrase to a timed caption, it appears as a **gold clickable phrase**. Click it to review the video at the source cue and show the corresponding video link in the transcript’s link strip, then correct the editable transcript if necessary. These are review suggestions; the AI may miss ambiguity or flag text that is actually correct. An unmatched phrase cannot provide a reliable timestamp link.
6. After correcting the transcript or changing the guidance or summary style, choose **Redo summary** to regenerate from the current text, guidance, and style using the currently selected AI. A summary with tracked source text shows a stale indicator after these inputs change. In browser mode, prepare a new prompt, submit it on your AI website, then import the new answer with **Paste summary**. Directly replacing the Summary panel with a normal paste shows **source not tracked**; use **Paste summary** to import browser review markers and the prepared prompt's source provenance.
7. **Every summary style remains editable by hand.** Switching between Auto, Brief, and Detailed preserves your text; only an explicit Summarize or Redo summary replaces it. Use **Save transcript…** or **Save summary…** to choose a directory and filename in the native save dialog. Saving writes the editor's current text, including your changes. Files use UTF-8 plain text with a `.txt` extension; caption timestamps and clickable-link metadata are not saved. The app asks before replacing a file or unsaved panel text and remembers the last save directory.

The summary styles control length and organization: **Auto** adapts to the source, **Brief** gives a short overview of central points, and **Detailed** preserves the teaching, distinctions, useful examples, and practical steps. Lecture videos receive topical summaries, including when the speaker uses rhetorical questions. Q&A videos retain actual audience questions and answers. The fetched title identifies the video format; the AI is instructed not to invent questions. There is no enforced word or page cap. Excluding a meditation or recitation section preserves earlier answers about those topics unless your guidance also excludes those answers. Both local/API summaries and browser prompts use this approach. Review and edit the result as needed. Changing the transcript, guidance, or style marks the existing summary as needing an update. **Cancel** stops an active app request; current panel text is retained if an operation fails or is cancelled.

**Transcribe the audio locally**

If the YouTube captions contain nonsense or badly misheard terms, choose **Whisper · Free local** to generate a fresh transcript from the audio. This is a separate speech model from the AI that writes the summary. It does not ask the summary model to guess what broken captions meant.

Select **Set up speech** beside the transcript-source dropdown before the first use. Setup builds the pinned whisper.cpp **1.9.4** engine for the current Mac and downloads **Whisper large-v3-turbo**, approximately **1.62 GB**. Allow at least **3 GB free disk space** for the model and setup work. The source build needs **CMake** and **Apple Command Line Tools**; setup explains what is missing rather than installing system tools automatically. For a terminal setup, run from the StillWord project directory:

```sh
./setup-local-speech.sh
```

CMake can be installed from [cmake.org](https://cmake.org/download/) or with `brew install cmake`; Apple Command Line Tools can be requested with `xcode-select --install`. These are build requirements. The resulting speech engine uses macOS system frameworks and does not require CMake during transcription. No Python, ffmpeg, subscription, API key, or speech-service fee is required.

The verified engine and model stay under `tools/speech`; setup reuses valid existing files. Recognition runs locally with CPU and Metal support. The app downloads the video’s audio through yt-dlp, converts it to mono PCM using macOS `afconvert`, clips any selected range before recognition, recognizes English speech, and removes the temporary audio and transcript files after success, failure, or cancellation. Timestamp links continue to point into the original video, including when a time range was selected. Runtime and memory use depend on the Mac and talk length; a full talk can take substantial time.

Whisper can still mishear accents, names, and Pali words or produce text during silence. Click an unclear word and use its displayed video link, correct the transcript, and then **Redo summary**. Neither a cleaner-looking transcript nor a different summary style establishes that the words are accurate. An initial full local-audio test of the requested Q&A produced 4,237 words in about 9 minutes 34 seconds, but its last cue ran approximately 15 seconds past the selected endpoint. The app now clips the actual audio before recognition; a new 45:30–47:21.8 test ended at the requested time and excluded the closing announcement. Several Pali-related phrases improved, but garbled opening words and hallucinated repetitions remain; this is not an accuracy acceptance pass. See [speech setup implementation notes](docs/DEVELOPMENT.md#local-speech-recognition) and the [official whisper.cpp project](https://github.com/ggml-org/whisper.cpp/tree/v1.9.4).

**Choose how to summarize**

Use the model dropdown in the **Summary** panel:

| Dropdown choice | How it works and what it costs |
| --- | --- |
| **gpt-oss 20B — Free, local** — default | OpenAI's open-weight `gpt-oss:20b` runs on your Mac through Ollama, without an API key or API fees. |
| **GPT-6 Luna — Fees, API** or **GPT-5 — Fees, API** | Generates summaries through the paid OpenAI API using your API key. ChatGPT subscriptions do not pay for these API requests. |
| **ChatGPT — Free / subscription** | Opens ChatGPT for a manual prompt handoff using your free or paid website account. Choose the actual model within ChatGPT, subject to your plan. |
| **Claude**, **Gemini**, **Copilot**, or **Perplexity** — **Your plan, browser** | Uses the same manual handoff with your chosen website. Available models, free access, subscriptions, and limits depend on your account there. |
| **Custom AI…** | Opens settings for another model, provider, website, or local server. Custom API choices show **Fees may apply**. |

Selecting a dropdown item changes configuration only; it does not send the transcript or start a paid API request. **AI & settings** provides three underlying connection types: **Local model — Free**, **AI website — Free / subscription**, and **AI API — Fees may apply**.

For the default **gpt-oss 20B — Free, local** option, select **Set up** beside the dropdown. Setup downloads the Ollama runtime if needed and the approximately **13 GB model**. Allow at least **20 GB free disk space** and **16 GB GPU/unified memory** for the model; 32 GB gives macOS and other applications more room. The runtime download is approximately 160 MB. Setup is cancellable, and retrying resumes the model download. A terminal alternative, run from the StillWord project directory, is:

```sh
./setup-local-ai.sh
```

Local summaries process bounded discussion sections, with visible phase and elapsed-time updates. They retain the complete source for processing and assemble the resulting drafts in order. Cancel stops the request; an error leaves the existing editor text intact. AI output still needs human review, especially when speech or speaker changes are unclear.

Progress identifies the current passage and refreshes approximately every two seconds while the local AI works. An inactive request stops after 120 seconds, and each request has an eight-minute overall limit. **Cancel** stops the request; timeout, invalid output, or cancellation keeps the existing transcript and summary. No partial streamed draft replaces the Summary panel. Long talks need several requests, so the per-request limits are not an overall time promise. The download and earlier synthetic check are complete; acceptance of the new workflow on the requested real Q&A remains a separate content review.

For a **browser** choice, select the website in the dropdown or use **Custom AI…** to enter another HTTPS website. Click **Prepare prompt**, then **Copy & open AI** to copy the complete transcript and guidance and open the site, or **Copy prompt** to copy without opening it. Choose an available model on that website, then paste and submit the prompt yourself. Copy its answer, return to Stillword, and choose **Paste summary**. The prompt asks the AI to mark an unclear source phrase as `[[check: exact source phrase]]`; imported markers support the same gold phrase links when they match timed source text. You can also paste directly into the Summary panel. Stillword does not sign in, submit messages, retrieve replies, or choose the website's model automatically. The provider's website limits and privacy settings apply. A very long transcript may exceed that website's input limit; Stillword does not truncate it. See [website handoff and provider references](docs/AI-PROVIDERS.md).

For an **API** choice, enter your API key in **AI & settings**. Settings offer OpenAI, Anthropic, Gemini, OpenRouter, and custom provider presets. The editable **Model — choose or enter an available model ID** dropdown lists familiar OpenAI choices with fee labels and accepts any exact model ID enabled for your provider account. Its editable field and API requests use the raw ID, such as `gpt-6-luna` or `gpt-5`, without the display label. OpenAI-compatible services use Chat Completions; Anthropic uses its Messages API. A base URL is expanded to `/chat/completions` or `/messages` as appropriate, and complete endpoint URLs are also accepted. The OpenAI API preset uses `https://api.openai.com/v1` with `gpt-6-luna`; model and endpoint remain editable. Other compatible providers and local servers can be configured with their documented endpoint, matching protocol, and exact model name. Remote endpoints require HTTPS; HTTP is allowed only on loopback. Browser subscriptions and API access are separate arrangements, so a website login is not an API key.

API keys stay in memory for the current app session. For the standard OpenAI endpoint, the app can also read `OPENAI_API_KEY` from its launch environment. There is no bundled or hidden API key. Model availability, context limits, fees, and summary quality depend on the selected provider.

**Data and limitations**

- Transcript extraction contacts YouTube through yt-dlp. Caption mode downloads English subtitles and metadata; local speech mode downloads audio even when English captions are absent, then recognizes it on your Mac. Temporary media, captions, and diagnostic files are cleaned up after the operation. Neither mode guarantees accurate wording. Sign-in-restricted videos are unsupported. Existing yt-dlp configuration and plugins are deliberately ignored.
- Clicking or selecting any timed transcript word reveals a visible video link and copy action. Playback uses the retained caption or speech cue start, rounded down to the whole second in the YouTube URL; it is not word-level audio alignment. Timestamp offsets follow ordinary edits within the loaded transcript; a newly pasted transcript has no attached timing data. Gold review phrases require an exact match to timed source text and are suggestions rather than a complete error check. Opening a link does not guarantee that the browser will start playback automatically.
- In local/API mode, **Summarize** sends the current transcript and your guidance to the indicated endpoint. Long transcripts require multiple requests. No transcript is sent on launch, when selecting a model, while editing, or while saving. **Set up** downloads software/model files and checks local model readiness. Local summarization uses your Mac's server.
- In subscription mode, **Prepare prompt** builds the prompt locally. The copy buttons put it on the system clipboard. Opening a website does not attach the transcript to its URL; you submit the prompt yourself. **Paste summary** reads the answer you copied, and the result remains editable.
- AI mode, endpoints, models, protocol, website, transcript source, summary style, caption language, executable path, and last save directory are stored in Java Preferences. API keys, transcripts, summaries, and guidance are not stored there. Text files are written only when you choose a save button; Copy link and browser handoff also use the clipboard. Closing the app discards unsaved panel text.
- The app does not silently discard long transcript sections. Sources over 800,000 characters or guidance over 4,000 characters are rejected before sending. The AI is instructed to treat transcript text as source material rather than commands. Review generated summaries for accuracy.

The monastery scene is AI-generated artwork based on a historical photograph of Bhavana Society's main meditation hall. This is an independent personal tool with no implied endorsement.

The monastery was generated with the built-in ImageGen tool. Its final prompt and provenance are saved in [monastery-source.md](src/main/resources/images/monastery-source.md), beside the bundled `monastery.png` asset. A [rendered interface preview](docs/interface-preview.png) is included.

The final section-based local run summarized the existing 4,237-word Whisper transcript of the requested Q&A in **197 seconds**, using ten completed requests. All nine Q&A topics appeared and the closing practice/recitation was excluded. This verifies completion after the earlier ten-minute timeout, not factual accuracy: the local model still endorsed a premise the speaker did not endorse, misstated the number of hindrances, and omitted a practical step. Review against the source before relying on a summary; changing its style does not correct these errors. The [current populated preview](docs/transcript-link-preview.png) displays the unedited local Auto draft and the visible video link. The earlier review preview uses a manually reviewed sample. Playback links, manual editing, Redo summary, and other configured AI providers support the review process.

**Build and test**

```sh
./build.sh
./test.sh
```

The build creates `build/StillWord.jar`. The test runner covers caption extraction and cue offsets, speech-process orchestration, summarization protocols and styles, browser prompt handoff, local AI readiness/process handling, connection routing, model/fee selection, and application helpers. It uses fixtures, fake caption processes, and localhost HTTP servers, without paid API calls or model downloads. The suites also cover ordinary-click video links, validated topic boundaries, complete contiguous source coverage, and local streamed-response handling. Consult the recorded regression output for the latest completed run; a passing mock test does not establish live summary accuracy. Each suite prints its check count. See [development and verification notes](docs/DEVELOPMENT.md) for coverage, design details, and a manual timestamp-review checklist.

Live caption extraction and a two-minute range were previously verified with a Bhante G talk. The latest caption-parser repair recovers text that followed YouTube’s whitespace-only display lines and avoids deleting ordinary repeated words. On the retained Q&A captions it recovers 64 previously dropped words, for 4,731 words in total; this repairs extraction but cannot correct words already misrecognized by YouTube. This does not establish that video seeking, gold phrase clicks, or the correction/Redo workflow have passed a live UI review. Native UI automation was unavailable because macOS Computer Use permission was not granted; these interactions, the native file chooser, and keyboard behavior should be tried during your review. Live hosted API access and browser submission depend on the user's own account and have not been exercised by the automated runner.

Mac installers are built with `./package-macos.sh` using the verified native dependency cache in `tools/distribution`. The output is two DMGs under `dist`: Apple silicon and Intel, targeting macOS 13 or later. Each includes Temurin Java 17, yt-dlp, Node.js, and a Whisper engine; optional models download separately into the user's Application Support folder. Users do not need Java, Homebrew, CMake, or Xcode to run the installed app. See [release build instructions](docs/MAC-RELEASE.md).

These initial installers have ad-hoc integrity signatures only. They are **not Developer ID signed or notarized**, so they are test builds rather than a completed public release. Public distribution requires Apple's Developer ID signing and notarization steps. Intel launch checks under Rosetta do not replace testing on physical Intel hardware or testing older supported macOS versions.

**Developer:** Veronique · [criticalattentionsystems.org](https://criticalattentionsystems.org). Report problems through **About → Report a bug**, or email **criticalattentionsystems@gmail.com**. **Copy bug report** provides a template containing only basic app and system version details.
