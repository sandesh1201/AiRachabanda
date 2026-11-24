package com.airachabanda.app.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

object DocumentLimits {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 200
    const val MAX_PAGES = 20
    const val MAX_SHEETS = 8
    const val MAX_SLIDES = 20
    const val MAX_CHARS = 12_000
    const val CHUNK_CHARS = 1_500
    const val MAX_CHUNKS = 40
    const val MAX_ATTACHMENTS = 8
    const val MAX_IMAGE_EDGE = 2_048
    const val PREVIEW_EDGE = 512
    const val EXPLANATION =
        "Files stay on this device. Each file can be 8 MB. A conversation can hold 8 files. " +
            "Extraction reads 20 PDF pages, 8 sheets, and 20 slides, up to 12,000 characters. " +
            "Sections past those limits are listed as unprocessed. OCR is not installed. " +
            "No model was downloaded."
}

data class ExtractBudget(
    val maxFileBytes: Int = DocumentLimits.MAX_FILE_BYTES,
    val maxUncompressedBytes: Int = DocumentLimits.MAX_UNCOMPRESSED_BYTES,
    val maxZipEntries: Int = DocumentLimits.MAX_ZIP_ENTRIES,
    val maxPages: Int = DocumentLimits.MAX_PAGES,
    val maxSheets: Int = DocumentLimits.MAX_SHEETS,
    val maxSlides: Int = DocumentLimits.MAX_SLIDES,
    val maxChars: Int = DocumentLimits.MAX_CHARS,
    val chunkChars: Int = DocumentLimits.CHUNK_CHARS,
    val maxChunks: Int = DocumentLimits.MAX_CHUNKS,
    val maxImageEdge: Int = DocumentLimits.MAX_IMAGE_EDGE,
)

data class DocumentChunk(
    val ordinal: Int,
    val reference: String,
    val kind: String,
    val method: String,
    val text: String,
    val processed: Boolean,
    val limitation: String,
)

data class ExtractionResult(
    val format: String,
    val mimeType: String,
    val status: String,
    val limitation: String,
    val chunks: List<DocumentChunk>,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val previewPng: ByteArray?,
)

fun interface ExtractControl {
    fun cancelled(): Boolean
}

object OnDeviceOcr {
    const val INSTALLED = false
    const val MODEL_DOWNLOADED = false
    const val DISCLOSURE =
        "ML Kit Latin text recognition can run on the device after a Play services model download of about 4 MB, " +
            "or with a bundled model. Neither was installed. No image is sent off the device. " +
            "OCR reads characters. It does not interpret charts, diagrams, objects, or the meaning of a picture."
}

object DocumentExtractor {
    private val stopped = ExtractControl { false }

    fun extract(
        bytes: ByteArray,
        filename: String,
        declaredMime: String,
        control: ExtractControl = stopped,
        budget: ExtractBudget = ExtractBudget(),
    ): ExtractionResult {
        if (control.cancelled()) return cancelled()
        if (bytes.size > budget.maxFileBytes) {
            return failed("UNSUPPORTED", "application/octet-stream", "The file exceeds the ${budget.maxFileBytes} byte limit. It was not extracted.")
        }
        if (bytes.isEmpty()) {
            return failed("UNSUPPORTED", "application/octet-stream", "The file is empty. Nothing was extracted.")
        }
        val sniffed = sniff(bytes, filename)
        val mismatch = extensionMismatch(filename, sniffed.format)
        return when (sniffed.format) {
            "PDF" -> pdf(bytes, mismatch, control, budget)
            "ZIP" -> office(bytes, filename, mismatch, control, budget)
            "PNG", "JPEG", "WEBP" -> image(bytes, sniffed, mismatch, control, budget)
            "JSON" -> structuredText(bytes, "JSON", "application/json", "JSON", mismatch, control, budget)
            "CSV" -> structuredText(bytes, "CSV", "text/csv", "CSV", mismatch, control, budget)
            "MARKDOWN" -> structuredText(bytes, "MARKDOWN", "text/markdown", "MARKDOWN", mismatch, control, budget)
            "TEXT" -> structuredText(bytes, "TXT", "text/plain", "TEXT", mismatch, control, budget)
            else -> failed("UNSUPPORTED", declaredMime.ifBlank { "application/octet-stream" }, "The file format is not supported. Nothing was extracted.")
        }
    }

