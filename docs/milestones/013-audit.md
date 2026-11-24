# Milestone 013 audit

Baseline: `e642cc59779a1711e82e7eca7f00455454901423`.

Schema version 9 is unchanged. Quick uses 1 round and 1 AI request. Standard uses 3 rounds and 4 AI requests. Deep Research uses 4 rounds, 8 AI requests, 4 research requests, and 6 sources. Custom values are saved in preferences and clamped. They are not raised automatically.

Public research resolves a host, rejects any private or loopback address, and opens TLS to the approved address. The certificate is checked against the approved host name. Wikimedia, arXiv, and the Federal Register remain the connectors. No search-engine scraper was added.

PDF text uses the embedded Helvetica font. Characters outside Windows-1252 are omitted and named in the generation report. Telugu is not drawn. No font was downloaded.

Conversation text, attachments, and generated documents are not encrypted. Credential files use the Android Keystore. Deleting a conversation removes its imported files and generated files. Cache files older than one hour are removed at startup.

OCR is not installed. Adding it needs a separately approved on-device library and its language data. No vision model is called.

The release APK is assembled with the debug signing config so no release keystore is stored in the project. It was not published.
