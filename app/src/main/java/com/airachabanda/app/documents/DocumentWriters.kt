package com.airachabanda.app.documents

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

object PdfText {
    fun encodable(char: Char): Boolean = char.code in 32..126 || char.code in 160..255

    fun omitted(text: String): String {
        val missing = text.filter { char -> !char.isWhitespace() && !encodable(char) }.toSet()
        if (missing.isEmpty()) return ""
        val sample = missing.take(12).joinToString("")
        val scripts = missing.map { Character.UnicodeScript.of(it.code) }.distinct().joinToString(", ") { it.name }
        return "These characters were not drawn in the PDF because the embedded Helvetica font has no glyph for them: $sample. Scripts: $scripts. The file does not substitute a question mark. Telugu and other scripts outside Windows-1252 need a licensed Unicode font, and no additional font was downloaded."
    }
}

object DocumentWriters {
    fun render(document: StructuredDocument, format: OutputFormat): ByteArray = when (format) {
        OutputFormat.PDF -> pdf(document)
        OutputFormat.DOCX -> docx(document)
        OutputFormat.XLSX -> xlsx(document)
        OutputFormat.PPTX -> pptx(document)
        OutputFormat.CSV -> csv(document).toByteArray(Charsets.UTF_8)
        OutputFormat.TXT -> text(document).toByteArray(Charsets.UTF_8)
        OutputFormat.MARKDOWN -> markdown(document).toByteArray(Charsets.UTF_8)
        OutputFormat.JSON -> json(document).toByteArray(Charsets.UTF_8)
    }

    fun pdf(document: StructuredDocument): ByteArray {
        val pages = paginate(document).ifEmpty { listOf(emptyList()) }
        val contents = pages.mapIndexed { index, lines -> pageContent(document.title, lines, index + 1, pages.size) }
        val objects = mutableListOf<ByteArray>()
        objects += "<< /Type /Catalog /Pages 2 0 R >>".toByteArray()
        val kids = pages.indices.joinToString(" ") { "${5 + it * 2 + 1} 0 R" }
        objects += "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>".toByteArray()
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".toByteArray()
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>".toByteArray()
        contents.forEachIndexed { index, content ->
            objects += content
            val contentId = 5 + index * 2
            objects += (
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents $contentId 0 R " +
                    "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> >>"
                ).toByteArray()
        }
        return packPdf(objects)
    }

    private fun paginate(document: StructuredDocument): List<List<String>> {
        val commands = mutableListOf<String>()
        commands += drawText(document.title, 18f, true)
        document.blocks.forEach { block ->
            when (block.kind) {
                "heading" -> commands += drawText(block.text, 14f, true)
                "paragraph", "source" -> wrap(block.text, 11f, false).forEach { commands += it }
                "bullets" -> block.items.forEach { item -> wrap("• $item", 11f, false).forEach { commands += it } }
                "link" -> commands += drawText("${block.text} ${block.url}", 11f, false)
                "table" -> commands += tableCommands(block)
                "chart" -> commands += chartCommands(block)
            }
        }
        val pages = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var used = 0f
        val limit = 640f
        commands.forEach { line ->
            val height = if (line.startsWith("BAR|")) 18f else line.substringBefore('|').toFloatOrNull() ?: 16f
            if (used + height > limit && current.isNotEmpty()) {
                pages += current
                current = mutableListOf()
                used = 0f
                if (pages.size >= DocumentGenerationLimits.MAX_PAGES) return pages
            }
            current += line
            used += height
        }
        if (current.isNotEmpty() && pages.size < DocumentGenerationLimits.MAX_PAGES) pages += current
        return pages.ifEmpty { listOf(listOf(drawText(document.title, 16f, true).first())) }
    }

    private fun tableCommands(block: DocBlock): List<String> {
        val lines = mutableListOf<String>()
        lines += drawText(block.text, 12f, true)
        val rows = listOf(block.headers) + block.rows
        rows.forEach { row -> lines += drawText(row.joinToString("  ") { it.take(28) }, 10f, false) }
        return lines
    }

