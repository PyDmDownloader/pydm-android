package com.pydm.android

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun esc(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (ch in s) {
        val c = ch.code
        if (c < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') continue
        when (ch) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); else -> sb.append(ch)
        }
    }
    return sb.toString()
}

private fun ZipOutputStream.put(name: String, content: String) {
    putNextEntry(ZipEntry(name)); write(content.toByteArray(Charsets.UTF_8)); closeEntry()
}

/** Bina kisi library ke minimal .docx. */
object Docx {
    class Para(val text: String, val bold: Boolean = false, val size: Int = 0)

    fun build(paras: List<Para>): ByteArray {
        val body = StringBuilder()
        for (p in paras) {
            if (p.text.isEmpty()) { body.append("<w:p/>"); continue }
            body.append("<w:p><w:r>")
            if (p.bold || p.size > 0) {
                body.append("<w:rPr>")
                if (p.bold) body.append("<w:b/>")
                if (p.size > 0) body.append("<w:sz w:val=\"${p.size}\"/>")
                body.append("</w:rPr>")
            }
            body.append("<w:t xml:space=\"preserve\">").append(esc(p.text)).append("</w:t></w:r></w:p>")
        }
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" +
            body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.put("[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "</Types>")
            z.put("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                "</Relationships>")
            z.put("word/document.xml", doc)
        }
        return bos.toByteArray()
    }
}

/** Bina kisi library ke minimal .xlsx (inline strings, frozen header row). */
object Xlsx {
    class Sheet(val name: String, val header: List<String>, val rows: List<List<Any?>>)

    private fun col(i: Int): String {
        var n = i; val sb = StringBuilder()
        while (n >= 0) { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 }
        return sb.toString()
    }

    private fun sheetXml(s: Sheet): String {
        val widths = IntArray(s.header.size) { s.header[it].length }
        for (r in s.rows.take(200)) for ((i, v) in r.withIndex()) if (i < widths.size)
            widths[i] = maxOf(widths[i], (v?.toString() ?: "").length)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        sb.append("<cols>")
        for (i in widths.indices) sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${minOf(70, widths[i] + 2)}\" customWidth=\"1\"/>")
        sb.append("</cols><sheetData>")
        fun cell(ref: String, v: Any?) {
            when (v) {
                null -> {}
                is Number -> sb.append("<c r=\"$ref\"><v>$v</v></c>")
                else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
            }
        }
        sb.append("<row r=\"1\">")
        s.header.forEachIndexed { i, h -> cell(col(i) + "1", h) }
        sb.append("</row>")
        s.rows.forEachIndexed { ri, r ->
            sb.append("<row r=\"${ri + 2}\">")
            r.forEachIndexed { i, v -> cell(col(i) + (ri + 2), v) }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    fun build(sheets: List<Sheet>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            val ct = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            sheets.indices.forEach {
                ct.append("<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            ct.append("</Types>")
            z.put("[Content_Types].xml", ct.toString())
            z.put("_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>")
            val wb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            sheets.forEachIndexed { i, s -> wb.append("<sheet name=\"${esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>") }
            wb.append("</sheets></workbook>")
            z.put("xl/workbook.xml", wb.toString())
            val rels = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            sheets.indices.forEach {
                rels.append("<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>")
            }
            rels.append("</Relationships>")
            z.put("xl/_rels/workbook.xml.rels", rels.toString())
            sheets.forEachIndexed { i, s -> z.put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
        return bos.toByteArray()
    }
}
