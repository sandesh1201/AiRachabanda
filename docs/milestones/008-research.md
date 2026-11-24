# Milestone 008 — Public research

Public research uses three connectors that do not require an account, a payment method, or purchased credits: Wikimedia, arXiv, and the Federal Register. OpenAlex was not connected because its current API uses an account key and a usage budget. A user may also submit one public HTTPS URL.

The app sends a request only after consent, and the request contains the query or that URL. Conversation history is not attached. Destinations are checked before the connection and after every redirect. Private, loopback, and link-local addresses are refused. Pages are size-capped, scripts are not executed, and the text is stored as untrusted data.

A stored source records the title, publisher, author, and publication date only when the response contains them. A successful retrieval is marked retrieved and not assessed. It is not treated as proof of a claim. The same canonical URL is one source.

No AI inference request is added.
