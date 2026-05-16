package com.bv.notes.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.bv.notes.data.model.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

object PdfExporter {

    private val PAGE_W = 1000
    private val PAGE_H = 1414
    private val PAGE_GAP = 60f

    fun export(ctx: Context, note: Note, elements: List<NoteElement>, folderName: String = "General", pageRange: IntRange? = null): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cleanFolder = folderName.replace(" ", "_").take(15)
        val fileName = "EXPORT_${cleanFolder}_$ts.pdf"

        val internalDir = File(ctx.filesDir, "exports").apply { mkdirs() }
        val internalFile = File(internalDir, fileName)

        val doc = PdfDocument()
        val inkMap = mutableMapOf<String, List<InkStroke>>()
        elements.filter { it.type == ElementType.INK && it.inkJson != null }.forEach {
            inkMap[it.id] = deserialiseStrokes(it.inkJson!!)
        }

        if (note.format == NoteFormat.PAGES) {
            val range = pageRange ?: (0 until note.pageCount)
            for (p in range) {
                if (p < 0 || p >= note.pageCount) continue
                
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, p + 1).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                val pyOffset = p * (PAGE_H + PAGE_GAP)

                canvas.drawColor(Color.parseColor("#FDFAF6"))
                drawPaperTemplate(canvas, note.template, PAGE_H.toFloat())

                if (p == range.first && note.title.isNotBlank()) {
                    drawTitle(canvas, note.title)
                }

                canvas.save()
                canvas.translate(0f, -pyOffset)
                drawContent(ctx, canvas, elements, inkMap, pyOffset, pyOffset + PAGE_H)
                canvas.restore()
                doc.finishPage(page)
            }
        } else {
            // CANVAS mode: find bounds of all elements
            var minY = 0f; var maxY = PAGE_H.toFloat()
            var minX = 0f; var maxX = PAGE_W.toFloat()

            elements.forEach { el ->
                minX = min(minX, el.x); maxX = max(maxX, el.x + el.width)
                minY = min(minY, el.y); maxY = max(maxY, el.y + el.height)
                if (el.type == ElementType.INK) {
                    inkMap[el.id]?.forEach { s ->
                        s.pts.forEach { p ->
                            minX = min(minX, p.x); maxX = max(maxX, p.x)
                            minY = min(minY, p.y); maxY = max(maxY, p.y)
                        }
                    }
                }
            }

            val contentH = (maxY - minY) + 100f
            val contentW = max(PAGE_W.toFloat(), (maxX - minX) + 100f)
            
            val pageInfo = PdfDocument.PageInfo.Builder(contentW.toInt(), contentH.toInt(), 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas

            canvas.drawColor(Color.parseColor("#FDFAF6"))
            // For canvas, we just draw a huge grid/lines
            drawPaperTemplate(canvas, note.template, contentH, contentW)

            canvas.save()
            canvas.translate(-minX + 50f, -minY + 50f)
            drawContent(ctx, canvas, elements, inkMap, -1e6f, 1e6f)
            canvas.restore()
            doc.finishPage(page)
        }

        internalFile.outputStream().use { doc.writeTo(it) }
        saveToPublicDownloads(ctx, doc, fileName)
        doc.close()
        
        return internalFile
    }

    private fun drawTitle(canvas: Canvas, title: String) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 32f; color = Color.parseColor("#2C2416"); typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
        canvas.drawText(title, 64f, 80f, titlePaint)
        canvas.drawLine(64f, 96f, PAGE_W - 64f, 96f, Paint().apply { color = Color.parseColor("#C4B49A"); strokeWidth = 2f })
    }

    private fun drawContent(ctx: Context, c: Canvas, elements: List<NoteElement>, inkMap: Map<String, List<InkStroke>>, top: Float, bottom: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Images/Text/Links
        elements.filter { it.type != ElementType.INK }.forEach { el ->
            if (el.y + el.height > top && el.y < bottom) {
                drawElement(ctx, c, el, paint)
            }
        }
        // Ink
        elements.filter { it.type == ElementType.INK }.forEach { el ->
            inkMap[el.id]?.forEach { stroke ->
                val path = buildPath(stroke.pts)
                val pPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                    color = stroke.color; strokeWidth = stroke.width
                    if (Color.alpha(stroke.color) < 200) alpha = 120
                }
                c.drawPath(path, pPaint)
            }
        }
    }

    private fun drawElement(ctx: Context, c: Canvas, el: NoteElement, paint: Paint) {
        when (el.type) {
            ElementType.IMAGE -> el.imagePath?.let { path ->
                try {
                    val bmp = if (path.startsWith("content://")) {
                        ctx.contentResolver.openInputStream(android.net.Uri.parse(path))?.use { BitmapFactory.decodeStream(it) }
                    } else BitmapFactory.decodeFile(path)
                    bmp?.let { c.drawBitmap(it, null, RectF(el.x, el.y, el.x + el.width, el.y + el.height), paint) }
                } catch (e: Exception) {}
            }
            ElementType.TEXT_BOX -> {
                paint.textSize = el.textSize; paint.color = el.textColor
                val lines = el.textContent?.split("\n") ?: emptyList()
                var ty = el.y + el.textSize + 4f
                lines.forEach { c.drawText(it, el.x + 4f, ty, paint); ty += el.textSize + 4f }
            }
            ElementType.LINK -> {
                val label = el.linkLabel ?: el.linkUrl ?: "Link"
                c.drawRoundRect(RectF(el.x, el.y, el.x + el.width, el.y + el.height), 8f, 8f, Paint().apply { color = Color.parseColor("#F0E8DC") })
                c.drawText("🔗 $label", el.x + 8f, el.y + el.height / 2f + 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#6B5E4E"); textSize = 14f; isUnderlineText = true })
            }
            else -> {}
        }
    }

    private fun drawPaperTemplate(c: Canvas, template: PaperTemplate, h: Float, w: Float = PAGE_W.toFloat()) {
        val p = Paint().apply { color = Color.parseColor("#DDD5C8"); strokeWidth = 0.8f; alpha = 160 }
        val spacing = 32f
        when (template) {
            PaperTemplate.LINED -> { var y = 64f; while (y < h) { c.drawLine(0f, y, w, y, p); y += spacing } }
            PaperTemplate.GRID -> {
                var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h, p); x += spacing }
                var y = 0f; while (y < h) { c.drawLine(0f, y, w, y, p); y += spacing }
            }
            PaperTemplate.DOTTED -> {
                var y = 32f; while (y < h) {
                    var x = 32f; while (x < w) { c.drawCircle(x, y, 2f, p.apply { style = Paint.Style.FILL }); x += spacing }; y += spacing
                }
            }
            PaperTemplate.CORNELL -> {
                c.drawLine(200f, 0f, 200f, h * 0.82f, p.apply { strokeWidth = 1.5f })
                c.drawLine(0f, h * 0.82f, w, h * 0.82f, p)
                var y = 64f; while (y < h) { c.drawLine(0f, y, w, y, p.apply { strokeWidth = 0.8f }); y += spacing }
            }
            PaperTemplate.MUSIC_STAFF -> {
                var y = 80f; while (y < h - 100f) {
                    repeat(5) { c.drawLine(40f, y + it * 12f, w - 40f, y + it * 12f, p.apply { strokeWidth = 1f }) }
                    y += 120f
                }
            }
            else -> {}
        }
    }

    private fun buildPath(pts: List<InkPoint>): Path {
        val p = Path(); if (pts.isEmpty()) return p
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2f; val my = (pts[i].y + pts[i + 1].y) / 2f
            p.quadTo(pts[i].x, pts[i].y, mx, my)
        }
        if (pts.size > 1) p.lineTo(pts.last().x, pts.last().y)
        return p
    }

    private fun deserialiseStrokes(json: String): List<InkStroke> {
        return try {
            val strokes = mutableListOf<InkStroke>()
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            val list: List<Map<String, Any>> = gson.fromJson(json, type)
            list.forEach { s ->
                val color = (s["c"] as? Double)?.toInt() ?: Color.BLACK
                val width = (s["w"] as? Double)?.toFloat() ?: 3f
                val eraser = s["e"] as? Boolean ?: false
                val rawPts = s["p"] as? List<Map<String, Any>> ?: emptyList()
                val pts = rawPts.map { p -> InkPoint((p["x"] as Double).toFloat(), (p["y"] as Double).toFloat(), (p["p"] as Double).toFloat(), (p["t"] as Double).toLong()) }
                strokes.add(InkStroke(pts, color, width, eraser))
            }
            strokes
        } catch (e: Exception) { emptyList() }
    }

    private fun saveToPublicDownloads(ctx: Context, doc: PdfDocument, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/BVNotes/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = ctx.contentResolver.insert(collection, values)
                uri?.let {
                    ctx.contentResolver.openOutputStream(it)?.use { out -> doc.writeTo(out) }
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    ctx.contentResolver.update(it, values, null, null)
                }
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NoteNest/$fileName")
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { doc.writeTo(it) }
            }
        } catch (e: Exception) {}
    }

    data class InkPoint(val x: Float, val y: Float, val p: Float, val t: Long)
    data class InkStroke(val pts: List<InkPoint>, val color: Int, val width: Float, val isEraser: Boolean)
}