    private fun sniff(bytes: ByteArray, filename: String): Sniff {
        if (bytes.size >= 5 && bytes.copyOfRange(0, 5).decodeToString() == "%PDF-") return Sniff("PDF", "application/pdf")
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes.copyOfRange(1, 4).decodeToString() == "PNG") {
            return Sniff("PNG", "image/png")
        }
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return Sniff("JPEG", "image/jpeg")
        }
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP") {
            return Sniff("WEBP", "image/webp")
        }
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) return Sniff("ZIP", "application/zip")
        val extension = extensionOf(filename)
        val sample = bytes.take(64).toByteArray()
        val head = runCatching { decodeText(sample).first.trimStart() }.getOrDefault("")
        if (head.startsWith("{") || head.startsWith("[")) return Sniff("JSON", "application/json")
        return when (extension) {
            "csv" -> Sniff("CSV", "text/csv")
            "json" -> Sniff("JSON", "application/json")
            "md", "markdown" -> Sniff("MARKDOWN", "text/markdown")
            "txt", "text" -> Sniff("TEXT", "text/plain")
            else -> {
                val printable = sample.count { byte -> byte.toInt() == 9 || byte.toInt() == 10 || byte.toInt() == 13 || byte in 32..126 }
                if (sample.isNotEmpty() && printable * 100 / sample.size > 85) Sniff("TEXT", "text/plain") else Sniff("UNKNOWN", "application/octet-stream")
            }
        }
    }

    private fun pdf(bytes: ByteArray, mismatch: String, control: ExtractControl, budget: ExtractBudget): ExtractionResult {
        val latin = bytes.toString(Charsets.ISO_8859_1)
        if (latin.contains("/Encrypt")) {
            return failed("PDF", "application/pdf", join(mismatch, "The PDF is encrypted. Protection was not bypassed. Nothing was extracted."))
        }
        val script = latin.contains("/JavaScript") || latin.contains("/JS")
        val pageCount = Regex("/Type\\s*/Page(?!s)").findAll(latin).count().coerceAtLeast(0)
        val streams = pdfStreams(bytes, budget)
        if (control.cancelled()) return cancelled("PDF", "application/pdf")
        val texts = mutableListOf<String>()
        var imageStreams = 0
        streams.forEach { stream ->
            if (stream.containsImage) imageStreams += 1
            val text = pdfText(stream.bytes)
            if (text.isNotBlank()) texts += text
        }
        val limitations = mutableListOf<String>()
        if (mismatch.isNotBlank()) limitations += mismatch
        if (script) limitations += "Embedded scripts were not executed."
        if (texts.isEmpty() && (imageStreams > 0 || pageCount > 0 && latin.contains("/Image"))) {
            limitations += "No text was found. The PDF appears scanned or image-only. ${OnDeviceOcr.DISCLOSURE}"
            return result("PDF", "application/pdf", "PARTIAL", limitations.joinToString(" "), emptyList())
        }
        if (texts.isEmpty()) {
            return failed("PDF", "application/pdf", join(mismatch, "The PDF text could not be read. Nothing was invented."))
        }
        val chunks = mutableListOf<DocumentChunk>()
        val visible = texts.take(budget.maxPages)
        visible.forEachIndexed { index, text ->
            if (control.cancelled()) return cancelled("PDF", "application/pdf", chunks)
            chunks += splitChunks(chunks.size, "page ${index + 1}", "ORIGINAL", "PDF_TEXT", text, "", budget, chunks)
        }
        if (texts.size > budget.maxPages || pageCount > budget.maxPages) {
            val unread = maxOf(texts.size, pageCount) - budget.maxPages
            chunks += limitChunk(chunks.size, "pages ${budget.maxPages + 1}-${maxOf(texts.size, pageCount)}", "Those pages were not extracted.")
            limitations += "$unread page(s) were not extracted."
        }
        val status = if (limitations.any { it.contains("not extracted") || it.contains("scripts") }) "PARTIAL" else "EXTRACTED"
        return result("PDF", "application/pdf", status, limitations.joinToString(" "), chunks)
    }

    private fun office(bytes: ByteArray, filename: String, mismatch: String, control: ExtractControl, budget: ExtractBudget): ExtractionResult {
        val zip = readZip(bytes, budget) ?: return failed("ZIP", "application/zip", "The archive could not be read. Nothing was extracted.")
        if (zip.error != null) return failed("ZIP", "application/zip", zip.error)
        if (control.cancelled()) return cancelled()
        val names = zip.entries.keys
        if (names.any { it.equals("EncryptionInfo", true) || it.equals("EncryptedPackage", true) }) {
            return failed("OFFICE", officeMime(filename), "The document is encrypted. Protection was not bypassed. Nothing was extracted.")
        }
        val macro = names.any { it.endsWith("vbaProject.bin", true) }
        val doctype = zip.entries.values.any { entry -> entry.toString(Charsets.ISO_8859_1).contains("<!DOCTYPE", ignoreCase = true) }
        if (doctype) {
            return failed("OFFICE", officeMime(filename), "The document contains a document type declaration. It was not parsed.")
        }
        val base = mutableListOf<String>()
        if (mismatch.isNotBlank()) base += mismatch
        if (macro) base += "A macro was present and was not executed."
        return when {
            names.any { it.startsWith("word/") } -> docx(zip.entries, base, control, budget)
            names.any { it.startsWith("xl/") } -> xlsx(zip.entries, base, control, budget)
            names.any { it.startsWith("ppt/") } -> pptx(zip.entries, base, control, budget)
            else -> failed("UNSUPPORTED", "application/zip", join(mismatch, "The archive is not a supported document. Nothing was extracted."))
        }
    }

    private fun docx(entries: Map<String, ByteArray>, limitations: MutableList<String>, control: ExtractControl, budget: ExtractBudget): ExtractionResult {
        val xml = entries["word/document.xml"] ?: return failed("DOCX", docxMime, "The document body is missing. Nothing was extracted.")
        if (entries.keys.any { it.contains("hyperlink", true) } || xml.toString(Charsets.UTF_8).contains("hyperlink", ignoreCase = true)) {
            limitations += "External links were not opened."
        }
        val blocks = docxBlocks(xml)
        if (blocks.isEmpty()) return failed("DOCX", docxMime, (limitations + "No paragraph or table text was found.").joinToString(" "))
        val chunks = mutableListOf<DocumentChunk>()
        blocks.forEach { block ->
            if (control.cancelled()) return cancelled("DOCX", docxMime, chunks)
            val reference = when (block.kind) {
                "table" -> "table ${block.index}"
                "heading" -> "heading ${block.index}"
                else -> "paragraph ${block.index}"
            }
            chunks += splitChunks(chunks.size, reference, if (block.kind == "table") "STRUCTURE" else "ORIGINAL", "DOCX_XML", block.text, "", budget, chunks)
        }
        return result("DOCX", docxMime, if (limitations.isEmpty()) "EXTRACTED" else "PARTIAL", limitations.joinToString(" "), chunks)
    }

    private fun xlsx(entries: Map<String, ByteArray>, limitations: MutableList<String>, control: ExtractControl, budget: ExtractBudget): ExtractionResult {
        val names = sheetNames(entries["xl/workbook.xml"])
        val strings = sharedStrings(entries["xl/sharedStrings.xml"])
        val sheets = entries.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }.sorted()
        if (sheets.isEmpty()) return failed("XLSX", xlsxMime, "No worksheet was found.")
        val chunks = mutableListOf<DocumentChunk>()
        sheets.forEachIndexed { index, path ->
            if (control.cancelled()) return cancelled("XLSX", xlsxMime, chunks)
            val sheetName = names.getOrNull(index) ?: "Sheet ${index + 1}"
            if (index >= budget.maxSheets) {
                chunks += limitChunk(chunks.size, sheetName, "This sheet was not extracted.")
                limitations += "Sheets after ${budget.maxSheets} were not extracted."
                return@forEachIndexed
            }
            val cells = sheetCells(entries.getValue(path), strings)
            if (cells.isEmpty()) {
                chunks += splitChunks(chunks.size, sheetName, "STRUCTURE", "XLSX_XML", "The sheet has no cached cell value.", "", budget, chunks)
            } else {
                val body = cells.joinToString("\n") { cell ->
                    val formula = if (cell.formula.isBlank()) "no formula" else "formula ${cell.formula} was not executed"
                    val stored = if (cell.stored.isBlank()) "no stored result" else "stored result ${cell.stored}"
                    "${cell.reference}: $formula; $stored"
                }
                chunks += splitChunks(chunks.size, sheetName, "STRUCTURE", "XLSX_XML", "$sheetName\n$body", "Formulas were not executed.", budget, chunks)
            }
        }
        return result("XLSX", xlsxMime, if (limitations.isEmpty()) "EXTRACTED" else "PARTIAL", limitations.joinToString(" "), chunks)
    }

    private fun pptx(entries: Map<String, ByteArray>, limitations: MutableList<String>, control: ExtractControl, budget: ExtractBudget): ExtractionResult {
        val slides = entries.keys.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }.sortedBy { slideNumber(it) }
        if (slides.isEmpty()) return failed("PPTX", pptxMime, "No slide was found.")
        val chunks = mutableListOf<DocumentChunk>()
        slides.forEach { path ->
            val number = slideNumber(path)
            if (control.cancelled()) return cancelled("PPTX", pptxMime, chunks)
            if (number > budget.maxSlides) {
                chunks += limitChunk(chunks.size, "slide $number", "This slide was not extracted.")
                limitations += "Slides after ${budget.maxSlides} were not extracted."
                return@forEach
            }
            val text = xmlTexts(entries.getValue(path), "t")
            chunks += splitChunks(
                chunks.size,
                "slide $number",
                "ORIGINAL",
                "PPTX_XML",
                text.ifBlank { "The slide has no text." },
                "",
                budget,
                chunks,
            )
            val notesPath = "ppt/notesSlides/notesSlide$number.xml"
            val notes = entries[notesPath]?.let { xmlTexts(it, "t") }.orEmpty()
            if (notes.isNotBlank()) {
                chunks += splitChunks(chunks.size, "slide $number notes", "ORIGINAL", "PPTX_XML", notes, "", budget, chunks)
            }
        }
        return result("PPTX", pptxMime, if (limitations.isEmpty()) "EXTRACTED" else "PARTIAL", limitations.joinToString(" "), chunks)
    }

    private fun structuredText(
        bytes: ByteArray,
        format: String,
        mime: String,
        method: String,
        mismatch: String,
        control: ExtractControl,
        budget: ExtractBudget,
    ): ExtractionResult {
        if (control.cancelled()) return cancelled(format, mime)
        val decoded = decodeText(bytes)
        val limitations = mutableListOf<String>()
        if (mismatch.isNotBlank()) limitations += mismatch
        if (decoded.second.contains("replacement")) limitations += "Some bytes were not valid ${decoded.second.substringBefore(" with")}."
        val body = when (format) {
            "JSON" -> jsonText(decoded.first, limitations)
            "CSV" -> csvText(decoded.first, limitations)
            "MARKDOWN" -> decoded.first
            else -> decoded.first
        } ?: return failed(format, mime, limitations.joinToString(" ").ifBlank { "The file could not be read." })
        val chunks = splitChunks(0, format.lowercase(), "ORIGINAL", method, body, "", budget, mutableListOf())
        val status = if (limitations.isEmpty()) "EXTRACTED" else "PARTIAL"
        return result(format, mime, status, limitations.joinToString(" "), chunks)
    }

    private fun image(bytes: ByteArray, sniffed: Sniff, mismatch: String, control: ExtractControl, budget: ExtractBudget): ExtractionResult {
        if (control.cancelled()) return cancelled(sniffed.format, sniffed.mime)
        val orientation = if (sniffed.format == "JPEG") jpegOrientation(bytes) else ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return failed(sniffed.format, sniffed.mime, join(mismatch, "The image could not be decoded. No text was invented. ${OnDeviceOcr.DISCLOSURE}"))
        }
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, budget.maxImageEdge)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return failed(sniffed.format, sniffed.mime, "The image could not be decoded.")
        val oriented = orient(decoded, orientation)
        if (oriented !== decoded) decoded.recycle()
        val preview = scale(oriented, DocumentLimits.PREVIEW_EDGE)
        val png = ByteArrayOutputStream().use { out ->
            preview.compress(Bitmap.CompressFormat.PNG, 90, out)
            out.toByteArray()
        }
        if (preview !== oriented) preview.recycle()
        oriented.recycle()
        val limitations = mutableListOf<String>()
        if (mismatch.isNotBlank()) limitations += mismatch
        limitations += OnDeviceOcr.DISCLOSURE
        val text = "Image ${bounds.outWidth} by ${bounds.outHeight} pixels. Orientation $orientation. No OCR text is available."
        val chunk = DocumentChunk(0, "image", "IMAGE", "IMAGE_METADATA", text, true, OnDeviceOcr.DISCLOSURE)
        return ExtractionResult(
            format = sniffed.format,
            mimeType = sniffed.mime,
            status = "PARTIAL",
            limitation = limitations.joinToString(" "),
            chunks = listOf(chunk),
            width = bounds.outWidth,
            height = bounds.outHeight,
            orientation = orientation,
            previewPng = png,
        )
    }

    private fun jsonText(text: String, limitations: MutableList<String>): String? {
        return try {
            val value = JSONTokener(text).nextValue()
            val rendered = when (value) {
                is JSONObject -> value.toString(2)
                is JSONArray -> value.toString(2)
                else -> value.toString()
            }
            "JSON structure:\n$rendered"
        } catch (_: Exception) {
            limitations += "The JSON could not be parsed."
            null
        }
    }

    private fun csvText(text: String, limitations: MutableList<String>): String {
        val rows = parseCsv(text)
        if (rows.truncated) limitations += "A quoted CSV field was not closed. Later rows were not parsed."
        return rows.lines.joinToString("\n") { row -> row.joinToString(" | ") }
    }

    private fun decodeText(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE"
        }
        val text = String(bytes, Charsets.UTF_8)
        return text to if (text.contains('\uFFFD')) "UTF-8 with replacement for invalid bytes" else "UTF-8"
    }

    private fun splitChunks(
        startOrdinal: Int,
        reference: String,
        kind: String,
        method: String,
        text: String,
        limitation: String,
        budget: ExtractBudget,
        existing: List<DocumentChunk>,
    ): List<DocumentChunk> {
        val used = existing.sumOf { if (it.processed) it.text.length else 0 }
        val room = budget.maxChars - used
        if (room <= 0 || existing.size >= budget.maxChunks) {
            return listOf(limitChunk(startOrdinal, reference, "This section was not extracted because the character limit was reached."))
        }
        val clean = text.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ").trim()
        val taken = clean.take(room)
        val pieces = taken.chunked(budget.chunkChars.coerceAtLeast(1))
        val produced = mutableListOf<DocumentChunk>()
        pieces.forEachIndexed { index, piece ->
            if (existing.size + produced.size >= budget.maxChunks) {
                produced += limitChunk(startOrdinal + produced.size, reference, "Later text in this section was not extracted.")
                return produced
            }
            val label = if (pieces.size == 1) reference else "$reference part ${index + 1}"
            produced += DocumentChunk(startOrdinal + produced.size, label, kind, method, piece, true, limitation)
        }
        if (taken.length < clean.length) {
            produced += limitChunk(startOrdinal + produced.size, reference, "The rest of this section was not extracted.")
        }
        return produced
    }

    private fun limitChunk(ordinal: Int, reference: String, limitation: String) = DocumentChunk(
        ordinal = ordinal,
        reference = reference,
        kind = "LIMITATION",
        method = "LIMIT",
        text = limitation,
        processed = false,
        limitation = limitation,
    )

    private data class Sniff(val format: String, val mime: String)
    private data class ZipBundle(val entries: Map<String, ByteArray>, val error: String?)
    private data class PdfStream(val bytes: ByteArray, val containsImage: Boolean)
    private data class Cell(val reference: String, val formula: String, val stored: String)
    private data class CsvRows(val lines: List<List<String>>, val truncated: Boolean)

    private fun readZip(bytes: ByteArray, budget: ExtractBudget): ZipBundle? {
        return try {
            val entries = linkedMapOf<String, ByteArray>()
            var uncompressed = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var count = 0
                while (entry != null) {
                    count += 1
                    if (count > budget.maxZipEntries) return ZipBundle(emptyMap(), "The archive has too many entries. Nothing was extracted.")
                    val name = safeZipName(entry.name) ?: return ZipBundle(emptyMap(), "An archive path was rejected. Nothing was extracted.")
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        uncompressed += read
                        if (uncompressed > budget.maxUncompressedBytes) {
                            return ZipBundle(emptyMap(), "The archive expanded past the size limit and was not extracted.")
                        }
                        output.write(buffer, 0, read)
                    }
                    entries[name] = output.toByteArray()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            ZipBundle(entries, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun safeZipName(name: String): String? {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains(":")) return null
        if (normalized.split('/').any { it == ".." }) return null
        return normalized
    }

    private fun pdfStreams(bytes: ByteArray, budget: ExtractBudget): List<PdfStream> {
        val streams = mutableListOf<PdfStream>()
        var index = 0
        var produced = 0
        while (index < bytes.size && produced < budget.maxUncompressedBytes) {
            val marker = indexOfAscii(bytes, "stream", index)
            if (marker < 0) break
            val dictionary = bytes.copyOfRange((marker - 200).coerceAtLeast(0), marker).toString(Charsets.ISO_8859_1)
            var start = marker + "stream".length
            if (start < bytes.size && bytes[start] == '\r'.code.toByte()) start += 1
            if (start < bytes.size && bytes[start] == '\n'.code.toByte()) start += 1
            val end = indexOfAscii(bytes, "endstream", start)
            if (end < 0) break
            val raw = bytes.copyOfRange(start, end)
            val inflated = if (dictionary.contains("/FlateDecode")) inflate(raw, budget.maxUncompressedBytes - produced) else raw
            produced += inflated.size
            streams += PdfStream(inflated, dictionary.contains("/Image"))
            index = end + "endstream".length
        }
        return streams
    }

    private fun inflate(raw: ByteArray, room: Int): ByteArray {
        if (room <= 0) return ByteArray(0)
        return try {
            val inflater = Inflater()
            inflater.setInput(raw)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (!inflater.finished() && total < room) {
                val count = inflater.inflate(buffer)
                if (count <= 0) break
                val allowed = minOf(count, room - total)
                output.write(buffer, 0, allowed)
                total += allowed
            }
            inflater.end()
            output.toByteArray()
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun pdfText(bytes: ByteArray): String {
        val latin = bytes.toString(Charsets.ISO_8859_1)
        val parts = mutableListOf<String>()
        Regex("\\((?:\\\\.|[^\\\\)])*\\)\\s*Tj").findAll(latin).forEach { match ->
            parts += pdfLiteral(match.value.substringAfter("(").substringBeforeLast(")"))
        }
        Regex("<[0-9A-Fa-f\\s]+>\\s*Tj").findAll(latin).forEach { match ->
            parts += pdfHex(match.value.substringAfter("<").substringBefore(">"))
        }
        Regex("\\[(.*?)]\\s*TJ", RegexOption.DOT_MATCHES_ALL).findAll(latin).forEach { match ->
            Regex("\\((?:\\\\.|[^\\\\)])*\\)").findAll(match.groupValues[1]).forEach { literal ->
                parts += pdfLiteral(literal.value.removePrefix("(").removeSuffix(")"))
            }
        }
        return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun pdfLiteral(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '(', ')', '\\' -> out.append(next)
                    else -> if (next.isDigit()) {
                        val octal = value.substring(index + 1).take(3).takeWhile { it.isDigit() }
                        out.append(octal.toInt(8).toChar())
                        index += octal.length
                    } else {
                        out.append(next)
                    }
                }
                index += 2
            } else {
                out.append(char)
                index += 1
            }
        }
        return out.toString()
    }

    private fun pdfHex(value: String): String {
        val hex = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (hex.length < 2) return ""
        return hex.chunked(2).mapNotNull { byte -> byte.toIntOrNull(16)?.toChar() }.joinToString("")
    }

    private data class DocxBlock(val kind: String, val index: Int, val text: String)

    private fun docxBlocks(xml: ByteArray): List<DocxBlock> {
        val parser = parser(xml) ?: return emptyList()
        val blocks = mutableListOf<DocxBlock>()
        var paragraph = 0
        var heading = 0
        var table = 0
        var inTable = false
        var inText = false
        var paragraphText = StringBuilder()
        var tableText = StringBuilder()
        var style = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (local(parser.name) == "tbl") inTable = true
                    if (local(parser.name) == "t") inText = true
                    if (local(parser.name) == "pStyle") style = parser.getAttributeValue(null, "val") ?: attribute(parser, "val")
                }
                XmlPullParser.TEXT -> if (inText) {
                    val value = parser.text.orEmpty()
                    if (inTable) tableText.append(value).append(' ') else paragraphText.append(value).append(' ')
                }
                XmlPullParser.END_TAG -> {
                    when (local(parser.name)) {
                        "t" -> inText = false
                        "p" -> {
                            val text = paragraphText.toString().trim()
                            if (!inTable && text.isNotEmpty()) {
                                val headingStyle = style.contains("Heading", true)
                                if (headingStyle) {
                                    heading += 1
                                    blocks += DocxBlock("heading", heading, text)
                                } else {
                                    paragraph += 1
                                    blocks += DocxBlock("paragraph", paragraph, text)
                                }
                            }
                            paragraphText = StringBuilder()
                            style = ""
                        }
                        "tr" -> if (tableText.isNotBlank()) tableText.append("| ")
                        "tbl" -> {
                            table += 1
                            val text = tableText.toString().trim().trim('|').trim()
                            if (text.isNotEmpty()) blocks += DocxBlock("table", table, text)
                            tableText = StringBuilder()
                            inTable = false
                        }
                    }
                }
            }
            parser.next()
        }
        return blocks
    }

    private fun sheetNames(workbook: ByteArray?): List<String> {
        if (workbook == null) return emptyList()
        val parser = parser(workbook) ?: return emptyList()
        val names = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && local(parser.name) == "sheet") {
                names += parser.getAttributeValue(null, "name") ?: attribute(parser, "name")
            }
            parser.next()
        }
        return names.filter { it.isNotBlank() }
    }

    private fun sharedStrings(table: ByteArray?): List<String> {
        if (table == null) return emptyList()
        val parser = parser(table) ?: return emptyList()
        val strings = mutableListOf<String>()
        var current = StringBuilder()
        var inSi = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (local(parser.name) == "si") {
                    inSi = true
                    current = StringBuilder()
                }
                XmlPullParser.TEXT -> if (inSi) current.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> if (local(parser.name) == "si") {
                    strings += current.toString()
                    inSi = false
                }
            }
            parser.next()
        }
        return strings
    }

    private fun sheetCells(xml: ByteArray, strings: List<String>): List<Cell> {
        val parser = parser(xml) ?: return emptyList()
        val cells = mutableListOf<Cell>()
        var reference = ""
        var type = ""
        var formula = ""
        var stored = ""
        var capture = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "c" -> {
                        reference = parser.getAttributeValue(null, "r") ?: attribute(parser, "r")
                        type = parser.getAttributeValue(null, "t") ?: attribute(parser, "t")
                        formula = ""
                        stored = ""
                    }
                    "f" -> capture = "f"
                    "v" -> capture = "v"
                }
                XmlPullParser.TEXT -> when (capture) {
                    "f" -> formula += parser.text.orEmpty()
                    "v" -> stored += parser.text.orEmpty()
                }
                XmlPullParser.END_TAG -> {
                    if (local(parser.name) == "f" || local(parser.name) == "v") capture = ""
                    if (local(parser.name) == "c" && reference.isNotBlank()) {
                        val value = if (type == "s") strings.getOrNull(stored.toIntOrNull() ?: -1).orEmpty() else stored
                        cells += Cell(reference, formula, value)
                    }
                }
            }
            parser.next()
        }
        return cells
    }

    private fun xmlTexts(xml: ByteArray, localName: String): String {
        val parser = parser(xml) ?: return ""
        val parts = mutableListOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && local(parser.name) == localName) {
                parser.next()
                if (parser.eventType == XmlPullParser.TEXT) parts += parser.text.orEmpty()
            }
            parser.next()
        }
        return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun parser(xml: ByteArray): XmlPullParser? {
        val text = xml.toString(Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return null
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching { factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(xml), "UTF-8")
            parser.nextTag()
            parser
        } catch (_: Exception) {
            null
        }
    }

    private fun jpegOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scale(bitmap: Bitmap, edge: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= edge) return bitmap
        val ratio = edge.toFloat() / largest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun sampleSize(width: Int, height: Int, edge: Int): Int {
        var sample = 1
        var largest = maxOf(width, height)
        while (largest / sample > edge) sample *= 2
        return sample
    }

    private fun parseCsv(text: String): CsvRows {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        cell.append('"')
                        index += 1
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(char)
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    ',' -> {
                        row += cell.toString()
                        cell = StringBuilder()
                    }
                    '\n' -> {
                        row += cell.toString()
                        rows += row
                        row = mutableListOf()
                        cell = StringBuilder()
                    }
                    '\r' -> Unit
                    else -> cell.append(char)
                }
            }
            index += 1
        }
        if (quoted) return CsvRows(rows, true)
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row
        }
        return CsvRows(rows, false)
    }

    private fun extensionMismatch(filename: String, format: String): String {
        val extension = extensionOf(filename)
        if (extension.isBlank()) return ""
        val expected = when (format) {
            "PDF" -> setOf("pdf")
            "PNG" -> setOf("png")
            "JPEG" -> setOf("jpg", "jpeg")
            "WEBP" -> setOf("webp")
            "JSON" -> setOf("json")
            "CSV" -> setOf("csv")
            "MARKDOWN" -> setOf("md", "markdown")
            "TEXT" -> setOf("txt", "text", "md", "csv", "json")
            "ZIP" -> setOf("docx", "xlsx", "pptx", "zip")
            else -> emptySet()
        }
        if (expected.isEmpty() || extension in expected) return ""
        return "The filename extension .$extension did not match the file contents. The contents were processed as $format."
    }

    private fun extensionOf(filename: String) = filename.substringAfterLast('.', "").lowercase()

    private fun officeMime(filename: String) = when (extensionOf(filename)) {
        "docx" -> docxMime
        "xlsx" -> xlsxMime
        "pptx" -> pptxMime
        else -> "application/zip"
    }

    private fun slideNumber(path: String) = Regex("\\d+").find(path.substringAfterLast('/'))?.value?.toIntOrNull() ?: 0

    private fun local(name: String) = name.substringAfter(':')

    private fun attribute(parser: XmlPullParser, localName: String): String {
        for (index in 0 until parser.attributeCount) {
            if (local(parser.getAttributeName(index)) == localName) return parser.getAttributeValue(index).orEmpty()
        }
        return ""
    }

    private fun indexOfAscii(bytes: ByteArray, needle: String, start: Int): Int {
        val target = needle.toByteArray(Charsets.US_ASCII)
        var index = start
        while (index + target.size <= bytes.size) {
            var match = true
            for (offset in target.indices) {
                if (bytes[index + offset] != target[offset]) {
                    match = false
                    break
                }
            }
            if (match) return index
            index += 1
        }
        return -1
    }

    private fun join(first: String, second: String) = listOf(first, second).filter { it.isNotBlank() }.joinToString(" ")

    private fun cancelled(format: String = "UNSUPPORTED", mime: String = "application/octet-stream", chunks: List<DocumentChunk> = emptyList()) =
        ExtractionResult(format, mime, "CANCELLED", "Extraction was cancelled. Unread sections were not analyzed.", chunks, 0, 0, 0, null)

    private fun failed(format: String, mime: String, limitation: String) =
        ExtractionResult(format, mime, "FAILED", limitation, emptyList(), 0, 0, 0, null)

    private fun result(format: String, mime: String, status: String, limitation: String, chunks: List<DocumentChunk>): ExtractionResult {
        val renumbered = chunks.mapIndexed { index, chunk -> chunk.copy(ordinal = index) }
        val finalStatus = when {
            status == "FAILED" || status == "CANCELLED" -> status
            renumbered.any { !it.processed } -> "PARTIAL"
            else -> status
        }
        return ExtractionResult(format, mime, finalStatus, limitation, renumbered, 0, 0, 0, null)
    }

    private const val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val pptxMime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
}
