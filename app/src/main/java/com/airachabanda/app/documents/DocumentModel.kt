package com.airachabanda.app.documents

import com.airachabanda.app.orchestration.LocalMath
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

object DocumentGenerationLimits {
    const val MAX_PAGES = 30
    const val MAX_SLIDES = 20
    const val MAX_SHEETS = 8
    const val MAX_ROWS = 200
    const val MAX_COLUMNS = 12
    const val MAX_CHARS = 40_000
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_BLOCKS = 300
    const val EXPLANATION =
        "Documents are rendered on this device. A file can be 8 MB, with 30 pages, 20 slides, 8 sheets, and 200 rows. " +
            "No provider is contacted and no file is uploaded. Formulas are limited to local arithmetic. Macros are not created."
}

enum class OutputFormat(val label: String, val extension: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("DOCX", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    XLSX("XLSX", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PPTX("PPTX", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    CSV("CSV", "csv", "text/csv"),
    TXT("TXT", "txt", "text/plain"),
    MARKDOWN("Markdown", "md", "text/markdown"),
    JSON("JSON", "json", "application/json"),
    ;

    companion object {
        fun fromName(name: String): OutputFormat? = entries.firstOrNull { it.name.equals(name, true) || it.label.equals(name, true) }
    }
}

data class TemplateOption(val id: String, val label: String)

object DocumentTemplates {
    val all = listOf(
        TemplateOption("business-report", "Business report"),
        TemplateOption("research-report", "Research report"),
        TemplateOption("executive-summary", "Executive summary"),
        TemplateOption("letter", "Letter"),
        TemplateOption("resume", "Resume"),
        TemplateOption("agreement", "Agreement"),
        TemplateOption("financial-spreadsheet", "Financial spreadsheet"),
        TemplateOption("comparison-spreadsheet", "Comparison spreadsheet"),
        TemplateOption("business-presentation", "Business presentation"),
        TemplateOption("research-presentation", "Research presentation"),
    )

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: "Business report"

    fun build(request: DocumentRequest): StructuredDocument = DocumentAssembler.assemble(request)
}

data class StoredRecords(
    val messages: List<String> = emptyList(),
    val evidence: List<Pair<String, String>> = emptyList(),
    val objections: List<String> = emptyList(),
)

data class DocumentRequest(
    val title: String,
    val templateId: String,
    val body: String,
    val formatName: String = "PDF",
    val records: StoredRecords = StoredRecords(),
    val createdLabel: String = "",
)

data class SheetCell(
    val kind: String,
    val text: String = "",
    val number: Double? = null,
    val formula: String? = null,
    val cached: Double? = null,
)

data class Worksheet(
    val name: String,
    val rows: List<List<SheetCell>>,
    val widths: List<Double>,
    val freezeHeader: Boolean,
    val filter: Boolean,
    val chartCategories: List<String> = emptyList(),
    val chartValues: List<Double> = emptyList(),
    val chartTitle: String = "",
)

data class SlideSpec(
    val title: String,
    val lines: List<String>,
    val notes: String,
    val headers: List<String> = emptyList(),
    val table: List<List<String>> = emptyList(),
    val image: ByteArray? = null,
    val imageAlt: String = "",
)

data class DocBlock(
    val kind: String,
    val text: String = "",
    val items: List<String> = emptyList(),
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val url: String = "",
    val image: ByteArray? = null,
    val values: List<Double> = emptyList(),
    val labels: List<String> = emptyList(),
)

data class StructuredDocument(
    val title: String,
    val templateId: String,
    val createdLabel: String,
    val blocks: List<DocBlock>,
    val sheets: List<Worksheet>,
    val slides: List<SlideSpec>,
    val limitations: List<String>,
)

object FormulaPolicy {
    private val functions = setOf("SUM", "AVERAGE", "MIN", "MAX", "COUNT", "ABS", "ROUND", "PRODUCT")

    fun accepted(raw: String): Boolean {
        val formula = raw.trim().removePrefix("=").trim()
        if (formula.isEmpty() || formula.length > 80) return false
        if (!formula.matches(Regex("[A-Za-z0-9_:.+\\-*/,()\\s$!]+"))) return false
        val withoutRefs = formula.replace(
            Regex("([A-Za-z][A-Za-z0-9]*!)?\\$?[A-Z]{1,3}\\$?[0-9]{1,4}(:(\\$?[A-Z]{1,3})?\\$?[0-9]{1,4})?"),
            " ",
        )
        val words = Regex("[A-Za-z]+").findAll(withoutRefs).map { it.value.uppercase() }.toList()
        return words.all { it in functions }
    }
}

object SpreadsheetMath {
    fun cache(formula: String, sheet: String, values: Map<String, Double>): Double? = runCatching {
        val parser = Parser(formula.trim().removePrefix("=").replace(" ", ""), values, sheet)
        val value = parser.parse()
        if (!parser.finished() || value == null || !value.isFinite()) null else value
    }.getOrNull()

    private class Parser(
        private val input: String,
        private val values: Map<String, Double>,
        private val sheet: String,
    ) {
        private var index = 0

        fun finished(): Boolean = index >= input.length

        fun parse(): Double? = expression()

        private fun expression(): Double? {
            var value = term() ?: return null
            while (index < input.length && (input[index] == '+' || input[index] == '-')) {
                val op = input[index]
                index += 1
                val right = term() ?: return null
                value = if (op == '+') value + right else value - right
            }
            return value
        }

        private fun term(): Double? {
            var value = factor() ?: return null
            while (index < input.length && (input[index] == '*' || input[index] == '/')) {
                val op = input[index]
                index += 1
                val right = factor() ?: return null
                if (op == '/' && abs(right) < 1e-12) return null
                value = if (op == '*') value * right else value / right
            }
            return value
        }

        private fun factor(): Double? {
            if (index >= input.length) return null
            if (input[index] == '+') {
                index += 1
                return factor()
            }
            if (input[index] == '-') {
                index += 1
                return factor()?.times(-1)
            }
            if (input[index] == '(') {
                index += 1
                val value = expression() ?: return null
                if (index >= input.length || input[index] != ')') return null
                index += 1
                return value
            }
            if (input[index].isLetter()) {
                val start = index
                while (index < input.length && (input[index].isLetterOrDigit() || input[index] == '_')) index += 1
                val word = input.substring(start, index)
                if (index < input.length && input[index] == '(') {
                    index += 1
                    val args = argument() ?: return null
                    if (index >= input.length || input[index] != ')') return null
                    index += 1
                    return function(word.uppercase(), args)
                }
                index = start
                val ref = reference() ?: return null
                return values[qualify(ref, sheet)]
            }
            val numberStart = index
            while (index < input.length && (input[index].isDigit() || input[index] == '.')) index += 1
            return input.substring(numberStart, index).toDoubleOrNull()
        }

        private fun argument(): List<Double>? {
            if (looksLikeRange()) return rangeValues()
            val value = expression() ?: return null
            return listOf(value)
        }

        private fun looksLikeRange(): Boolean {
            val saved = index
            val first = reference() ?: return false
            val range = index < input.length && input[index] == ':'
            index = saved
            return range || first.isNotEmpty()
        }

        private fun rangeValues(): List<Double>? {
            val start = reference() ?: return null
            if (index >= input.length || input[index] != ':') return listOfNotNull(values[qualify(start, sheet)])
            index += 1
            val end = reference() ?: return null
            val startRef = splitRef(qualify(start, sheet))
            val endRef = splitRef(qualify(end, startRef.first))
            val cells = mutableListOf<Double>()
            val columns = columnIndex(startRef.second)..columnIndex(endRef.second)
            val rows = startRef.third..endRef.third
            if (columns.first < 1 || rows.first < 1 || columns.count() > DocumentGenerationLimits.MAX_COLUMNS || rows.count() > DocumentGenerationLimits.MAX_ROWS) {
                return null
            }
            for (column in columns) {
                for (row in rows) {
                    values["${startRef.first}!${columnName(column)}$row"]?.let { cells += it }
                }
            }
            return cells
        }

        private fun function(name: String, args: List<Double>): Double? = when (name) {
            "SUM" -> if (args.isEmpty()) null else args.sum()
            "AVERAGE" -> if (args.isEmpty()) null else args.average()
            "MIN" -> args.minOrNull()
            "MAX" -> args.maxOrNull()
            "COUNT" -> args.size.toDouble()
            "ABS" -> args.singleOrNull()?.let { abs(it) }
            "PRODUCT" -> if (args.isEmpty()) null else args.fold(1.0) { acc, value -> acc * value }
            "ROUND" -> args.firstOrNull()
            else -> null
        }

        private fun reference(): String? {
            val start = index
            while (index < input.length && (input[index].isLetterOrDigit() || input[index] == '_' || input[index] == '!' || input[index] == '$')) index += 1
            val token = input.substring(start, index).replace("$", "")
            return token.takeIf { it.matches(Regex("([A-Za-z][A-Za-z0-9]*!)?[A-Z]{1,3}[0-9]{1,4}")) }
        }
    }

    private fun qualify(ref: String, sheet: String): String = if (ref.contains('!')) ref else "$sheet!$ref"

    private fun splitRef(qualified: String): Triple<String, String, Int> {
        val sheet = qualified.substringBefore('!')
        val cell = qualified.substringAfter('!')
        val column = cell.takeWhile { it.isLetter() }
        val row = cell.dropWhile { it.isLetter() }.toInt()
        return Triple(sheet, column, row)
    }

    private fun columnIndex(name: String): Int = name.fold(0) { acc, char -> acc * 26 + (char.code - 'A'.code + 1) }

    private fun columnName(index: Int): String {
        var value = index
        val chars = StringBuilder()
        while (value > 0) {
            value -= 1
            chars.append(('A'.code + (value % 26)).toChar())
            value /= 26
        }
        return chars.reverse().toString()
    }
}

object ChartPng {
    fun bars(values: List<Double>): ByteArray? {
        if (values.isEmpty()) return null
        val width = 320
        val height = 180
        val stride = width * 3 + 1
        val raw = ByteArray(stride * height)
        for (y in 0 until height) {
            val row = y * stride
            for (x in 0 until width) {
                val pixel = row + 1 + x * 3
                raw[pixel] = 0xF7.toByte()
                raw[pixel + 1] = 0xF4.toByte()
                raw[pixel + 2] = 0xEF.toByte()
            }
        }
        val max = values.maxOf { it }.coerceAtLeast(1.0)
        val gap = 10
        val barWidth = ((width - gap * (values.size + 1)) / values.size).coerceAtLeast(4)
        values.forEachIndexed { index, value ->
            val barHeight = ((value / max) * (height - 28)).toInt().coerceIn(2, height - 28)
            val left = gap + index * (barWidth + gap)
            for (y in (height - 16 - barHeight) until (height - 16)) {
                for (x in left until (left + barWidth).coerceAtMost(width)) {
                    val pixel = y * stride + 1 + x * 3
                    raw[pixel] = 0x1F
                    raw[pixel + 1] = 0x3A
                    raw[pixel + 2] = 0x5F
                }
            }
        }
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count == 0) break
            compressed.write(buffer, 0, count)
        }
        deflater.end()
        return png(width, height, compressed.toByteArray())
    }

    private fun png(width: Int, height: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        out.write(chunk("IHDR", header))
        out.write(chunk("IDAT", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        return out.toByteArray()
    }
}

private data class ParsedBody(
    val paragraphs: List<String>,
    val bullets: List<String>,
    val rows: List<List<String>>,
    val amounts: List<Pair<String, Double>>,
    val comparisons: List<Triple<String, Double, Double>>,
    val date: String?,
    val rate: Double?,
    val math: List<Pair<String, String>>,
    val links: List<Pair<String, String>>,
    val limitations: List<String>,
)

private object DocumentAssembler {
    fun assemble(request: DocumentRequest): StructuredDocument {
        val title = request.title.trim().take(120)
        val parsed = parse(request.body)
        val limitations = parsed.limitations.toMutableList()
        val records = request.records
        if (records.evidence.isEmpty()) limitations += "No stored evidence was supplied."
        if (records.objections.isEmpty()) limitations += "No stored objection was supplied."
        val blocks = mutableListOf<DocBlock>()
        blocks += DocBlock("paragraph", disclosure)
        blocks += DocBlock("heading", headingFor(request.templateId), items = listOf("1"))
        parsed.paragraphs.forEach { blocks += DocBlock("paragraph", it) }
        if (parsed.paragraphs.isEmpty()) blocks += DocBlock("paragraph", "No user text was supplied for this section.")
        if (parsed.bullets.isNotEmpty()) blocks += DocBlock("bullets", items = parsed.bullets)
        if (parsed.rows.isNotEmpty()) {
            blocks += DocBlock("table", text = "Supplied rows", headers = listOf("Item", "Detail", "Note"), rows = parsed.rows.map { row ->
                List(3) { index -> row.getOrElse(index) { "" } }
            })
        }
        parsed.math.forEach { (expression, outcome) -> blocks += DocBlock("paragraph", "Local arithmetic for $expression. $outcome") }
        parsed.links.forEach { (label, url) -> blocks += DocBlock("link", text = label, url = url) }
        val chartLabels = parsed.amounts.map { it.first }
        val chartValues = parsed.amounts.map { it.second }
        val image = ChartPng.bars(chartValues)
        if (image != null) {
            blocks += DocBlock(
                "chart",
                text = "Supplied amounts",
                labels = chartLabels,
                values = chartValues,
                image = image,
            )
        }
        blocks += DocBlock("heading", "Stored records", items = listOf("1"))
        if (records.messages.isEmpty()) blocks += DocBlock("paragraph", "No stored message was included.")
        records.messages.take(12).forEach { blocks += DocBlock("paragraph", "Stored message. $it") }
        if (records.evidence.isEmpty()) {
            blocks += DocBlock("paragraph", "No stored evidence was supplied. No citation was added.")
        } else {
            records.evidence.take(8).forEach { (locator, excerpt) ->
                blocks += DocBlock("source", text = excerpt.take(500), url = locator)
            }
        }
        if (records.objections.isEmpty()) {
            blocks += DocBlock("paragraph", "No stored objection was supplied. Nothing was written in its place.")
        } else {
            records.objections.take(8).forEach { blocks += DocBlock("paragraph", "Stored objection. $it") }
        }
        if (request.templateId == "agreement") {
            blocks += DocBlock("paragraph", "This draft uses only the supplied text. It does not add legal provisions.")
        }
        if (request.templateId == "letter") {
            blocks.add(1, DocBlock("paragraph", parsed.date?.let { "Date supplied: $it" } ?: "No date was supplied."))
        }
        val sheets = buildSheets(title, request.templateId, parsed, limitations)
        val slides = slides(title, request, parsed, records, image, limitations)
        return StructuredDocument(
            title = title,
            templateId = request.templateId,
            createdLabel = request.createdLabel,
            blocks = blocks.take(DocumentGenerationLimits.MAX_BLOCKS),
            sheets = sheets.take(DocumentGenerationLimits.MAX_SHEETS),
            slides = slides.take(DocumentGenerationLimits.MAX_SLIDES),
            limitations = limitations.distinct(),
        )
    }

    private fun buildSheets(title: String, templateId: String, parsed: ParsedBody, limitations: MutableList<String>): List<Worksheet> {
        val numeric = when (templateId) {
            "comparison-spreadsheet" -> comparisonSheets(parsed, limitations)
            else -> amountSheets(parsed, limitations)
        }
        return numeric + notesSheet(title, parsed)
    }

    private fun amountSheets(parsed: ParsedBody, limitations: MutableList<String>): List<Worksheet> {
        if (parsed.amounts.isEmpty() && parsed.date == null && parsed.rate == null) {
            limitations += "No amounts were supplied. No total was calculated."
            return emptyList()
        }
        val rows = mutableListOf(listOf(textCell("Item"), textCell("Amount"), textCell("Note")))
        val values = mutableMapOf<String, Double>()
        parsed.amounts.take(DocumentGenerationLimits.MAX_ROWS - 2).forEachIndexed { index, (name, amount) ->
            val rowNumber = index + 2
            rows += listOf(textCell(name), numberCell(amount, "CURRENCY"), textCell("Supplied amount"))
            values["Lines!B$rowNumber"] = amount
        }
        if (parsed.date != null) {
            val serial = excelSerial(parsed.date)
            if (serial != null) rows += listOf(textCell("Supplied date"), dateCell(serial), textCell(parsed.date))
        }
        if (parsed.rate != null) {
            rows += listOf(textCell("Supplied rate"), percentCell(parsed.rate), textCell("Stored as a fraction for percentage formatting"))
        }
        val summary = mutableListOf(listOf(textCell("Item"), textCell("Value")))
        if (parsed.amounts.isNotEmpty()) {
            val formula = "SUM(Lines!B2:B${parsed.amounts.size + 1})"
            val cached = if (FormulaPolicy.accepted(formula)) SpreadsheetMath.cache(formula, "Summary", values) else null
            if (cached == null) limitations += "The total formula was recorded and no cached value was invented."
            summary += listOf(textCell("Total"), formulaCell(formula, cached))
        } else {
            summary += listOf(textCell("Total"), textCell("No amounts were supplied. No total was calculated."))
        }
        return listOf(
            Worksheet(
                name = "Lines",
                rows = rows,
                widths = listOf(28.0, 16.0, 42.0),
                freezeHeader = true,
                filter = parsed.amounts.isNotEmpty(),
                chartCategories = parsed.amounts.map { it.first },
                chartValues = parsed.amounts.map { it.second },
                chartTitle = "Supplied amounts",
            ),
            Worksheet("Summary", summary, listOf(24.0, 22.0), true, false),
        )
    }

    private fun comparisonSheets(parsed: ParsedBody, limitations: MutableList<String>): List<Worksheet> {
        if (parsed.comparisons.isEmpty()) {
            limitations += "No comparison rows were supplied. No difference was calculated."
            return amountSheets(parsed, limitations)
        }
        val rows = mutableListOf(listOf(textCell("Item"), textCell("Left"), textCell("Right"), textCell("Difference")))
        val values = mutableMapOf<String, Double>()
        parsed.comparisons.take(DocumentGenerationLimits.MAX_ROWS - 1).forEachIndexed { index, (name, left, right) ->
            val row = index + 2
            val formula = "B$row-C$row"
            values["Comparison!B$row"] = left
            values["Comparison!C$row"] = right
            val cached = SpreadsheetMath.cache(formula, "Comparison", values)
            rows += listOf(textCell(name), numberCell(left, "CURRENCY"), numberCell(right, "CURRENCY"), formulaCell(formula, cached))
        }
        return listOf(Worksheet("Comparison", rows, listOf(28.0, 14.0, 14.0, 16.0), true, true))
    }

    private fun notesSheet(title: String, parsed: ParsedBody): Worksheet {
        val rows = mutableListOf(listOf(textCell("Note")), listOf(textCell(title)))
        (parsed.paragraphs + parsed.bullets + parsed.math.map { "${it.first} ${it.second}" }).forEach { line ->
            rows += listOf(textCell(line.take(500)))
        }
        if (rows.size == 1) rows += listOf(textCell("No user text was supplied."))
        return Worksheet("Notes", rows, listOf(80.0), true, false)
    }

    private fun slides(
        title: String,
        request: DocumentRequest,
        parsed: ParsedBody,
        records: StoredRecords,
        image: ByteArray?,
        limitations: MutableList<String>,
    ): List<SlideSpec> {
        val templateId = request.templateId
        val body = parsed.paragraphs + parsed.bullets
        val visible = mutableListOf<String>()
        val overflow = StringBuilder()
        body.forEach { line ->
            if (visible.size < 6 && line.length <= 240) visible += line
            else {
                overflow.append(line).append('\n')
            }
        }
        val tableRows = parsed.rows.ifEmpty { parsed.amounts.map { listOf(it.first, plain(it.second)) } }
        val tableVisible = tableRows.take(6)
        if (request.formatName == "PPTX" && (overflow.isNotBlank() || tableRows.size > tableVisible.size)) {
            limitations += "Text beyond the slide box was placed in speaker notes."
        }
        val tableOverflow = tableRows.drop(6).joinToString("\n") { it.joinToString(" | ") }
        val sourceLines = if (records.evidence.isEmpty()) {
            listOf("No stored evidence was supplied.")
        } else {
            records.evidence.take(5).map { "${it.first}: ${it.second.take(180)}" }
        }
        val objectionNote = records.objections.joinToString("\n") { "Stored objection. $it" }
        return listOf(
            SlideSpec(title, listOf(templateHeading(templateId), disclosure), "Locally rendered title slide."),
            SlideSpec("Section", listOf(templateHeading(templateId)), ""),
            SlideSpec("Supplied text", visible.ifEmpty { listOf("No additional user text was supplied.") }, overflow.toString().trim()),
            SlideSpec(
                "Supplied table",
                emptyList(),
                tableOverflow,
                headers = listOf("Item", "Detail", "Note"),
                table = tableVisible.map { List(3) { index -> it.getOrElse(index) { "" } } },
            ),
            SlideSpec(
                if (image == null) "Chart" else "Supplied chart",
                if (image == null) listOf("No chart values were supplied. No chart was invented.") else parsed.amounts.map { "${it.first}: ${plain(it.second)}" },
                "The chart image repeats supplied numbers. It is not a new measurement.",
                image = image,
                imageAlt = "Bar chart of supplied amounts",
            ),
            SlideSpec("Sources and review", sourceLines, objectionNote.ifBlank { "No stored objection was supplied." }),
        )
    }

    private fun parse(body: String): ParsedBody {
        val paragraphs = mutableListOf<String>()
        val bullets = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        val amounts = mutableListOf<Pair<String, Double>>()
        val comparisons = mutableListOf<Triple<String, Double, Double>>()
        val math = mutableListOf<Pair<String, String>>()
        val links = mutableListOf<Pair<String, String>>()
        val limitations = mutableListOf<String>()
        var date: String? = null
        var rate: Double? = null
        var chars = 0
        body.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { raw ->
            if (chars >= DocumentGenerationLimits.MAX_CHARS || rows.size >= DocumentGenerationLimits.MAX_ROWS) {
                limitations += "Content past the size limit was not rendered."
                return@forEach
            }
            val line = withholdCredentials(raw.trim())
            if (line.isEmpty()) return@forEach
            chars += line.length
            when {
                line.startsWith("ROW|") -> rows += line.removePrefix("ROW|").split('|').map { withholdCredentials(it.trim()) }.take(DocumentGenerationLimits.MAX_COLUMNS)
                line.startsWith("ITEM|") -> bullets += line.removePrefix("ITEM|").trim()
                line.startsWith("AMOUNT|") -> amount(line.removePrefix("AMOUNT|"))?.let { amounts += it } ?: limitations.add("An amount line was not numeric and was not calculated.")
                line.startsWith("COMPARE|") -> comparison(line.removePrefix("COMPARE|"))?.let { comparisons += it } ?: limitations.add("A comparison line was not numeric and was not calculated.")
                line.startsWith("DATE ") || line.startsWith("DATE|") -> {
                    val value = line.substringAfter('|').substringAfter(' ').trim()
                    if (excelSerial(value) != null) date = value else limitations += "The date was not a calendar date and was not converted."
                }
                line.startsWith("RATE ") || line.startsWith("RATE|") -> {
                    val value = line.substringAfter('|').substringAfter(' ').trim().toDoubleOrNull()
                    if (value != null && value in 0.0..100.0) rate = value / 100.0 else limitations += "The rate was not a percentage from 0 to 100."
                }
                line.startsWith("MATH ") || line.startsWith("MATH|") -> {
                    val expression = line.substringAfter('|').substringAfter(' ').trim()
                    val outcome = LocalMath.check(expression)
                    math += expression to outcome.reason
                }
                line.startsWith("LINK|") -> {
                    val parts = line.removePrefix("LINK|").split('|')
                    val label = parts.getOrElse(0) { "Link" }.trim()
                    val url = parts.getOrElse(1) { "" }.trim()
                    if (url.startsWith("https://") && !url.contains('@') && url.length <= 200) links += label to url
                    else limitations += "The link was not added because it is not a plain https URL. It was not opened."
                }
                else -> paragraphs += line
            }
        }
        return ParsedBody(paragraphs, bullets, rows, amounts, comparisons, date, rate, math, links, limitations)
    }

    private fun amount(value: String): Pair<String, Double>? {
        val parts = value.split('|')
        val number = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        val name = parts[0].trim().ifBlank { return null }
        if (name.startsWith("=") || name.startsWith("+") || name.startsWith("-") || name.startsWith("@")) return null
        return name to number
    }

    private fun comparison(value: String): Triple<String, Double, Double>? {
        val parts = value.split('|')
        val left = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        val right = parts.getOrNull(2)?.trim()?.toDoubleOrNull() ?: return null
        val name = parts[0].trim().ifBlank { return null }
        return Triple(name, left, right)
    }

    private val disclosure =
        "Rendered locally from supplied text and stored records. This file is not an AI provider response."
}

internal fun headingFor(templateId: String): String = when (templateId) {
    "research-report", "research-presentation" -> "Findings"
    "executive-summary" -> "Summary"
    "letter" -> "Letter"
    "resume" -> "Experience"
    "agreement" -> "Terms"
    "financial-spreadsheet", "comparison-spreadsheet" -> "Figures"
    "business-presentation" -> "Overview"
    else -> "Report"
}

private fun templateHeading(templateId: String): String = DocumentTemplates.label(templateId)

internal fun textCell(value: String) = SheetCell("TEXT", text = value.take(500))

internal fun numberCell(value: Double, kind: String) = SheetCell(kind, number = value, cached = value)

internal fun dateCell(serial: Double) = SheetCell("DATE", number = serial, cached = serial)

internal fun percentCell(fraction: Double) = SheetCell("PERCENT", number = fraction, cached = fraction)

internal fun formulaCell(formula: String, cached: Double?): SheetCell {
    val clean = formula.removePrefix("=").trim()
    return if (FormulaPolicy.accepted(clean)) {
        SheetCell("FORMULA", formula = clean, cached = cached)
    } else {
        SheetCell("TEXT", text = clean)
    }
}

internal fun excelSerial(iso: String): Double? = runCatching {
    ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.parse(iso)).toDouble()
}.getOrNull()

internal fun plain(value: Double): String {
    val rounded = if (abs(value - value.toLong()) < 0.0000001) value.toLong().toString() else value.toString()
    return rounded
}

internal fun withholdCredentials(value: String): String {
    val patterns = listOf("BEGIN PRIVATE KEY", "BEGIN RSA PRIVATE KEY")
    if (patterns.any { value.contains(it) }) return "[withheld credential pattern]"
    return value.replace(Regex("sk-[A-Za-z0-9]{10,}"), "[withheld credential pattern]")
}

internal fun xmlEscape(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (char == '\n' || char == '\t' || char.code in 0x20..0xD7FF) append(char)
        }
    }
}
