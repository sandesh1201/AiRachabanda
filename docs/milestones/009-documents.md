# Milestone 009 — Local documents

Attachments are chosen through the Android document picker or a share intent and copied into app storage. The supported local extractors are PDF text, DOCX paragraphs and tables, XLSX cells, PPTX slides and notes, and plain text, CSV, JSON, and Markdown. JPEG, PNG, and WEBP files get a bounded preview and recorded dimensions. Orientation is applied for JPEG files that carry it.

PDF page rendering uses the platform renderer. Office files are read as ZIP and XML with size, entry, and path limits. Formulas and macros are not executed. Encrypted files are refused. External links inside a document are not opened.

ML Kit Latin text recognition was not added. Its Play services path downloads a model of about 4 MB on first use, and a bundled model would increase the APK. Neither download was approved. OCR is reported as not installed. An image preview is not a description of the picture.

Extracted text is stored as untrusted document content with a page, sheet, cell, slide, or paragraph reference. A prompt receives that text only after a consent checkbox. The original file stays on the device. No vision request is sent. Document generation is not part of this milestone.
