package com.bv.notes.ui.editor

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.util.LruCache
import android.view.*
import android.view.ScaleGestureDetector
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import androidx.core.content.ContextCompat
import com.bv.notes.R
import com.bv.notes.data.model.*
import com.bv.notes.util.ElementSerializer
import java.io.File
import java.util.UUID
import kotlin.math.*

/**
 * NoteCanvasView — Discrete page-based canvas with:
 *  • A4 paper layout with vertical scroll
 *  • Multi-element model (ink, image, text-box, link)
 *  • Pressure-sensitive S Pen ink with hover preview
 *  • Object-based lasso tool for selecting and moving ink/elements
 */
class NoteCanvasView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    // ── Tool Modes ────────────────────────────────────────────────────────────
    enum class Tool { NONE, PEN, ERASER, SELECT, TEXT, LINK, HIGHLIGHTER, LASSO, SHAPES }

    val elements = mutableListOf<NoteElement>()
    private val inkStrokeMap = mutableMapOf<String, List<InkStroke>>()

    data class InkPoint(val x: Float, val y: Float, val p: Float, val t: Long)
    data class InkStroke(val pts: List<InkPoint>, val color: Int, val width: Float, val isEraser: Boolean)

    private val currentPts = mutableListOf<InkPoint>()
    private var activeInkElement: NoteElement? = null
    
    // Path Cache for performance optimization
    private val pathCache = mutableMapOf<String, List<Path>>()

    var tool = Tool.NONE
    var inkColor = Color.BLACK
    var penWidth = 3f
    var highlightWidth = 20f
    var highlightColor = Color.parseColor("#C8B97A")

    var inkWidth: Float
        get() = if (tool == Tool.HIGHLIGHTER) highlightWidth else penWidth
        set(value) { if (tool == Tool.HIGHLIGHTER) highlightWidth = value else penWidth = value }

    fun setCurrentColor(color: Int) {
        if (tool == Tool.HIGHLIGHTER) {
            highlightColor = color
        } else {
            inkColor = color
        }
    }

    var template = PaperTemplate.LINED
    var format = NoteFormat.PAGES
        set(value) {
            field = value
            if (value == NoteFormat.PAGES) {
                panX = (width - PAGE_W * scale) / 2f
            }
            invalidate()
        }

    var pageCount = 1
    var isCenterLocked = true
        set(value) {
            field = value
            if (value && format == NoteFormat.PAGES) {
                panX = (width - PAGE_W * scale) / 2f
            }
            invalidate()
        }

    val PAGE_W = 1000f
    val PAGE_H = 1414f // A4 ratio
    val PAGE_GAP = 60f

    var selectedElementId: String? = null
    var ignoreElementId: String? = null
    private var selectedInkIds = mutableSetOf<String>()
    private val undoStack = ArrayDeque<List<NoteElement>>()
    private val redoStack = ArrayDeque<List<NoteElement>>()

    // ── Transform (pan + zoom) ────────────────────────────────────────────────
    var scale = 1f; private set
    var panX = 100f; private set
    var panY = 100f; private set
    private val scaleDetector = ScaleGestureDetector(ctx, ScaleListener())
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var isPanning = false
    private var isStylusActive = false

    // Shape Detection
    private val shapeHandler = Handler(Looper.getMainLooper())
    private var shapeRunnable: Runnable? = null
    private var isShapeDetected = false

    // PDF Template
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    var pdfPath: String? = null
        set(value) {
            field = value; setupPdfRenderer()
        }

    // ── Lasso State ──────────────────────────────────────────────────────────
    private val lassoPath = Path()
    private val lassoPoints = mutableListOf<PointF>()
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    // ── Scrollbar ─────────────────────────────────────────────────────────────
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 220
        style = Paint.Style.FILL
    }
    private val scrollbarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Hover ─────────────────────────────────────────────────────────────────
    private var hoverX = -1f; private var hoverY = -1f; private var isHovering = false

    // ── Paints ────────────────────────────────────────────────────────────────
    private val voidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val templatePaint = Paint().apply { strokeWidth = 0.8f; alpha = 160 }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val imagePaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        isUnderlineText = true
    }
    private val bookmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        color = 0xFF5C3D1E.toInt() // Darker brown for contrast
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        alpha = 140
    }
    
    data class Bookmark(val name: String, val page: Int)
    var bookmarks = listOf<Bookmark>()
        set(value) { field = value; invalidate() }

    private val bitmapCache = object : LruCache<String, Bitmap>(20) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            oldValue?.recycle()
        }
    }

    fun releaseResources() {
        bitmapCache.evictAll()
        pathCache.clear()
        undoStack.clear()
        redoStack.clear()
        inkStrokeMap.clear()
        pdfRenderer?.close()
        pdfFileDescriptor?.close()
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onElementSelected: ((NoteElement?) -> Unit)? = null
    var onRequestTextInput: ((NoteElement) -> Unit)? = null
    var onRequestLinkInput: ((NoteElement) -> Unit)? = null
    var onCanvasChanged: (() -> Unit)? = null
    var onTransformChanged: (() -> Unit)? = null
    var onLassoSelectionActive: ((RectF?) -> Unit)? = null

    // ── Selection drag state ──────────────────────────────────────────────────
    private var dragStartX = 0f; private var dragStartY = 0f
    private var dragElementStartX = 0f; private var dragElementStartY = 0f
    private var isResizing = false; private var resizeHandle = -1
    private var elementStartW = 0f; private var elementStartH = 0f

    // Snapping
    private var snapLineX: Float? = null
    private var snapLineY: Float? = null
    private var lastSnappedX: Float? = null
    private var lastSnappedY: Float? = null
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7A5C3C.toInt()
        strokeWidth = 1.5f
        alpha = 100
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        refreshTheme()
    }

    fun refreshTheme() {
        inkColor = ContextCompat.getColor(context, R.color.text_primary)
        highlightColor = ContextCompat.getColor(context, R.color.accent_brown_light)
        
        voidPaint.color = ContextCompat.getColor(context, R.color.bg_sidebar)
        pageBgPaint.color = ContextCompat.getColor(context, R.color.bg_paper)
        pageShadowPaint.color = ContextCompat.getColor(context, R.color.canvas_page_shadow)
        lassoPaint.color = ContextCompat.getColor(context, R.color.canvas_lasso_fill)
        scrollbarPaint.color = ContextCompat.getColor(context, R.color.accent_brown)
        scrollbarTrackPaint.color = ContextCompat.getColor(context, R.color.canvas_page_shadow)
        hoverPaint.color = ContextCompat.getColor(context, R.color.canvas_hover)
        templatePaint.color = ContextCompat.getColor(context, R.color.canvas_template_line)
        selectionPaint.color = ContextCompat.getColor(context, R.color.canvas_selection_dash)
        handleBorderPaint.color = ContextCompat.getColor(context, R.color.accent_brown)
        textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        linkPaint.color = ContextCompat.getColor(context, R.color.canvas_link_text)
        invalidate()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun loadElements(json: String?) {
        elements.clear()
        elements.addAll(ElementSerializer.deserialize(json))
        inkStrokeMap.clear()
        pathCache.clear()
        elements.filter { it.type == ElementType.INK && it.inkJson != null }.forEach { el ->
            inkStrokeMap[el.id] = deserialiseStrokes(el.inkJson!!)
        }
        invalidate()
    }

    fun serialiseElements(): String {
        // Only serialise strokes that don't have an up-to-date inkJson
        elements.filter { it.type == ElementType.INK }.forEach { el ->
            if (el.inkJson == null) {
                val idx = elements.indexOf(el)
                if (idx >= 0) {
                    elements[idx] = el.copy(inkJson = serialiseStrokes(inkStrokeMap[el.id] ?: emptyList()))
                }
            }
        }
        return ElementSerializer.serialize(elements)
    }

    fun captureAsBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(elements.map { it.copy() })
        elements.clear(); elements.addAll(undoStack.removeLast())
        pathCache.clear()
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        saveUndo()
        elements.clear(); elements.addAll(redoStack.removeLast())
        pathCache.clear()
        invalidate()
    }

    fun deleteSelected() {
        if (selectedInkIds.isNotEmpty()) {
            saveUndo()
            elements.removeAll { selectedInkIds.contains(it.id) }
            selectedInkIds.forEach { 
                inkStrokeMap.remove(it)
                pathCache.remove(it)
            }
            selectedInkIds.clear()
            onLassoSelectionActive?.invoke(null)
            invalidate(); return
        }
        val id = selectedElementId ?: return
        saveUndo()
        elements.removeAll { it.id == id }
        inkStrokeMap.remove(id)
        selectedElementId = null
        onElementSelected?.invoke(null)
        invalidate()
    }

    fun duplicateSelected() {
        if (selectedInkIds.isNotEmpty()) {
            saveUndo()
            val newIds = mutableSetOf<String>()
            val offset = 60f 
            
            // Collect to list first to avoid ConcurrentModificationException
            val elementsToDuplicate = elements.filter { selectedInkIds.contains(it.id) }
            
            elementsToDuplicate.forEach { el ->
                val newId = UUID.randomUUID().toString()
                val originalStrokes = inkStrokeMap[el.id] ?: return@forEach
                
                // Deep copy strokes
                val newStrokes = originalStrokes.map { s ->
                    s.copy(pts = s.pts.map { p -> p.copy(x = p.x + offset, y = p.y + offset) })
                }
                
                inkStrokeMap[newId] = newStrokes
                
                // Remove path cloning - let onDraw build paths lazily to avoid UI hang
                pathCache.remove(newId)
                
                // Add new element
                elements.add(el.copy(id = newId, inkJson = el.inkJson))
                newIds.add(newId)
            }
            selectedInkIds.clear()
            selectedInkIds.addAll(newIds)
            performLassoSelect()
            onCanvasChanged?.invoke()
            invalidate(); return
        }
        
        val id = selectedElementId ?: return
        val el = elements.find { it.id == id } ?: return
        saveUndo()
        val newId = UUID.randomUUID().toString()
        val offset = 60f
        val newEl = el.copy(id = newId, x = el.x + offset, y = el.y + offset)
        elements.add(newEl)
        selectedElementId = newId
        tool = Tool.SELECT
        onElementSelected?.invoke(newEl)
        invalidate()
    }

    fun changeSelectedInkColor(color: Int) {
        if (selectedInkIds.isEmpty()) return
        saveUndo()
        elements.filter { selectedInkIds.contains(it.id) }.forEach { el ->
            val strokes = inkStrokeMap[el.id]?.map { it.copy(color = color) } ?: return@forEach
            inkStrokeMap[el.id] = strokes
            pathCache.remove(el.id)
            val idx = elements.indexOf(el)
            if (idx >= 0) elements[idx] = el.copy(inkJson = serialiseStrokes(strokes))
        }
        invalidate()
    }

    fun lockSelected() {
        val el = elements.find { it.id == selectedElementId } ?: return
        val idx = elements.indexOf(el)
        elements[idx] = el.copy(isLocked = !el.isLocked)
        invalidate()
    }

    fun clearSelections() {
        selectedElementId = null
        selectedInkIds.clear()
        onElementSelected?.invoke(null)
        onLassoSelectionActive?.invoke(null)
        invalidate()
    }

    fun addImage(imagePath: String) {
        saveUndo()
        val pageIdx = if (format == NoteFormat.PAGES) getCurrentPageIdx() else 0
        val py = pageIdx * (PAGE_H + PAGE_GAP)
        
        val el = NoteElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.IMAGE,
            imagePath = imagePath,
            x = 80f, y = py + 120f, width = 320f, height = 240f
        )
        elements.add(el)
        onCanvasChanged?.invoke()
        invalidate()
    }

    fun addTextBox() {
        saveUndo()
        val pageIdx = if (format == NoteFormat.PAGES) getCurrentPageIdx() else 0
        val py = pageIdx * (PAGE_H + PAGE_GAP)
        
        val el = NoteElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.TEXT_BOX,
            x = 60f, y = py + 100f, width = 280f, height = 80f,
            textContent = ""
        )
        elements.add(el)
        selectedElementId = el.id
        onRequestTextInput?.invoke(el)
        invalidate()
    }

    fun addLink(url: String, label: String) {
        saveUndo()
        val pageIdx = if (format == NoteFormat.PAGES) getCurrentPageIdx() else 0
        val py = pageIdx * (PAGE_H + PAGE_GAP)
        
        val el = NoteElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.LINK,
            x = 60f, y = py + 100f, width = 200f, height = 40f,
            linkUrl = url, linkLabel = label
        )
        elements.add(el)
        onCanvasChanged?.invoke()
        invalidate()
    }

    fun updateElement(id: String, textContent: String? = null, linkUrl: String? = null, linkLabel: String? = null) {
        val idx = elements.indexOfFirst { it.id == id }
        if (idx < 0) return
        val el = elements[idx]
        elements[idx] = el.copy(
            textContent = textContent ?: el.textContent,
            linkUrl = linkUrl ?: el.linkUrl,
            linkLabel = linkLabel ?: el.linkLabel
        )
        invalidate(); onCanvasChanged?.invoke()
    }

    fun goToPage(idx: Int) {
        if (idx < 0 || idx >= pageCount) return
        val targetY = idx * (PAGE_H + PAGE_GAP)
        if (isCenterLocked) {
            panX = (width - PAGE_W * scale) / 2f
        }
        panY = -targetY * scale + 60f
        invalidate()
    }

    fun getCurrentPageIdx(): Int {
        val cy = -panY / scale + (height / 2f) / scale
        return (cy / (PAGE_H + PAGE_GAP)).toInt().coerceIn(0, pageCount - 1)
    }

    fun deleteCurrentPage() {
        if (format != NoteFormat.PAGES || pageCount <= 1) return
        saveUndo()
        val currentIdx = getCurrentPageIdx()
        val pyStart = currentIdx * (PAGE_H + PAGE_GAP)
        val pyEnd = pyStart + PAGE_H + PAGE_GAP
        
        // Remove elements on this page
        elements.removeAll { it.y in pyStart..pyEnd }
        
        // Shift remaining elements up
        elements.replaceAll { 
            if (it.y > pyEnd) it.copy(y = it.y - (PAGE_H + PAGE_GAP))
            else it
        }
        
        // Handle Ink elements specifically since they might cross pages (though unlikely with current model)
        elements.filter { it.type == ElementType.INK }.forEach { el ->
            val strokes = inkStrokeMap[el.id]?.toMutableList() ?: return@forEach
            strokes.removeAll { s -> s.pts.any { it.y in pyStart..pyEnd } }
            // Shift points in remaining strokes
            val shiftedStrokes = strokes.map { s ->
                if (s.pts.any { it.y > pyEnd }) {
                    s.copy(pts = s.pts.map { it.copy(y = it.y - (PAGE_H + PAGE_GAP)) })
                } else s
            }
            inkStrokeMap[el.id] = shiftedStrokes
            val idx = elements.indexOf(el)
            if (idx >= 0) elements[idx] = el.copy(inkJson = serialiseStrokes(shiftedStrokes))
        }

        pageCount--
        if (currentIdx >= pageCount) goToPage(pageCount - 1)
        invalidate()
        onCanvasChanged?.invoke()
    }

    fun recenter() {
        scale = 1f
        if (format == NoteFormat.CANVAS) {
            panX = 100f; panY = 100f
        } else {
            panX = (width - PAGE_W * scale) / 2f
            panY = 60f
        }
        invalidate()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (format == NoteFormat.PAGES && isCenterLocked) {
            panX = (w - PAGE_W * scale) / 2f
            if (ow == 0) panY = 60f
        }
    }

    private fun setupPdfRenderer() {
        pdfRenderer?.close()
        pdfRenderer = null
        pdfFileDescriptor?.close()
        pdfFileDescriptor = null
        
        val path = pdfPath ?: return
        try {
            val file = File(path)
            if (file.exists()) {
                pdfFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(pdfFileDescriptor!!)
                pageCount = pdfRenderer?.pageCount ?: 1
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Notify page index change for UI indicator
        if (format == NoteFormat.PAGES) {
            onTransformChanged?.invoke()
        }
        
        if (format == NoteFormat.PAGES) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), voidPaint)
        } else {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pageBgPaint)
        }

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scale, scale)

        if (format == NoteFormat.PAGES) {
            for (i in 0 until pageCount) {
                val py = i * (PAGE_H + PAGE_GAP)
                
                // 2. Paper Edge Shadows (Professional Look)
                canvas.save()
                // Simple soft shadow using a translated rect
                pageShadowPaint.alpha = 30
                canvas.drawRect(8f, py + 8f, PAGE_W + 8f, py + PAGE_H + 8f, pageShadowPaint)
                pageShadowPaint.alpha = 15
                canvas.drawRect(14f, py + 14f, PAGE_W + 14f, py + PAGE_H + 14f, pageShadowPaint)
                canvas.restore()
                
                canvas.drawRect(0f, py, PAGE_W, py + PAGE_H, pageBgPaint)
                
                // Draw PDF Page if available
                val currentRenderer = pdfRenderer
                if (currentRenderer != null && i < currentRenderer.pageCount) {
                    val bmpKey = "PDF_${pdfPath}_$i"
                    var bmp = bitmapCache.get(bmpKey)
                    if (bmp == null) {
                        try {
                            val page = currentRenderer.openPage(i)
                            bmp = Bitmap.createBitmap(PAGE_W.toInt(), PAGE_H.toInt(), Bitmap.Config.ARGB_8888)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmapCache.put(bmpKey, bmp)
                            page.close()
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, 0f, py, imagePaint)
                    }
                }

                drawTemplateOnPage(canvas, i)
            }
        } else {
            val vLeft = -panX / scale
            val vTop = -panY / scale
            val vRight = (width - panX) / scale
            val vBottom = (height - panY) / scale
            drawInfiniteTemplate(canvas, vLeft, vTop, vRight, vBottom)
        }

        // Performance: Avoid .filter and .forEach on main render loop
        for (i in 0 until elements.size) {
            val el = elements[i]
            if (el.id == ignoreElementId) continue
            
            if (el.type != ElementType.INK) {
                drawElement(canvas, el)
                continue
            }

            // Draw INK element
            val strokes = inkStrokeMap[el.id] ?: if (el.inkJson != null) {
                deserialiseStrokes(el.inkJson).also { inkStrokeMap[el.id] = it }
            } else null
            
            if (strokes == null) continue

            val isLassoSelected = selectedInkIds.contains(el.id)
            val cachedPaths = pathCache[el.id] ?: strokes.map { buildPath(it.pts) }.also {
                pathCache[el.id] = it
            }

            for (j in 0 until strokes.size) {
                if (j >= cachedPaths.size) break
                val stroke = strokes[j]
                val path = cachedPaths[j]
                
                linePaint.color = if (isLassoSelected) ContextCompat.getColor(context, R.color.accent_brown) else stroke.color
                linePaint.strokeWidth = stroke.width
                val alpha = Color.alpha(stroke.color)
                
                if (alpha < 255) {
                    linePaint.alpha = (alpha * 0.5f).toInt() // Proper transparency for highlighters
                } else {
                    linePaint.alpha = 255
                }
                canvas.drawPath(path, linePaint)
            }
        }

        // Draw Snapping Guidelines
        snapLineX?.let { x ->
            canvas.drawLine(x, -1e6f, x, 1e6f, snapPaint)
        }
        snapLineY?.let { y ->
            canvas.drawLine(-1e6f, y, 1e6f, y, snapPaint)
        }

        if (!lassoPath.isEmpty) {
            canvas.drawPath(lassoPath, lassoPaint)
        }

        if (currentPts.size > 1) drawLivePath(canvas)

        selectedElementId?.let { id ->
            elements.find { it.id == id }?.let { drawSelectionHandles(canvas, it) }
        }

        if (isHovering) {
            val r = if (tool == Tool.ERASER) 24f else 5f
            canvas.drawCircle(hoverX, hoverY, r, hoverPaint)
        }

        canvas.restore()

        if (format == NoteFormat.PAGES) {
            drawScrollbar(canvas)
        }
    }

    private fun drawInfiniteTemplate(c: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val spacing = 32f
        when (template) {
            PaperTemplate.LINED -> {
                var y = ceil(top / spacing) * spacing
                while (y < bottom) { c.drawLine(left, y, right, y, templatePaint); y += spacing }
            }
            PaperTemplate.GRID -> {
                var x = ceil(left / spacing) * spacing
                while (x < right) { c.drawLine(x, top, x, bottom, templatePaint); x += spacing }
                var y = ceil(top / spacing) * spacing
                while (y < bottom) { c.drawLine(left, y, right, y, templatePaint); y += spacing }
            }
            PaperTemplate.DOTTED -> {
                var y = ceil(top / spacing) * spacing
                while (y < bottom) {
                    var x = ceil(left / spacing) * spacing
                    while (x < right) { c.drawCircle(x, y, 2f, templatePaint.apply { style = Paint.Style.FILL }); x += spacing }
                    y += spacing
                }
            }
            PaperTemplate.CORNELL -> {
                c.drawLine(200f, top, 200f, bottom, templatePaint.apply { strokeWidth = 1.5f })
                var y = ceil(top / spacing) * spacing
                while (y < bottom) { c.drawLine(left, y, right, y, templatePaint.apply { strokeWidth = 0.8f }); y += spacing }
            }
            PaperTemplate.MUSIC_STAFF -> {
                val staffSpacing = 96f
                var y = ceil(top / staffSpacing) * spacing
                while (y < bottom) {
                    repeat(5) { val ly = y + it * 12f; c.drawLine(left, ly, right, ly, templatePaint.apply { strokeWidth = 1f }) }
                    y += staffSpacing
                }
            }
            PaperTemplate.BLANK -> {}
        }
    }

    private fun drawScrollbar(c: Canvas) {
        val totalHeight = (pageCount * (PAGE_H + PAGE_GAP) - PAGE_GAP) * scale
        val viewHeight = height.toFloat()
        if (totalHeight <= viewHeight) return
        
        val trackHeight = viewHeight - 40f
        val barWidth = 4f
        val barRight = width - 10f
        
        val thumbHeight = (viewHeight / totalHeight * trackHeight).coerceIn(100f, trackHeight)
        val minPanY = viewHeight - totalHeight - 120f * scale
        val maxPanY = 60f
        val panRange = maxPanY - minPanY
        
        val scrollProgress = ((maxPanY - panY) / panRange).coerceIn(0f, 1f)
        val thumbY = 20f + scrollProgress * (trackHeight - thumbHeight)
        
        c.drawRoundRect(barRight - barWidth, thumbY, barRight, thumbY + thumbHeight, barWidth/2, barWidth/2, scrollbarPaint)
    }

    private fun drawTemplateOnPage(c: Canvas, pageIdx: Int) {
        val py = pageIdx * (PAGE_H + PAGE_GAP)
        val spacing = 32f
        when (template) {
            PaperTemplate.LINED -> {
                var y = py + 64f
                while (y < py + PAGE_H) { c.drawLine(0f, y, PAGE_W, y, templatePaint); y += spacing }
            }
            PaperTemplate.GRID -> {
                var x = 0f; while (x < PAGE_W) { c.drawLine(x, py, x, py + PAGE_H, templatePaint); x += spacing }
                var y = py; while (y < py + PAGE_H) { c.drawLine(0f, y, PAGE_W, y, templatePaint); y += spacing }
            }
            PaperTemplate.DOTTED -> {
                var y = py + 32f; while (y < py + PAGE_H) {
                    var x = 32f; while (x < PAGE_W) {
                        c.drawCircle(x, y, 2f, templatePaint.apply { style = Paint.Style.FILL }); x += spacing
                    }
                    y += spacing
                }
            }
            PaperTemplate.CORNELL -> {
                c.drawLine(200f, py, 200f, py + PAGE_H * 0.82f, templatePaint.apply { strokeWidth = 1.5f })
                c.drawLine(0f, py + PAGE_H * 0.82f, PAGE_W, py + PAGE_H * 0.82f, templatePaint)
                var y = py + 64f; while (y < py + PAGE_H) { c.drawLine(0f, y, PAGE_W, y, templatePaint.apply { strokeWidth = 0.8f }); y += 32f }
            }
            PaperTemplate.MUSIC_STAFF -> {
                var y = py + 80f; while (y < py + PAGE_H - 100f) {
                    repeat(5) { c.drawLine(40f, y + it * 12f, PAGE_W - 40f, y + it * 12f, templatePaint.apply { strokeWidth = 1f }) }
                    y += 120f
                }
            }
            PaperTemplate.BLANK -> {}
        }
        
        // Draw Bookmark Title on the left side
        bookmarks.find { it.page == pageIdx }?.let { b ->
            c.drawText(b.name, -16f, py + 40f, bookmarkPaint)
        }
    }

    private fun drawElement(c: Canvas, el: NoteElement) {
        when (el.type) {
            ElementType.IMAGE -> {
                if (el.imagePath != null) {
                    var bmp = bitmapCache.get(el.imagePath)
                    if (bmp == null) {
                        try {
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            if (el.imagePath!!.startsWith("content://") || el.imagePath!!.startsWith("file://")) {
                                val uri = Uri.parse(el.imagePath)
                                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                                
                                options.inSampleSize = calculateInSampleSize(options, (el.width * scale).toInt(), (el.height * scale).toInt())
                                options.inJustDecodeBounds = false
                                
                                context.contentResolver.openInputStream(uri)?.use { 
                                    bmp = BitmapFactory.decodeStream(it, null, options) 
                                }
                            } else {
                                BitmapFactory.decodeFile(el.imagePath, options)
                                options.inSampleSize = calculateInSampleSize(options, (el.width * scale).toInt(), (el.height * scale).toInt())
                                options.inJustDecodeBounds = false
                                bmp = BitmapFactory.decodeFile(el.imagePath, options)
                            }
                            if (bmp != null) bitmapCache.put(el.imagePath, bmp!!)
                        } catch (e: Exception) { }
                    }
                    if (bmp != null) {
                        c.drawBitmap(bmp!!, null, RectF(el.x, el.y, el.x + el.width, el.y + el.height), imagePaint)
                    }
                }
            }
            ElementType.TEXT_BOX -> {
                if (el.textContent != null) {
                    textPaint.textSize = el.textSize
                    textPaint.color = el.textColor
                    val lines = el.textContent.split("\n")
                    var ty = el.y + el.textSize + 4f
                    lines.forEach { line -> c.drawText(line, el.x + 4f, ty, textPaint); ty += el.textSize + 4f }
                }
            }
            ElementType.LINK -> {
                val label = el.linkLabel ?: el.linkUrl ?: "Link"
                val bgPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.canvas_link_bg); style = Paint.Style.FILL }
                c.drawRoundRect(RectF(el.x, el.y, el.x + el.width, el.y + el.height), 8f, 8f, bgPaint)
                val borderPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.canvas_link_border); style = Paint.Style.STROKE; strokeWidth = 1f }
                c.drawRoundRect(RectF(el.x, el.y, el.x + el.width, el.y + el.height), 8f, 8f, borderPaint)
                c.drawText("🔗 $label", el.x + 8f, el.y + el.height / 2f + 5f, linkPaint)
            }
            ElementType.INK -> {}
        }
        if (el.isLocked) {
            val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent_brown); textSize = 14f }
            c.drawText("🔒", el.x + el.width - 20f, el.y + 16f, lockPaint)
        }
    }

    private fun drawLivePath(c: Canvas) {
        val path = buildPath(currentPts)
        val w = inkWidth
        if (tool == Tool.ERASER) {
            val p = Paint(linePaint).apply { color = Color.parseColor("#33FF0000"); strokeWidth = 40f }
            c.drawPath(path, p)
        } else if (tool == Tool.HIGHLIGHTER) {
            linePaint.color = highlightColor; linePaint.strokeWidth = w
            linePaint.alpha = 100; c.drawPath(path, linePaint)
        } else {
            linePaint.color = inkColor; linePaint.strokeWidth = w * (0.5f + currentPts.last().p * 1.5f)
            linePaint.alpha = 255; c.drawPath(path, linePaint)
        }
    }

    private fun drawSelectionHandles(c: Canvas, el: NoteElement) {
        val rect = RectF(el.x, el.y, el.x + el.width, el.y + el.height)
        c.drawRoundRect(rect, 4f, 4f, selectionPaint)
        val hs = 12f
        listOf(el.x to el.y, el.x + el.width to el.y, el.x + el.width to el.y + el.height, el.x to el.y + el.height).forEach { (hx, hy) ->
            c.drawCircle(hx, hy, hs, handlePaint)
            c.drawCircle(hx, hy, hs, handleBorderPaint)
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

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Robust Stylus Detection - Check all pointers
        var anyStylus = false
        for (i in 0 until ev.pointerCount) {
            val toolType = ev.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                anyStylus = true; break
            }
        }
        val isStylus = anyStylus || (ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
        
        isStylusActive = isStylus
        
        // Multi-touch Zoom/Pan: ONLY if not using a stylus
        if (ev.pointerCount >= 2 && !isStylus) {
            // Cancel active ink/lasso if a second finger touches down
            // to prevent "stray dots" or accidental strokes during zoom.
            if (currentPts.isNotEmpty()) {
                currentPts.clear()
                activeInkElement = null
                invalidate()
            }
            if (!lassoPath.isEmpty) {
                lassoPath.reset()
                lassoPoints.clear()
                invalidate()
            }
            
            scaleDetector.onTouchEvent(ev)
            
            val fx = scaleDetector.focusX; val fy = scaleDetector.focusY
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { lastFocusX = fx; lastFocusY = fy }
                MotionEvent.ACTION_MOVE -> {
                    val dx = fx - lastFocusX; val dy = fy - lastFocusY
                    if (!isCenterLocked || format == NoteFormat.CANVAS) panX += dx
                    panY += dy
                    lastFocusX = fx; lastFocusY = fy; 
                    onTransformChanged?.invoke()
                    invalidate()
                }
            }
            return true
        }

        val cx = (ev.x - panX) / scale; val cy = (ev.y - panY) / scale
        val p = if (isStylus) ev.pressure.coerceIn(0.05f, 1f) else 0.5f
        
        // S-Pen Button Eraser Support - Samsung S-Pen typically uses BUTTON_STYLUS_PRIMARY
        // IMPORTANT: The button must be held WHILE the tip is touching the screen for this state to be detected.
        val isEraserButtonPressed = (ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
        
        // Samsung S-Pen specific: Tool type changes to ERASER when button is held on some models
        val actualTool = if (ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || isEraserButtonPressed) Tool.ERASER else tool

        // Prevention: If we are writing (Stylus), don't allow panning/zooming from the same touch stream
        if (isStylus) {
            parent.requestDisallowInterceptTouchEvent(true)
        }

        when (actualTool) {
            Tool.NONE -> {}
            Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPES, Tool.ERASER -> {
                if (actualTool == Tool.ERASER) handleErase(ev, cx, cy)
                else handleInk(ev, cx, cy, p, actualTool)
            }
            Tool.LASSO -> handleLasso(ev, cx, cy)
            Tool.SELECT -> handleSelect(ev, cx, cy)
            Tool.TEXT -> if (ev.action == MotionEvent.ACTION_UP) { addTextBox(); tool = Tool.SELECT }
            Tool.LINK -> if (ev.action == MotionEvent.ACTION_UP) {
                val tmp = NoteElement(UUID.randomUUID().toString(), ElementType.LINK, x = cx, y = cy, width = 220f, height = 44f)
                elements.add(tmp); onRequestLinkInput?.invoke(tmp); tool = Tool.SELECT
            }
        }
        return true
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                hoverX = (ev.x - panX) / scale; hoverY = (ev.y - panY) / scale; isHovering = true; invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> { isHovering = false; invalidate() }
        }
        return true
    }

    private fun handleInk(ev: MotionEvent, cx: Float, cy: Float, pressure: Float, t: Tool) {
        val mx = (ev.x - panX) / scale
        val my = (ev.y - panY) / scale
        
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                saveUndo()
                isShapeDetected = false
                shapeHandler.removeCallbacksAndMessages(null)
                currentPts.clear()
                currentPts.add(InkPoint(mx, my, pressure, System.currentTimeMillis()))
                if (activeInkElement == null) activeInkElement = NoteElement(UUID.randomUUID().toString(), ElementType.INK, inkColor = inkColor, inkWidth = inkWidth)
                
                if (t == Tool.SHAPES) {
                    shapeRunnable = Runnable {
                        if (currentPts.size > 10) {
                            recognizeAndSnapShape()
                            isShapeDetected = true
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            invalidate()
                        }
                    }
                    shapeHandler.postDelayed(shapeRunnable!!, 700)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isShapeDetected) return
                
                // If moving too much, cancel shape detection
                if (t == Tool.SHAPES) {
                    val last = currentPts.lastOrNull()
                    if (last != null && Math.hypot((mx - last.x).toDouble(), (my - last.y).toDouble()) > 10) {
                        shapeHandler.removeCallbacksAndMessages(null)
                        shapeHandler.postDelayed(shapeRunnable!!, 700)
                    }
                }

                for (i in 0 until ev.historySize) {
                    val hp = ev.getHistoricalPressure(i).coerceIn(0.05f, 1f)
                    currentPts.add(InkPoint((ev.getHistoricalX(i) - panX) / scale, (ev.getHistoricalY(i) - panY) / scale, hp, ev.getHistoricalEventTime(i)))
                }
                currentPts.add(InkPoint(mx, my, pressure, System.currentTimeMillis())); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                shapeHandler.removeCallbacksAndMessages(null)
                currentPts.add(InkPoint(mx, my, pressure, System.currentTimeMillis()))
                val el = activeInkElement ?: return
                val isHighlighter = t == Tool.HIGHLIGHTER
                val color = if (isHighlighter) highlightColor.let { Color.argb(120, Color.red(it), Color.green(it), Color.blue(it)) } else inkColor
                val width = inkWidth
                val newStroke = InkStroke(currentPts.toList(), color, width, isEraser = false)
                val existing = inkStrokeMap[el.id]?.toMutableList() ?: mutableListOf()
                existing.add(newStroke); inkStrokeMap[el.id] = existing
                val existingEl = elements.find { it.id == el.id }
                if (existingEl == null) elements.add(el.copy(id = el.id, type = ElementType.INK, inkJson = serialiseStrokes(existing)))
                else {
                    val idx = elements.indexOf(existingEl)
                    if (idx >= 0) elements[idx] = existingEl.copy(inkJson = serialiseStrokes(existing))
                }
                pathCache.remove(el.id) // Clear cache to rebuild with the new stroke
                currentPts.clear(); onCanvasChanged?.invoke(); invalidate()
            }
        }
    }

    private fun recognizeAndSnapShape() {
        if (currentPts.size < 10) return
        
        val first = currentPts.first()
        val last = currentPts.last()
        val dist = Math.hypot((first.x - last.x).toDouble(), (first.y - last.y).toDouble())
        
        val minX = currentPts.minOf { it.x }
        val maxX = currentPts.maxOf { it.x }
        val minY = currentPts.minOf { it.y }
        val maxY = currentPts.maxOf { it.y }
        val midX = (minX + maxX) / 2
        val midY = (minY + maxY) / 2
        val w = maxX - minX
        val h = maxY - minY
        
        val newPts = mutableListOf<InkPoint>()
        
        // 1. Line check (Open shape or ends far apart)
        if (dist > Math.max(w, h) * 0.5f) {
            newPts.add(InkPoint(first.x, first.y, 0.5f, 0))
            newPts.add(InkPoint(last.x, last.y, 0.5f, 0))
        } else {
            // 2. Closed shape analysis
            // Count "corners" by looking at rapid direction changes
            var corners = 0
            for (i in 2 until currentPts.size - 2) {
                val p1 = currentPts[i - 2]
                val p2 = currentPts[i]
                val p3 = currentPts[i + 2]
                val ang1 = Math.atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
                val ang2 = Math.atan2((p3.y - p2.y).toDouble(), (p3.x - p2.x).toDouble())
                var diff = Math.abs(ang1 - ang2)
                if (diff > Math.PI) diff = 2 * Math.PI - diff
                if (diff > Math.PI / 4) corners++ // Significant turn
            }

            if (corners < 5 && Math.abs(w - h) < Math.max(w, h) * 0.3f) {
                // Triangle or Square/Circle
                if (corners <= 2) {
                    // Circle
                    val r = (w + h) / 4f
                    for (i in 0..60) {
                        val angle = Math.PI * 2 * i / 60.0
                        newPts.add(InkPoint((midX + r * Math.cos(angle)).toFloat(), (midY + r * Math.sin(angle)).toFloat(), 0.5f, 0))
                    }
                } else if (corners == 3) {
                    // Triangle
                    newPts.add(InkPoint(midX, minY, 0.5f, 0))
                    newPts.add(InkPoint(maxX, maxY, 0.5f, 0))
                    newPts.add(InkPoint(minX, maxY, 0.5f, 0))
                    newPts.add(InkPoint(midX, minY, 0.5f, 0))
                } else {
                    // Square
                    newPts.add(InkPoint(minX, minY, 0.5f, 0))
                    newPts.add(InkPoint(maxX, minY, 0.5f, 0))
                    newPts.add(InkPoint(maxX, maxY, 0.5f, 0))
                    newPts.add(InkPoint(minX, maxY, 0.5f, 0))
                    newPts.add(InkPoint(minX, minY, 0.5f, 0))
                }
            } else {
                // Fallback to rectangle for anything else closed
                newPts.add(InkPoint(minX, minY, 0.5f, 0))
                newPts.add(InkPoint(maxX, minY, 0.5f, 0))
                newPts.add(InkPoint(maxX, maxY, 0.5f, 0))
                newPts.add(InkPoint(minX, maxY, 0.5f, 0))
                newPts.add(InkPoint(minX, minY, 0.5f, 0))
            }
        }
        
        if (newPts.isNotEmpty()) {
            currentPts.clear()
            currentPts.addAll(newPts)
        }
    }

    private fun handleErase(ev: MotionEvent, cx: Float, cy: Float) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) saveUndo()
        
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
            // Process historical points for smooth continuous erasing
            for (i in 0 until ev.historySize) {
                performErase((ev.getHistoricalX(i) - panX) / scale, (ev.getHistoricalY(i) - panY) / scale)
            }
            performErase(cx, cy)
        }
        
        if (ev.actionMasked == MotionEvent.ACTION_UP) onCanvasChanged?.invoke()
    }

    private fun performErase(cx: Float, cy: Float) {
        var changed = false; val threshold = 25f / scale
        elements.filter { it.type == ElementType.INK }.forEach { el ->
            val strokes = inkStrokeMap[el.id]?.toMutableList() ?: return@forEach
            val iterator = strokes.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().pts.any { p -> hypot(p.x - cx, p.y - cy) < (threshold + 3f) }) { iterator.remove(); changed = true }
            }
            if (changed) {
                inkStrokeMap[el.id] = strokes
                pathCache.remove(el.id) // Clear cache for modified element
                val idx = elements.indexOf(el); if (idx >= 0) elements[idx] = el.copy(inkJson = serialiseStrokes(strokes))
            }
        }
        if (changed) invalidate()
    }

    private fun handleLasso(ev: MotionEvent, cx: Float, cy: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                var hitInk = false
                if (selectedInkIds.isNotEmpty()) {
                    elements.filter { selectedInkIds.contains(it.id) }.forEach { el ->
                        if (inkStrokeMap[el.id]?.any { s -> s.pts.any { p -> hypot(p.x - cx, p.y - cy) < 40f } } == true) hitInk = true
                    }
                }

                if (hitInk) {
                    dragStartX = cx; dragStartY = cy
                    onLassoSelectionActive?.invoke(null)
                } else {
                    selectedInkIds.clear()
                    selectedElementId = null
                    onElementSelected?.invoke(null)
                    onLassoSelectionActive?.invoke(null)
                    lassoPoints.clear()
                    lassoPath.reset()
                    lassoPoints.add(PointF(cx, cy))
                    lassoPath.moveTo(cx, cy)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectedInkIds.isNotEmpty()) {
                    val dx = cx - dragStartX; val dy = cy - dragStartY
                    moveSelectedStrokes(dx, dy)
                    dragStartX = cx; dragStartY = cy
                } else {
                    lassoPoints.add(PointF(cx, cy))
                    lassoPath.lineTo(cx, cy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (selectedInkIds.isEmpty() && !lassoPath.isEmpty) {
                    lassoPath.close()
                    performLassoSelect()
                    lassoPath.reset()
                    lassoPoints.clear()
                } else if (selectedInkIds.isNotEmpty()) {
                    performLassoSelect() // Recalculate bounds to show menu at new pos
                }
                onCanvasChanged?.invoke()
                invalidate()
            }
        }
    }

    private fun handleSelect(ev: MotionEvent, cx: Float, cy: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val selEl = elements.find { it.id == selectedElementId }
                if (selEl != null && !selEl.isLocked) {
                    val handle = hitHandle(cx, cy, selEl)
                    if (handle >= 0) {
                        isResizing = true; resizeHandle = handle; dragStartX = cx; dragStartY = cy
                        elementStartW = selEl.width; elementStartH = selEl.height
                        return
                    }
                }
                isResizing = false

                val hit = elements.lastOrNull { it.type != ElementType.INK && hitTest(cx, cy, it) }
                
                selectedElementId = hit?.id
                selectedInkIds.clear()
                onElementSelected?.invoke(hit)
                if (hit != null) {
                    dragStartX = cx; dragStartY = cy
                    dragElementStartX = hit.x; dragElementStartY = hit.y
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val el = elements.find { it.id == selectedElementId } ?: return
                if (el.isLocked) return
                val idx = elements.indexOf(el); var dx = cx - dragStartX; var dy = cy - dragStartY
                
                snapLineX = null
                snapLineY = null

                if (isResizing) {
                    elements[idx] = when (resizeHandle) {
                        0 -> el.copy(x = dragElementStartX + dx, y = dragElementStartY + dy, width = (elementStartW - dx).coerceAtLeast(40f), height = (elementStartH - dy).coerceAtLeast(30f))
                        1 -> el.copy(y = dragElementStartY + dy, width = (elementStartW + dx).coerceAtLeast(40f), height = (elementStartH - dy).coerceAtLeast(30f))
                        2 -> el.copy(width = (elementStartW + dx).coerceAtLeast(40f), height = (elementStartH + dy).coerceAtLeast(30f))
                        3 -> el.copy(x = dragElementStartX + dx, width = (elementStartW - dx).coerceAtLeast(40f), height = (elementStartH + dy).coerceAtLeast(30f))
                        else -> el
                    }
                } else {
                    var targetX = dragElementStartX + dx
                    var targetY = dragElementStartY + dy
                    
                    if (format == NoteFormat.PAGES) {
                        val centerX = PAGE_W / 2f
                        val threshold = 20f
                        
                        // Vertical Center Snap
                        if (Math.abs((targetX + el.width/2f) - centerX) < threshold) {
                            targetX = centerX - el.width/2f
                            snapLineX = centerX
                            if (lastSnappedX != centerX) {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastSnappedX = centerX
                            }
                        } else {
                            lastSnappedX = null
                        }
                        
                        // Edge Snaps
                        if (Math.abs(targetX - 20f) < threshold) { 
                            targetX = 20f; snapLineX = 20f 
                        }
                        if (Math.abs(targetX + el.width - (PAGE_W - 20f)) < threshold) { targetX = PAGE_W - 20f - el.width; snapLineX = PAGE_W - 20f }
                        
                        // Horizontal Page Center Snap
                        val pgIdx = getCurrentPageIdx()
                        val pgCenterY = pgIdx * (PAGE_H + PAGE_GAP) + PAGE_H / 2f
                        if (Math.abs((targetY + el.height/2f) - pgCenterY) < threshold) {
                            targetY = pgCenterY - el.height/2f
                            snapLineY = pgCenterY
                            if (lastSnappedY != pgCenterY) {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastSnappedY = pgCenterY
                            }
                        } else {
                            lastSnappedY = null
                        }
                    }
                    
                    elements[idx] = el.copy(x = targetX, y = targetY)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isResizing = false
                snapLineX = null
                snapLineY = null
                onCanvasChanged?.invoke()
                invalidate()
            }
        }
    }

    private fun performLassoSelect() {
        val region = Region(); val pathBounds = RectF()
        lassoPath.computeBounds(pathBounds, true)
        region.setPath(lassoPath, Region(pathBounds.left.toInt(), pathBounds.top.toInt(), pathBounds.right.toInt(), pathBounds.bottom.toInt()))
        selectedInkIds.clear()
        elements.filter { it.type == ElementType.INK }.forEach { el ->
            if (inkStrokeMap[el.id]?.any { s -> s.pts.any { p -> region.contains(p.x.toInt(), p.y.toInt()) } } == true) selectedInkIds.add(el.id)
        }
        
        if (selectedInkIds.isNotEmpty()) {
            val bounds = RectF()
            var first = true
            elements.filter { selectedInkIds.contains(it.id) }.forEach { el ->
                val strokes = inkStrokeMap[el.id] ?: return@forEach
                strokes.forEach { s -> s.pts.forEach { p ->
                    if (first) { bounds.set(p.x, p.y, p.x, p.y); first = false }
                    else { bounds.left = min(bounds.left, p.x); bounds.top = min(bounds.top, p.y); bounds.right = max(bounds.right, p.x); bounds.bottom = max(bounds.bottom, p.y) }
                }}
            }
            onLassoSelectionActive?.invoke(bounds)
        } else {
            onLassoSelectionActive?.invoke(null)
        }
    }

    private val transformMatrix = Matrix()
    private fun moveSelectedStrokes(dx: Float, dy: Float) {
        elements.filter { selectedInkIds.contains(it.id) }.forEach { el ->
            val strokes = inkStrokeMap[el.id]?.map { s ->
                s.copy(pts = s.pts.map { p -> p.copy(x = p.x + dx, y = p.y + dy) })
            } ?: return@forEach
            inkStrokeMap[el.id] = strokes
            
            // Performance: Use a reusable matrix
            pathCache[el.id]?.forEach { path ->
                transformMatrix.setTranslate(dx, dy)
                path.transform(transformMatrix)
            }
            // We do NOT modify el.inkJson here to keep it visible during the move
        }
        invalidate()
    }

    private fun hitTest(x: Float, y: Float, el: NoteElement) = x in el.x..el.x + el.width && y in el.y..el.y + el.height

    private fun hitHandle(x: Float, y: Float, el: NoteElement): Int {
        val hs = 40f / scale
        if (hypot(x - el.x, y - el.y) < hs) return 0
        if (hypot(x - (el.x + el.width), y - el.y) < hs) return 1
        if (hypot(x - (el.x + el.width), y - (el.y + el.height)) < hs) return 2
        if (hypot(x - el.x, y - (el.y + el.height)) < hs) return 3
        return -1
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            // IGNORE if using a stylus to prevent accidental zoom while writing/erasing
            if (isStylusActive) return false

            val oldScale = scale
            // Apply a smoothing factor to the raw scale delta
            val factor = (1f + (d.scaleFactor - 1f) * 0.6f)
            scale = (scale * factor).coerceIn(0.2f, 5f)
            
            val realFactor = scale / oldScale
            if (!isCenterLocked || format == NoteFormat.CANVAS) panX = d.focusX - (d.focusX - panX) * realFactor
            else panX = (width - PAGE_W * scale) / 2f
            panY = d.focusY - (d.focusY - panY) * realFactor
            
            onTransformChanged?.invoke()
            invalidate(); return true
        }
    }

    private fun serialiseStrokes(strokes: List<InkStroke>): String {
        val list = strokes.map { s ->
            mapOf("c" to s.color, "w" to s.width, "e" to s.isEraser, "p" to s.pts.map { mapOf("x" to it.x, "y" to it.y, "p" to it.p, "t" to it.t) })
        }
        return com.google.gson.Gson().toJson(list)
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

    private fun saveUndo() {
        undoStack.addLast(elements.map { it.copy() })
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