    private fun chartCommands(block: DocBlock): List<String> {
        val lines = mutableListOf<String>()
        lines += drawText(block.text, 12f, true)
        val max = block.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        block.values.forEachIndexed { index, value ->
            val label = block.labels.getOrElse(index) { "Item" }.take(18)
            val width = ((value / max) * 240.0).toInt().coerceIn(2, 240)
            lines += "BAR|$label|$width|${plain(value)}"
        }
        return lines
    }

    private fun drawText(text: String, size: Float, bold: Boolean): List<String> = wrap(text, size, bold)

    private fun wrap(text: String, size: Float, bold: Boolean): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val next = if (current.isEmpty()) word else "$current $word"
            if (width(next, size) > 500f && current.isNotEmpty()) {
                lines += line(current.toString(), size, bold)
                current = StringBuilder(word)
            } else {
                current = StringBuilder(next)
            }
        }
        if (current.isNotEmpty()) lines += line(current.toString(), size, bold)
        return lines.ifEmpty { listOf(line(" ", size, bold)) }
    }

    private fun line(text: String, size: Float, bold: Boolean): String =
        "${size + 6}|${if (bold) "B" else "R"}|$text"

    private fun width(text: String, size: Float): Float = text.sumOf { char ->
        val factor = when (char) {
            'i', 'l', 'j', '.', ',', ' ' -> 0.28
            'm', 'w', 'M', 'W' -> 0.84
            else -> 0.52
        }
        factor * size
    }.toFloat()

    private fun pageContent(title: String, lines: List<String>, page: Int, total: Int): ByteArray {
        val content = StringBuilder()
        content.append(textOp(title.take(70), 54f, 760f, 10f, true, "0.12 0.23 0.37"))
        var y = 730f
        lines.forEach { line ->
            if (line.startsWith("BAR|")) {
                val parts = line.split('|')
                val label = parts.getOrElse(1) { "" }
                val bar = parts.getOrElse(2) { "4" }.toFloatOrNull() ?: 4f
                val value = parts.getOrElse(3) { "" }
                content.append(textOp("$label $value", 54f, y, 10f, false, "0 0 0"))
                content.append("0.12 0.23 0.37 rg\n")
                content.append("250 $y $bar 8 re f\n")
                y -= 18f
            } else {
                val size = line.substringBefore('|').toFloatOrNull() ?: 16f
                val bold = line.substringAfter('|').startsWith("B|")
                val text = line.substringAfter("|B|").substringAfter("|R|")
                content.append(textOp(text, 54f, y, min(size, 18f), bold, "0 0 0"))
                y -= size
            }
        }
        content.append(textOp("Page $page of $total", 54f, 36f, 9f, false, "0.29 0.33 0.40"))
        val bytes = content.toString().toByteArray(Charsets.US_ASCII)
        return "<< /Length ${bytes.size} >>\nstream\n".toByteArray(Charsets.US_ASCII) + bytes + "\nendstream".toByteArray(Charsets.US_ASCII)
    }

    private fun textOp(text: String, x: Float, y: Float, size: Float, bold: Boolean, color: String): String {
        val font = if (bold) "F2" else "F1"
        return "$color rg\nBT /$font $size Tf 1 0 0 1 $x $y Tm <${pdfHex(text)}> Tj ET\n"
    }

    private fun pdfHex(text: String): String = buildString {
        text.forEach { char ->
            if (!PdfText.encodable(char)) return@forEach
            append("%02X".format(char.code))
        }
    }

    private fun packPdf(objects: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, body ->
            offsets += out.size()
            out.write("${index + 1} 0 obj\n".toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n".toByteArray(Charsets.US_ASCII))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.US_ASCII))
        offsets.drop(1).forEach { offset ->
            out.write("%010d 00000 n \n".format(offset).toByteArray(Charsets.US_ASCII))
        }
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\n".toByteArray(Charsets.US_ASCII))
        out.write("startxref\n$xref\n%%EOF".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    fun docx(document: StructuredDocument): ByteArray {
        val image = document.blocks.firstOrNull { it.image != null && it.image.size > 8 && it.image[1] == 0x50.toByte() }?.image
        val body = StringBuilder()
        body.append(paragraph(document.title, "Title"))
        document.blocks.forEach { block ->
            when (block.kind) {
                "heading" -> body.append(paragraph(block.text, "Heading1"))
                "paragraph", "source" -> body.append(paragraph(block.text, "Normal"))
                "bullets" -> block.items.forEach { body.append(paragraph(it, "List")) }
                "link" -> body.append(hyperlink(block.text, block.url))
                "table" -> body.append(wordTable(block))
                "chart" -> {
                    body.append(paragraph(block.text, "Heading2"))
                    block.labels.forEachIndexed { index, label ->
                        body.append(paragraph("$label: ${block.values.getOrNull(index)?.let { plain(it) }.orEmpty()}", "Normal"))
                    }
                }
            }
        }
        if (image != null) body.append(imageParagraph())
        body.append(
            "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/>" +
                "<w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/>" +
                "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>",
        )
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
            <w:body>$body</w:body></w:document>
        """.trimIndent()
        val entries = mutableListOf(
            "[Content_Types].xml" to contentTypes(image != null, "word"),
            "_rels/.rels" to packageRels("word/document.xml"),
            "word/document.xml" to documentXml.toByteArray(),
            "word/styles.xml" to styles().toByteArray(),
            "word/numbering.xml" to numbering().toByteArray(),
            "word/header1.xml" to header(document.title).toByteArray(),
            "word/footer1.xml" to footer().toByteArray(),
            "word/_rels/document.xml.rels" to wordRels(image != null, document.blocks.filter { it.kind == "link" }).toByteArray(),
            "docProps/core.xml" to core(document).toByteArray(),
            "docProps/app.xml" to app("Word").toByteArray(),
        )
        if (image != null) entries += "word/media/chart.png" to image
        return zip(entries)
    }

    private fun paragraph(text: String, style: String): String {
        val props = if (style == "List") "<w:pPr><w:pStyle w:val=\"List\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
        else "<w:pPr><w:pStyle w:val=\"${xmlEscape(style)}\"/></w:pPr>"
        return "<w:p>$props<w:r><w:t xml:space=\"preserve\">${xmlEscape(text)}</w:t></w:r></w:p>"
    }

    private fun hyperlink(label: String, url: String): String =
        "<w:p><w:hyperlink r:id=\"rIdLink\"><w:r><w:rPr><w:rStyle w:val=\"Hyperlink\"/></w:rPr><w:t>${xmlEscape(label)}</w:t></w:r></w:hyperlink></w:p>"

    private fun wordTable(block: DocBlock): String {
        val rows = listOf(block.headers) + block.rows
        val xml = rows.joinToString("") { row ->
            "<w:tr>" + row.joinToString("") { cell ->
                "<w:tc><w:p><w:r><w:t>${xmlEscape(cell)}</w:t></w:r></w:p></w:tc>"
            } + "</w:tr>"
        }
        return "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>$xml</w:tbl>"
    }

    private fun imageParagraph(): String = """
        <w:p><w:r><w:drawing><wp:inline><wp:extent cx="3200400" cy="1800000"/><wp:docPr id="1" name="Chart"/>
        <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic>
        <pic:nvPicPr><pic:cNvPr id="0" name="chart.png"/><pic:cNvPicPr/></pic:nvPicPr>
        <pic:blipFill><a:blip r:embed="rIdImage"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
        <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="3200400" cy="1800000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
        </pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
    """.trimIndent()

    fun xlsx(document: StructuredDocument): ByteArray {
        val sheets = document.sheets.ifEmpty { listOf(Worksheet("Notes", listOf(listOf(textCell(document.title))), listOf(40.0), false, false)) }
        val strings = mutableListOf<String>()
        fun shared(value: String): Int {
            val safe = if (value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@")) "'$value" else value
            strings += safe
            return strings.lastIndex
        }
        val sheetXml = sheets.mapIndexed { index, sheet ->
            val rows = sheet.rows.mapIndexed { rowIndex, row ->
                val cells = row.mapIndexed { column, cell ->
                    val ref = "${columnName(column + 1)}${rowIndex + 1}"
                    when (cell.kind) {
                        "FORMULA" -> {
                            val formula = cell.formula.orEmpty()
                            if (FormulaPolicy.accepted(formula)) {
                                val cached = cell.cached?.let { "<v>${plain(it)}</v>" }.orEmpty()
                                "<c r=\"$ref\"><f>${xmlEscape(formula)}</f>$cached</c>"
                            } else {
                                "<c r=\"$ref\" t=\"s\"><v>${shared(formula)}</v></c>"
                            }
                        }
                        "CURRENCY" -> "<c r=\"$ref\" s=\"2\"><v>${plain(cell.number ?: 0.0)}</v></c>"
                        "PERCENT" -> "<c r=\"$ref\" s=\"3\"><v>${cell.number ?: 0.0}</v></c>"
                        "DATE" -> "<c r=\"$ref\" s=\"4\"><v>${plain(cell.number ?: 0.0)}</v></c>"
                        "NUMBER" -> "<c r=\"$ref\" s=\"5\"><v>${plain(cell.number ?: 0.0)}</v></c>"
                        else -> "<c r=\"$ref\" t=\"s\" s=\"${if (rowIndex == 0) 1 else 0}\"><v>${shared(cell.text)}</v></c>"
                    }
                }.joinToString("")
                "<row r=\"${rowIndex + 1}\">$cells</row>"
            }.joinToString("")
            val cols = sheet.widths.mapIndexed { column, width ->
                "<col min=\"${column + 1}\" max=\"${column + 1}\" width=\"$width\" customWidth=\"1\"/>"
            }.joinToString("")
            val freeze = if (sheet.freezeHeader) "<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>" else ""
            val lastColumn = columnName(sheet.rows.firstOrNull()?.size ?: 1)
            val filter = if (sheet.filter) "<autoFilter ref=\"A1:$lastColumn${sheet.rows.size}\"/>" else ""
            val drawing = if (sheet.chartValues.isNotEmpty()) "<drawing r:id=\"rIdChart\"/>" else ""
            val xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                $freeze<cols>$cols</cols><sheetData>$rows</sheetData>$filter$drawing</worksheet>
            """.trimIndent()
            "xl/worksheets/sheet${index + 1}.xml" to xml.toByteArray()
        }
        val sharedXml = "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"${strings.size}\" uniqueCount=\"${strings.size}\">" +
            strings.joinToString("") { "<si><t xml:space=\"preserve\">${xmlEscape(it)}</t></si>" } + "</sst>"
        val sheetEntries = sheets.mapIndexed { index, sheet ->
            "<sheet name=\"${xmlEscape(sheet.name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>"
        }.joinToString("")
        val workbook = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>$sheetEntries</sheets></workbook>
        """.trimIndent()
        val rels = sheets.mapIndexed { index, _ ->
            "<Relationship Id=\"rId${index + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${index + 1}.xml\"/>"
        }.joinToString("") +
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rIdStrings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>"
        val entries = mutableListOf(
            "[Content_Types].xml" to xlsxTypes(sheets).toByteArray(),
            "_rels/.rels" to packageRels("xl/workbook.xml"),
            "xl/workbook.xml" to workbook.toByteArray(),
            "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>".toByteArray(),
            "xl/sharedStrings.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>$sharedXml".toByteArray(),
            "xl/styles.xml" to xlsxStyles().toByteArray(),
            "docProps/core.xml" to core(document).toByteArray(),
            "docProps/app.xml" to app("Excel").toByteArray(),
        )
        entries += sheetXml
        val chartSheet = sheets.indexOfFirst { it.chartValues.isNotEmpty() }
        if (chartSheet >= 0) {
            val sheet = sheets[chartSheet]
            val last = sheet.chartValues.size + 1
            entries += "xl/charts/chart1.xml" to chartXml(sheet.name, last, sheet.chartTitle).toByteArray()
            entries += "xl/drawings/drawing1.xml" to drawingXml().toByteArray()
            entries += "xl/drawings/_rels/drawing1.xml.rels" to chartRel().toByteArray()
            entries += "xl/worksheets/_rels/sheet${chartSheet + 1}.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rIdChart\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>".toByteArray()
        }
        return zip(entries)
    }

    fun pptx(document: StructuredDocument): ByteArray {
        val slides = document.slides.take(DocumentGenerationLimits.MAX_SLIDES).ifEmpty {
            listOf(SlideSpec(document.title, listOf("No slide text was supplied."), ""))
        }
        val entries = mutableListOf<Pair<String, ByteArray>>()
        slides.forEachIndexed { index, slide ->
            val number = index + 1
            entries += "ppt/slides/slide$number.xml" to slideXml(slide).toByteArray()
            entries += "ppt/notesSlides/notesSlide$number.xml" to notesXml(slide.notes.ifBlank { "No speaker note was supplied." }).toByteArray()
            val imageRel = if (slide.image != null) "<Relationship Id=\"rIdImage\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image$number.png\"/>" else ""
            entries += "ppt/slides/_rels/slide$number.xml.rels" to (
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rIdLayout\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                    "<Relationship Id=\"rIdNotes\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide\" Target=\"../notesSlides/notesSlide$number.xml\"/>" +
                    imageRel + "</Relationships>"
                ).toByteArray()
            entries += "ppt/notesSlides/_rels/notesSlide$number.xml.rels" to (
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rIdNotes\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster\" Target=\"../notesMasters/notesMaster1.xml\"/>" +
                    "<Relationship Id=\"rIdSlide\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"../slides/slide$number.xml\"/></Relationships>"
                ).toByteArray()
            if (slide.image != null) entries += "ppt/media/image$number.png" to slide.image
        }
        val slideIds = slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 2}\"/>" }
        val slideRels = slides.indices.joinToString("") {
            "<Relationship Id=\"rId${it + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${it + 1}.xml\"/>"
        }
        entries += "ppt/presentation.xml" to """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
            <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
            <p:sldIdLst>$slideIds</p:sldIdLst>
            <p:sldSz cx="12192000" cy="6858000" type="screen16x9"/>
            <p:notesSz cx="6858000" cy="9144000"/>
            </p:presentation>
        """.trimIndent().toByteArray()
        entries += "ppt/_rels/presentation.xml.rels" to (
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
                slideRels + "</Relationships>"
            ).toByteArray()
        entries += "ppt/slideMasters/slideMaster1.xml" to masterXml().toByteArray()
        entries += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to (
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"
            ).toByteArray()
        entries += "ppt/slideLayouts/slideLayout1.xml" to layoutXml().toByteArray()
        entries += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to (
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"
            ).toByteArray()
        entries += "ppt/theme/theme1.xml" to themeXml().toByteArray()
        entries += "ppt/notesMasters/notesMaster1.xml" to notesMaster().toByteArray()
        entries += "ppt/notesMasters/_rels/notesMaster1.xml.rels" to (
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"
            ).toByteArray()
        entries += "ppt/presProps.xml" to "<p:presentationPr xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"/>".toByteArray()
        entries += "ppt/viewProps.xml" to "<p:viewPr xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:normalViewPr/></p:viewPr>".toByteArray()
        entries += "ppt/tableStyles.xml" to "<a:tblStyleLst xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>".toByteArray()
        entries += "docProps/core.xml" to core(document).toByteArray()
        entries += "docProps/app.xml" to app("PowerPoint").toByteArray()
        entries += "[Content_Types].xml" to pptxTypes(slides.size, slides.any { it.image != null }).toByteArray()
        entries += "_rels/.rels" to packageRels("ppt/presentation.xml")
        return zip(entries)
    }

    fun csv(document: StructuredDocument): String {
        val sheet = document.sheets.firstOrNull { it.name != "Notes" } ?: document.sheets.firstOrNull()
        val rows = sheet?.rows.orEmpty().ifEmpty { listOf(listOf(textCell(document.title))) }
        return document.title + "\n" + rows.joinToString("\n") { row ->
            row.joinToString(",") { cell ->
                val raw = when (cell.kind) {
                    "FORMULA" -> "'${cell.formula.orEmpty()}"
                    "TEXT" -> if (cell.text.startsWith("=") || cell.text.startsWith("+") || cell.text.startsWith("-") || cell.text.startsWith("@")) "'${cell.text}" else cell.text
                    "PERCENT" -> cell.number?.toString().orEmpty()
                    else -> cell.cached?.let { plain(it) } ?: cell.number?.let { plain(it) } ?: cell.text
                }
                "\"" + raw.replace("\"", "\"\"") + "\""
            }
        } + "\n"
    }

    fun text(document: StructuredDocument): String = buildString {
        append(document.title).append("\n\n")
        document.blocks.forEach { block ->
            when (block.kind) {
                "heading" -> append(block.text).append("\n\n")
                "bullets" -> block.items.forEach { append("- ").append(it).append('\n') }
                "table" -> {
                    append(block.text).append('\n')
                    (listOf(block.headers) + block.rows).forEach { append(it.joinToString(" | ")).append('\n') }
                }
                "chart" -> block.labels.forEachIndexed { index, label -> append(label).append(": ").append(block.values.getOrNull(index)?.let { plain(it) }.orEmpty()).append('\n') }
                else -> append(block.text).append('\n')
            }
            append('\n')
        }
        document.limitations.forEach { append(it).append('\n') }
    }

    fun markdown(document: StructuredDocument): String = buildString {
        append("# ").append(document.title).append("\n\n")
        document.blocks.forEach { block ->
            when (block.kind) {
                "heading" -> append("## ").append(block.text).append("\n\n")
                "bullets" -> block.items.forEach { append("- ").append(it).append('\n') }
                "table" -> {
                    append("| ").append(block.headers.joinToString(" | ")).append(" |\n")
                    append("| ").append(block.headers.joinToString(" | ") { "---" }).append(" |\n")
                    block.rows.forEach { append("| ").append(it.joinToString(" | ")).append(" |\n") }
                }
                "link" -> append("[").append(block.text).append("](").append(block.url).append(")\n\n")
                "chart" -> block.labels.forEachIndexed { index, label -> append("- ").append(label).append(": ").append(block.values.getOrNull(index)?.let { plain(it) }.orEmpty()).append('\n') }
                else -> append(block.text).append("\n\n")
            }
        }
    }

    fun json(document: StructuredDocument): String {
        val blocks = document.blocks.joinToString(",") { block ->
            "{\"kind\":${quote(block.kind)},\"text\":${quote(block.text)},\"items\":[${block.items.joinToString(",") { quote(it) }}]}"
        }
        val sheets = document.sheets.joinToString(",") { sheet ->
            val rows = sheet.rows.joinToString(",") { row ->
                "[" + row.joinToString(",") { cell ->
                    val formula = cell.formula?.let { ",\"formula\":${quote(it)}" }.orEmpty()
                    val cached = cell.cached?.let { ",\"cachedLocally\":${plain(it)}" }.orEmpty()
                    "{\"kind\":${quote(cell.kind)},\"text\":${quote(cell.text)}$formula$cached}"
                } + "]"
            }
            "{\"name\":${quote(sheet.name)},\"rows\":[$rows]}"
        }
        return "{\"origin\":\"LOCAL_RENDER\",\"aiGenerated\":false,\"title\":${quote(document.title)},\"template\":${quote(document.templateId)},\"blocks\":[$blocks],\"sheets\":[$sheets],\"limitations\":[${document.limitations.joinToString(",") { quote(it) }}]}"
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

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

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                check(!name.contains("..") && !name.startsWith("/") && !name.contains(':'))
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun packageRels(target: String) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$target"/>
        <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
        <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
    """.trimIndent().toByteArray()

    private fun core(document: StructuredDocument) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>${xmlEscape(document.title)}</dc:title><dc:creator>AI Rachabanda local renderer</dc:creator>
        <dc:description>Rendered locally from supplied text and stored records. This file is not an AI provider response.</dc:description>
        </cp:coreProperties>
    """.trimIndent()

    private fun app(name: String) = "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>AI Rachabanda $name</Application></Properties>"

    private fun styles() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:sz w:val="22"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="List"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/></w:style>
        <w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="1F3A5F"/><w:u w:val="single"/></w:rPr></w:style>
        </w:styles>
    """.trimIndent()

    private fun numbering() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/></w:lvl></w:abstractNum>
        <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>
    """.trimIndent()

    private fun header(title: String) = "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>${xmlEscape(title.take(80))}</w:t></w:r></w:p></w:hdr>"

    private fun footer() = "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>Locally rendered. Page </w:t></w:r><w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>"

    private fun wordRels(image: Boolean, links: List<DocBlock>): String {
        val imageRel = if (image) "<Relationship Id=\"rIdImage\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/chart.png\"/>" else ""
        val link = links.firstOrNull()?.let {
            "<Relationship Id=\"rIdLink\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"${xmlEscape(it.url)}\" TargetMode=\"External\"/>"
        }.orEmpty()
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            <Relationship Id="rIdNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
            <Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
            <Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
            $imageRel$link</Relationships>
        """.trimIndent()
    }

    private fun contentTypes(image: Boolean, kind: String): ByteArray {
        val png = if (image) "<Default Extension=\"png\" ContentType=\"image/png\"/>" else ""
        val word = """
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            <Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
            <Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
            <Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>
        """.trimIndent()
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>$png
            $word
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
            <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
            </Types>
        """.trimIndent().toByteArray()
    }

    private fun xlsxTypes(sheets: List<Worksheet>): String {
        val sheetOverrides = sheets.indices.joinToString("") {
            "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        val chart = if (sheets.any { it.chartValues.isNotEmpty() }) {
            "<Override PartName=\"/xl/charts/chart1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>" +
                "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>"
        } else {
            ""
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
            $sheetOverrides
            <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
            $chart
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
            <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
            </Types>
        """.trimIndent()
    }

    private fun xlsxStyles() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
        <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
        <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
        <cellStyleXfs count="1"><xf/></cellStyleXfs>
        <cellXfs count="6">
        <xf numFmtId="0" fontId="0" xfId="0"/>
        <xf numFmtId="0" fontId="1" xfId="0" applyFont="1"/>
        <xf numFmtId="7" fontId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="10" fontId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="14" fontId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="2" fontId="0" xfId="0" applyNumberFormat="1"/>
        </cellXfs></styleSheet>
    """.trimIndent()

    private fun chartXml(sheet: String, lastRow: Int, title: String) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
        <c:chart><c:title><c:tx><c:v>${xmlEscape(title.ifBlank { "Supplied amounts" })}</c:v></c:tx></c:title>
        <c:plotArea><c:barChart><c:barDir val="col"/><c:grouping val="clustered"/>
        <c:ser><c:idx val="0"/><c:order val="0"/><c:tx><c:v>Amount</c:v></c:tx>
        <c:cat><c:strRef><c:f>${xmlEscape(sheet)}!${'$'}A${'$'}2:${'$'}A${'$'}$lastRow</c:f></c:strRef></c:cat>
        <c:val><c:numRef><c:f>${xmlEscape(sheet)}!${'$'}B${'$'}2:${'$'}B${'$'}$lastRow</c:f></c:numRef></c:val>
        </c:ser><c:axId val="1"/><c:axId val="2"/></c:barChart>
        <c:catAx><c:axId val="1"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:axPos val="b"/><c:crossAx val="2"/></c:catAx>
        <c:valAx><c:axId val="2"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:axPos val="l"/><c:crossAx val="1"/></c:valAx>
        </c:plotArea><c:plotVisOnly val="1"/></c:chart></c:chartSpace>
    """.trimIndent()

    private fun drawingXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
        <xdr:twoCellAnchor><xdr:from><xdr:col>4</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
        <xdr:to><xdr:col>10</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>16</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>
        <xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="2" name="Chart 1"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>
        <xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm>
        <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart r:id="rId1"/></a:graphicData></a:graphic>
        </xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>
    """.trimIndent()

    private fun chartRel() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart1.xml"/>
        </Relationships>
    """.trimIndent()

    private fun slideXml(slide: SlideSpec): String {
        val lines = slide.lines.joinToString("") { "<a:p><a:r><a:rPr lang=\"en-US\" sz=\"2000\"/><a:t>${xmlEscape(it.take(240))}</a:t></a:r></a:p>" }
        val table = if (slide.table.isEmpty()) "" else {
            val header = "<a:tr>" + slide.headers.joinToString("") { "<a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>${xmlEscape(it)}</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc>" } + "</a:tr>"
            val rows = slide.table.joinToString("") { row ->
                "<a:tr>" + row.joinToString("") { "<a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>${xmlEscape(it)}</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc>" } + "</a:tr>"
            }
            "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"5\" name=\"Table\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"457200\" y=\"1800000\"/><a:ext cx=\"10058400\" cy=\"3600000\"/></p:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblGrid>" +
                slide.headers.joinToString("") { "<a:gridCol w=\"3000000\"/>" } + "</a:tblGrid>$header$rows</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
        }
        val picture = if (slide.image == null) "" else """
            <p:pic><p:nvPicPr><p:cNvPr id="6" name="${xmlEscape(slide.imageAlt.ifBlank { "Image" })}"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
            <p:blipFill><a:blip r:embed="rIdImage"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
            <p:spPr><a:xfrm><a:off x="6858000" y="1800000"/><a:ext cx="4114800" cy="2286000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>
        """.trimIndent()
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
            <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
            <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
            <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
            <p:spPr><a:xfrm><a:off x="457200" y="274320"/><a:ext cx="11277600" cy="900000"/></a:xfrm></p:spPr>
            <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="3200" b="1"/><a:t>${xmlEscape(slide.title.take(80))}</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
            <p:spPr><a:xfrm><a:off x="457200" y="1280160"/><a:ext cx="6200000" cy="4800000"/></a:xfrm></p:spPr>
            <p:txBody><a:bodyPr/><a:lstStyle/>${lines.ifBlank { "<a:p><a:r><a:t> </a:t></a:r></a:p>" }}</p:txBody></p:sp>
            $table$picture</p:spTree></p:cSld></p:sld>
        """.trimIndent()
    }

    private fun notesXml(notes: String) = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
        <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
        <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
        <p:sp><p:nvSpPr><p:cNvPr id="2" name="Notes"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="457200" y="914400"/><a:ext cx="5943600" cy="6400800"/></a:xfrm></p:spPr>
        <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${xmlEscape(notes.take(1500))}</a:t></a:r></a:p></p:txBody></p:sp>
        </p:spTree></p:cSld></p:notes>
    """.trimIndent()

    private fun masterXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
        <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>
        <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
        <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>
    """.trimIndent()

    private fun layoutXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank">
        <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld></p:sldLayout>
    """.trimIndent()

    private fun notesMaster() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:notesMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
        <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>
        <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
        </p:notesMaster>
    """.trimIndent()

    private fun themeXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Local">
        <a:themeElements><a:clrScheme name="Local"><a:dk1><a:srgbClr val="1F3A5F"/></a:dk1><a:lt1><a:srgbClr val="F7F4EF"/></a:lt1><a:dk2><a:srgbClr val="4A5568"/></a:dk2><a:lt2><a:srgbClr val="E7E2D8"/></a:lt2><a:accent1><a:srgbClr val="1F3A5F"/></a:accent1><a:accent2><a:srgbClr val="4A5568"/></a:accent2><a:accent3><a:srgbClr val="8A8175"/></a:accent3><a:accent4><a:srgbClr val="1F3A5F"/></a:accent4><a:accent5><a:srgbClr val="4A5568"/></a:accent5><a:accent6><a:srgbClr val="8A8175"/></a:accent6><a:hlink><a:srgbClr val="1F3A5F"/></a:hlink><a:folHlink><a:srgbClr val="8A8175"/></a:folHlink></a:clrScheme>
        <a:fontScheme name="Local"><a:majorFont><a:latin typeface="Calibri"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme>
        <a:fmtScheme name="Local"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>
        </a:themeElements></a:theme>
    """.trimIndent()

    private fun pptxTypes(slides: Int, image: Boolean): String {
        val slideParts = (1..slides).joinToString("") {
            "<Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" +
                "<Override PartName=\"/ppt/notesSlides/notesSlide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml\"/>"
        }
        val png = if (image) "<Default Extension=\"png\" ContentType=\"image/png\"/>" else ""
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>$png
            <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
            <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
            <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
            <Override PartName="/ppt/notesMasters/notesMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml"/>
            <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
            <Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>
            <Override PartName="/ppt/viewProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"/>
            <Override PartName="/ppt/tableStyles.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"/>
            $slideParts
            <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
            <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
            </Types>
        """.trimIndent()
    }
}
