# Milestone 010 — Local document generation

Documents are rendered on the device from a structured model. The model holds headings, paragraphs, lists, tables, charts, images, locally checked arithmetic, spreadsheet formulas, source references, and slide layouts. File writers are separate from that model.

PDF files use a local PDF writer with standard fonts, page numbers, tables, and bar marks for supplied numbers. DOCX, XLSX, and PPTX are Office Open XML packages. CSV, TXT, Markdown, and JSON are text files with the matching content. No external conversion service is used.

Spreadsheet formulas are limited to SUM, AVERAGE, MIN, MAX, COUNT, ABS, PRODUCT, ROUND, and arithmetic on cell references. A cached number is written only when that local evaluator returns a value. Text that begins with `=`, `+`, `-`, or `@` stays text. Macros and external workbook connections are not written.

Ten local templates are available. A conversation can include stored messages, attachment excerpts, and objections. Missing evidence is stated. Nothing is added in its place. The file says it was rendered locally and is not an AI provider response.

Microsoft Office was not installed. Office files were checked for signatures, required parts, and relationships, then read back with the local extractor. That is structural validation. Visual Office compatibility is not claimed. PDF page previews use the Android page renderer when it accepts the file.
