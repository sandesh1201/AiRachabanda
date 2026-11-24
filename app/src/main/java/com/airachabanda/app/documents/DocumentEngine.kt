package com.airachabanda.app.documents

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class GenerationOutcome(
    val status: String,
    val bytes: ByteArray,
    val report: String,
)

object DocumentEngine {
    fun generate(request: DocumentRequest, format: OutputFormat, cancelled: () -> Boolean = { false }): GenerationOutcome {
        val title = request.title.trim()
        if (title.isEmpty()) return GenerationOutcome("FAILED", ByteArray(0), "The document needs a name. Nothing was rendered.")
        if (DocumentTemplates.all.none { it.id == request.templateId }) {
            return GenerationOutcome("FAILED", ByteArray(0), "The template is not available. Nothing was rendered.")
        }
        if (cancelled()) return GenerationOutcome("CANCELLED", ByteArray(0), "Generation was cancelled before rendering.")
        val document = DocumentTemplates.build(request.copy(title = title, formatName = format.name))
        if (cancelled()) return GenerationOutcome("CANCELLED", ByteArray(0), "Generation was cancelled before the file was written.")
        val bytes = runCatching { DocumentWriters.render(document, format) }.getOrElse {
            return GenerationOutcome("FAILED", ByteArray(0), "The file could not be rendered. Nothing was saved.")
        }
        if (bytes.size > DocumentGenerationLimits.MAX_FILE_BYTES) {
            return GenerationOutcome("FAILED", ByteArray(0), "The rendered file exceeds ${DocumentGenerationLimits.MAX_FILE_BYTES} bytes. It was not saved.")
        }
        val structural = validate(bytes, format, document)
        if (!structural.ok) return GenerationOutcome("FAILED", ByteArray(0), structural.report)
        val readBack = DocumentExtractor.extract(bytes, "local.${format.extension}", format.mime)
        if (readBack.status == "FAILED") {
            return GenerationOutcome("FAILED", ByteArray(0), "The rendered file could not be read back. It was not saved. ${readBack.limitation}")
        }
        val text = readBack.chunks.joinToString("\n") { it.text }
        if (title.any { it.code in 32..126 } && !text.contains(title.take(40))) {
            return GenerationOutcome("FAILED", ByteArray(0), "The rendered file did not contain the document title. It was not saved.")
        }
        val pdfOmitted = if (format == OutputFormat.PDF) PdfText.omitted(title + "\n" + request.body) else ""
        val notes = listOf(document.limitations.joinToString(" "), pdfOmitted).filter { it.isNotBlank() }.joinToString(" ")
        val visual = if (format == OutputFormat.PDF) {
            "PDF text was read back from the file. Page layout uses the local PDF writer. Microsoft Office was not used."
        } else if (format == OutputFormat.DOCX || format == OutputFormat.XLSX || format == OutputFormat.PPTX) {
            "Office structure was checked locally. Microsoft Office was not installed, so visual Office compatibility was not claimed."
        } else {
            "The text file was read back as ${format.label}."
        }
        val status = if (notes.contains("was not rendered") || notes.contains("speaker notes") || pdfOmitted.isNotBlank()) "PARTIAL" else "GENERATED"
        return GenerationOutcome(status, bytes, listOf(structural.report, visual, notes).filter { it.isNotBlank() }.joinToString(" "))
    }

    private fun validate(bytes: ByteArray, format: OutputFormat, document: StructuredDocument): StructureReport {
        if (bytes.isEmpty()) return StructureReport(false, "The rendered file is empty.")
        return when (format) {
            OutputFormat.PDF -> validatePdf(bytes)
            OutputFormat.DOCX, OutputFormat.XLSX, OutputFormat.PPTX -> validateOffice(bytes, format, document)
            else -> if (bytes.toString(Charsets.UTF_8).isNotBlank()) StructureReport(true, "The text structure is present.") else StructureReport(false, "The text file is empty.")
        }
    }

