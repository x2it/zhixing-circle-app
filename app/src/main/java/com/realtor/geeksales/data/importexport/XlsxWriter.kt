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

    /** 单表写入（兼容旧调用） */
    fun write(out: OutputStream, sheetName: String, rows: List<List<String>>) {
        writeMulti(out, listOf(sheetName to rows))
    }

    /** 多表写入：sheets = [(表名, 行)]，用于时光机快照（客户/跟进/标签/短信） */
    fun writeMulti(out: OutputStream, sheets: List<Pair<String, List<List<String>>>>) {
        val safe = sheets.map { (name, rows) -> (name.take(31)) to rows }
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(contentTypes(safe.size))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels")); zip.write(ROOT_RELS); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/workbook.xml")); zip.write(workbookXml(safe)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels")); zip.write(workbookRels(safe.size)); zip.closeEntry()
            safe.forEachIndexed { i, (_, rows) ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
                zip.write(sheetXml(rows))
                zip.closeEntry()
            }
        }
    }

    private val ROOT_RELS: ByteArray =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent().toByteArray(Charsets.UTF_8)

    private fun contentTypes(count: Int): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        (1..count).forEach { i ->
            sb.append("\n  <Override PartName=\"/xl/worksheets/sheet$i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("\n</Types>")
        return sb.toString().trimIndent().toByteArray(Charsets.UTF_8)
    }

    private fun workbookXml(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        sb.append("<sheets>")
        sheets.forEachIndexed { i, (name, _) ->
            sb.append("<sheet name=\"${esc(name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
        }
        sb.append("</sheets></workbook>")
        return sb.toString().trimIndent().toByteArray(Charsets.UTF_8)
    }

    private fun workbookRels(count: Int): ByteArray {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        (1..count).forEach { i ->
            sb.append("\n  <Relationship Id=\"rId$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$i.xml\"/>")
        }
        sb.append("\n</Relationships>")
        return sb.toString().trimIndent().toByteArray(Charsets.UTF_8)
    }

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
