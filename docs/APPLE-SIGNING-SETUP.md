# Signing StillWord for public distribution

The two current DMGs are unsigned test builds. Their local ad-hoc signatures verify file integrity; they do not identify you to Apple's Gatekeeper. They have not been notarized. No membership is needed to keep developing or locally testing StillWord.

To distribute with an Apple-verified Developer ID and notarization:

1. [Enroll in the Apple Developer Program](https://developer.apple.com/programs/enroll/). Apple lists the price as **US $99 per membership year**, or local currency where available. Choose individual enrollment if publishing in your own name, or organization enrollment if applying for an eligible legal entity. Follow Apple's identity and account verification steps yourself.
2. After membership is active, [create a Developer ID Application certificate](https://developer.apple.com/help/account/certificates/create-developer-id-certificates/). Use Xcode's certificate manager or the certificate workflow in your developer account. The certificate and its corresponding private key must be available in your Mac's Keychain. A Developer ID Application certificate is the relevant identity for this drag-and-drop `.app` distribution.
3. Return to this task after the certificate is installed. I can check that it is available, rebuild and sign both app bundles, and prepare them for Apple's notarization service. Any required account credentials should be entered into Apple's tools or stored securely in Keychain, not pasted into chat or committed to source control.
4. After Apple accepts the submission, the release needs its notarization ticket stapled and verified. Those final signed, notarized DMGs should replace the current unsigned test downloads.

Apple's process: [Notarizing macOS software before distribution](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution).

Your app's own developer and support details are already set to **Veronique**, **criticalattentionsystems.org**, and **criticalattentionsystems@gmail.com**. Apple's verified signing identity will use the legal identity associated with your membership.