    private fun validatePdf(bytes: ByteArray): StructureReport {
        val latin = bytes.toString(Charsets.ISO_8859_1)
        if (!latin.startsWith("%PDF-")) return StructureReport(false, "The PDF signature is missing.")
        if (!latin.contains("%%EOF")) return StructureReport(false, "The PDF ending is missing.")
        if (latin.contains("/Encrypt") || latin.contains("/JavaScript") || latin.contains("/JS")) {
            return StructureReport(false, "The PDF contains encryption or a script. It was not saved.")
        }
        val pages = Regex("/Type\\s*/Page(?!s)").findAll(latin).count()
        if (pages < 1) return StructureReport(false, "The PDF has no page.")
        return StructureReport(true, "PDF signature, catalog, and $pages page object(s) were checked. This is structural validation.")
    }

    private fun validateOffice(bytes: ByteArray, format: OutputFormat, document: StructuredDocument): StructureReport {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return StructureReport(false, "The Office file signature is missing.")
        }
        val entries = readZip(bytes) ?: return StructureReport(false, "The Office package could not be opened.")
        if (entries.keys.any { it.contains("..") || it.startsWith("/") || it.contains('\\') }) {
            return StructureReport(false, "The package contains an unsafe path.")
        }
        if (entries.keys.any { it.endsWith("vbaProject.bin", true) || it.contains("externalLink", true) }) {
            return StructureReport(false, "The package contains a macro or an external workbook connection.")
        }
        val required = when (format) {
            OutputFormat.DOCX -> listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")
            OutputFormat.XLSX -> listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/sharedStrings.xml")
            else -> listOf("[Content_Types].xml", "ppt/presentation.xml", "ppt/slides/slide1.xml", "ppt/theme/theme1.xml")
        }
        val missing = required.filter { it !in entries.keys }
        if (missing.isNotEmpty()) return StructureReport(false, "The package is missing ${missing.joinToString(", ")}.")
        if (format == OutputFormat.PPTX && document.slides.isNotEmpty()) {
            val expected = minOf(document.slides.size, DocumentGenerationLimits.MAX_SLIDES)
            val present = entries.keys.count { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            if (present < expected) return StructureReport(false, "The presentation has $present slide(s) and $expected were required.")
        }
        if (format == OutputFormat.XLSX && document.sheets.size > 1 && !entries.containsKey("xl/worksheets/sheet2.xml")) {
            return StructureReport(false, "The workbook is missing a second worksheet.")
        }
        val broken = brokenRelationships(entries)
        if (broken != null) return StructureReport(false, broken)
        return StructureReport(true, "Office signature, required parts, and relationships were checked. This is structural validation.")
    }

    private fun brokenRelationships(entries: Map<String, ByteArray>): String? {
        entries.filter { it.key.endsWith(".rels") }.forEach { (path, bytes) ->
            val xml = bytes.toString(Charsets.UTF_8)
            Regex("Target=\"([^\"]+)\"").findAll(xml).forEach { match ->
                val target = match.groupValues[1]
                val elementStart = xml.lastIndexOf("<Relationship", match.range.first).coerceAtLeast(0)
                val elementEnd = xml.indexOf("/>", match.range.last).let { if (it < 0) xml.length else it }
                val external = xml.substring(elementStart, elementEnd).contains("TargetMode=\"External\"")
                if (external) {
                    if (!target.startsWith("https://")) return "An external relationship is not a plain https URL."
                } else if (!target.startsWith("#")) {
                    val folder = path.substringBeforeLast('/', "")
                    val base = when {
                        folder == "_rels" -> ""
                        folder.endsWith("/_rels") -> folder.removeSuffix("/_rels")
                        else -> folder
                    }
                    val resolved = normalize(base, target)
                    if (resolved !in entries.keys) return "A relationship points at missing part $resolved."
                }
            }
        }
        return null
    }

    private fun normalize(base: String, target: String): String {
        val root = if (base.isBlank()) emptyList() else base.split('/')
        val parts = (root + target.split('/')).filter { it.isNotBlank() && it != "." }.toMutableList()
        val resolved = mutableListOf<String>()
        parts.forEach { part ->
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
            } else {
                resolved += part
            }
        }
        return resolved.joinToString("/")
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray>? = try {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            var total = 0
            while (count < 400) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                val data = zip.readBytes()
                total += data.size
                if (total > DocumentGenerationLimits.MAX_FILE_BYTES * 2) return null
                entries[name] = data
                count += 1
                zip.closeEntry()
            }
        }
        entries
    } catch (_: Exception) {
        null
    }

    private data class StructureReport(val ok: Boolean, val report: String)
}
