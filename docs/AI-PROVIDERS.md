# AI website handoff

Stillword can prepare a summary prompt for an AI website where you already have an account or subscription. Use the dropdown in the **Summary** panel to select **ChatGPT — Free / subscription**, or **Claude**, **Gemini**, **Copilot**, or **Perplexity — Your plan, browser**. Choose **Custom AI…** for another HTTPS website; its settings connection type is **AI website — Free / subscription**. Selecting a dropdown item changes configuration only and sends no transcript.

Click **Prepare prompt**, then **Copy & open AI** to copy the full prompt and open the site, or **Copy prompt** to copy it alone. Choose the actual model on the website, paste and submit the prompt, then copy its reply and use **Paste summary** in Stillword. Normal paste into the editable Summary panel also works. This is a manual handoff; Stillword does not sign into accounts, choose the website's model, submit browser messages, or collect the reply automatically. The website's model picker and your account determine which models are available. Free access, subscriptions, and fees vary by provider and plan; **Your plan, browser** does not promise free access to every model.

Use the dedicated **Paste summary** button to import review markers and track the source/guidance from the prepared prompt. Directly replacing the panel by normal paste is a plain-text edit: it does not import markers or establish source provenance, and the panel shows **source not tracked**.

The prompt includes the entire current transcript and your summary guidance. It asks the model to choose a concise, complete length based on the source's breadth and complexity, with no fixed word target. For Q&A sources, it preserves actual questions and answers and prohibits invented exchanges. It also tells the model to omit the topics or sections you excluded. When your guidance excludes a meditation or recitation section, earlier answers discussing those subjects remain in scope unless you exclude them too. The source is encoded separately from the instructions. Model compliance still depends on the service, so review the result.

The prompt also requests `[[check: exact source phrase]]` for unclear source wording that deserves review. After **Paste summary**, a marked phrase becomes a gold clickable phrase when it matches timed transcript text. Click it to open the video at the source caption cue, review the wording, and correct the transcript if necessary. An unmatched phrase has no reliable timestamp link, and these suggestions are not a complete ambiguity check. The browser may require a separate click on Play.

After correcting the transcript or changing guidance, repeat **Prepare prompt**, submit the updated prompt on the website, and import its new answer with **Paste summary**. The existing summary is marked stale after source or guidance edits. Summary length remains automatic, without a page cap. Saved summaries are UTF-8 plain text; source timestamps and clickable-link metadata are not stored in the text file.

The website's model availability, subscription limits, context window, and privacy settings apply. Stillword does not truncate the transcript to fit an unknown website limit. For a long transcript, use the website's document upload feature if available, shorten the selected video interval, or use Stillword's local/API summarization workflow.

## Preset websites

Addresses checked against the providers' own live pages on September 28, 2026:

| Provider | Website |
| --- | --- |
| ChatGPT | [chatgpt.com](https://chatgpt.com/) |
| Claude | [claude.ai/new](https://claude.ai/new) |
| Gemini | [gemini.google.com/app](https://gemini.google.com/app) |
| Copilot | [copilot.microsoft.com](https://copilot.microsoft.com/) |
| Perplexity | [perplexity.ai](https://www.perplexity.ai/) |

## Subscriptions and direct APIs

An AI website account is not interchangeable with an API key. This browser workflow uses the user's existing website access and does not require an API key. The separate **GPT-6 Luna — Fees, API** and **GPT-5 — Fees, API** dropdown choices use paid OpenAI API requests; ChatGPT subscriptions do not pay for those requests. The **gpt-oss 20B — Free, local** choice runs the downloaded open-weight model on the Mac without API fees. Other custom API models display **Fees may apply** and need the credentials and billing arrangement required by their provider. In API settings, the editable model dropdown accepts an exact provider model ID; the fee label is never sent as part of that ID.

- OpenAI documents [ChatGPT plan access and API-key pricing separately](https://learn.chatgpt.com/docs/pricing).
- Anthropic explains that [Claude paid chat plans and the Claude API/Console are separate products](https://support.claude.com/en/articles/9876003-i-have-a-paid-claude-subscription-pro-max-team-or-enterprise-plans-why-do-i-have-to-pay-separately-to-use-the-claude-api-and-console). Certain developer tools have their own subscription arrangements; this does not turn a browser subscription into a general API key.
- Google describes [Gemini API projects, usage tiers, and billing](https://ai.google.dev/gemini-api/docs/billing/).
- Perplexity distinguishes [website plans from its separately billed API platform](https://www.perplexity.ai/help-center/en/articles/11187416-which-perplexity-subscription-plan-is-right-for-you).

Provider offerings change. The browser handoff avoids assuming that one subscription grants direct programmatic access to every provider.
