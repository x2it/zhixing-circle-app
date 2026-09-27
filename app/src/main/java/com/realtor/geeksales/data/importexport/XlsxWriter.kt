package com.realtor.geeksales.data.importexport

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 纯 Kotlin 最小 XLSX 写入器，不依赖 Apache POI。
 * POI 的 SXSSFWorkbook 在 Android 依赖可写临时目录，常导致模板/导出丢失或报错。
 * 这里用 ZipOutputStream 直接生成标准 xlsx（inline string），Excel/WPS 均可打开。
 */
object XlsxWriter {

    fun write(out: OutputStream, sheetName: String, rows: List<List<String>>) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write(CONTENT_TYPES); zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels")); zip.write(ROOT_RELS); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/workbook.xml")); zip.write(workbookXml(sheetName)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels")); zip.write(WORKBOOK_RELS); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml")); zip.write(sheetXml(rows)); zip.closeEntry()
        }
    }

    private val CONTENT_TYPES: ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""".trimIndent().toByteArray(Charsets.UTF_8)

    private val ROOT_RELS: ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent().toByteArray(Charsets.UTF_8)

    private val WORKBOOK_RELS: ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""".trimIndent().toByteArray(Charsets.UTF_8)

    private fun workbookXml(sheetName: String): ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="${esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>""".trimIndent().toByteArray(Charsets.UTF_8)

    private fun sheetXml(rows: List<List<String>>): ByteArray {
        val sb = StringBuilder(rows.size * 64)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")
        sb.append("<sheetData>\n")
        rows.forEachIndexed { rowIdx, cells ->
            val rn = rowIdx + 1
            sb.append("<row r=\"$rn\">")
            cells.forEachIndexed { colIdx, v ->
                val ref = colName(colIdx) + rn
                sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                sb.append(esc(v))
                sb.append("</t></is></c>")
            }
            sb.append("</row>\n")
        }
        sb.append("</sheetData>\n</worksheet>\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun colName(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val m = (n - 1) % 26
            sb.insert(0, ('A'.code + m).toChar())
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun esc(s: String): String = buildString(s.length + 8) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
