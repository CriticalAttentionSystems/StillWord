# Free local AI

Stillword uses OpenAI's open-weight **gpt-oss:20b** through Ollama. Once downloaded, summaries run on your Mac without an API key or API fees. The model download is approximately 13 GB, in addition to the runtime. Allow at least 20 GB of free disk space. OpenAI recommends at least 16 GB of GPU/unified memory; a 32 GB Apple silicon Mac provides more room for macOS and other applications. Quality and speed depend on your transcript and hardware; this is not a guarantee that the local model will outperform GPT-4.1 mini.

The download on the development Mac is complete, and a live synthetic-transcript summary with exclusion guidance passed. Its status and sample output are in `tools/local-ai/setup-status.txt` and `tools/local-ai/smoke-summary.txt`. A live run on the requested Q&A's saved speech transcript completed in 197 seconds with all nine topics represented, but substantive factual errors remain; local summaries need source review. See `build/validation/section-summary-04/content-audit.md`. The Summary panel offers Auto, Brief, and Detailed. All three remain editable and have no fixed page cap.

In the Summary panel's model dropdown, choose **gpt-oss 20B — Free, local**, then click **Set up** beside it. The corresponding connection type in **AI & settings** is **Local model — Free**. Setup can be cancelled; rerunning it resumes the model download. A terminal alternative is:

```sh
./setup-local-ai.sh
```

The same dropdown also offers **GPT-6 Luna — Fees, API** and **GPT-5 — Fees, API**. Those choices use paid API access with your own key; they do not download those models to your Mac, and a ChatGPT subscription does not pay for their API usage. Browser choices use your website account instead: choose the available model on that website, submit the prepared prompt yourself, then paste its answer back into Stillword. **Custom AI…** opens settings for another local server, model, provider, or website. Selecting a dropdown item does not send the transcript.

For a one-time background installation that can finish after the terminal closes, run `./setup-local-ai-background.sh` (this optional developer script also needs Python 3). It also generates a synthetic transcript summary through the production summary service and verifies that an excluded announcement is absent. Progress is written to `tools/local-ai/setup.log`, completion or failure to `tools/local-ai/setup-status.txt`, and the successful sample to `tools/local-ai/smoke-summary.txt`. This is an opt-in live check and is not part of the normal offline test suite. The installer exits after the check; there is no recurring monitor or scheduled task.

The macOS setup installs a pinned official Ollama runtime and verifies its SHA-256 before extraction. No administrator password, login item, or system service is added. Stillword starts the server on demand, binds it to `127.0.0.1:11434`, disables Ollama cloud features for the server it launches, and stops that server when Stillword quits. An already-running Ollama server is reused and is not stopped or reconfigured.

The app prepares **stillword-gpt-oss**, a local alias of `gpt-oss:20b` with `num_ctx 32768`. The context accommodates the app’s bounded discussion-section requests; the app no longer relies on one large summary request. This changes its context setting, not its downloaded model weights, and does not duplicate the large model file. Setup checks whether the base model already exists and reuses it when upgrading the alias, without requesting another model pull. Stillword checks the context before summarization so an existing server's shorter default cannot silently discard transcript text.

Upgrading from the earlier 16,384-token alias requires **Set up** once; the downloaded base weights are reused. A larger context can consume more memory and increase processing time. The current 32 GB development Mac provides headroom, but performance depends on the Mac, other running applications, and transcript length; the larger context is not a guarantee that every Mac can run this model comfortably.

For source builds and the preview app kept under this project directory, runtime files are in `tools/ollama`, and models/logs are in `tools/local-ai`. These generated files are excluded from Git. For an independently installed app, the location is `~/Library/Application Support/Stillword/local-ai`. Ollama may also create its normal local identity files under `~/.ollama`. If another Ollama server is already running, that server's own model location is used.

`STILLWORD_OLLAMA` can point to an existing executable, and `STILLWORD_LOCAL_AI_DIR` can override the model/log directory when Stillword starts its own server. A normal Ollama installation in `/Applications`, Homebrew, or `PATH` is also supported.

Current Ollama macOS releases require macOS 14 or later. Apple silicon supports GPU acceleration; Intel Macs use CPU inference, which may be slow. The planned distributable must offer other AI options for Macs that cannot run this model comfortably.

Official references:

- [OpenAI: run gpt-oss locally with Ollama](https://developers.openai.com/cookbook/articles/gpt-oss/run-locally-ollama)
- [Ollama macOS requirements and file locations](https://docs.ollama.com/macos)
- [Ollama context and server configuration](https://docs.ollama.com/faq)
- [Ollama Modelfile parameters](https://docs.ollama.com/modelfile)
- [Pinned Ollama v0.34.4 runtime release](https://github.com/ollama/ollama/releases/tag/v0.34.4)
