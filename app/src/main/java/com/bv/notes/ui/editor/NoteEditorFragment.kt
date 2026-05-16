package com.bv.notes.ui.editor

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.util.TypedValue
import android.text.*
import android.text.style.BackgroundColorSpan
import android.widget.*
import androidx.appcompat.widget.SearchView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.bv.notes.R
import com.bv.notes.data.model.*
import com.bv.notes.databinding.FragmentEditorBinding
import com.bv.notes.util.*
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.TranscriptSegment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class NoteEditorFragment : Fragment() {
    private var _b: FragmentEditorBinding? = null
    private val b get() = _b!!
    private val vm: NoteViewModel by activityViewModels { NoteViewModelFactory(requireActivity().application) }
    private var note: Note? = null
    private lateinit var audio: AudioRecorderHelper
    private lateinit var lecture: LectureManager
    private var pendingImageUri: Uri? = null
    private val ampHandler = Handler(Looper.getMainLooper())
    private var currentTool = NoteCanvasView.Tool.NONE
    private var editingElementId: String? = null
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var currentLassoBounds: RectF? = null
    private var isPlacementMode = false
    
    private val updateProgressAction = object : Runnable {
        override fun run() {
            if (b.lectureVideoView.isPlaying) {
                val current = b.lectureVideoView.currentPosition
                val duration = b.lectureVideoView.duration
                if (duration > 0) {
                    b.lectureSeekBar.progress = (current * 100 / duration)
                    b.tvLectureTime.text = formatTime(current)
                }
                playbackHandler.postDelayed(this, 500)
            }
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private val hideControlsAction = Runnable { b.lectureControls.isVisible = false }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) openCamera() else snack("Camera permission needed")
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startRecording() else snack("Microphone permission needed")
    }

    private val lecturePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.all { it.value }) startLectureRecording() else snack("Camera & Audio needed for lectures")
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.data?.let { addImageFromUri(it) }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) pendingImageUri?.let { addImageFromUri(it) }
    }

    private val saveDebounceHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentEditorBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        audio = AudioRecorderHelper(requireContext())
        lecture = LectureManager(requireContext())
        setupTranscriptionObserver()
        val noteId = arguments?.getLong("noteId") ?: return
        
        b.canvas.loadElements("[]")
        b.transcriptCard.isVisible = false
        b.btnShowTranscript.alpha = 0.6f

        vm.loadNote(noteId)
        vm.currentNote.observe(viewLifecycleOwner) { n ->
            if (n != null && n.id == noteId && note == null) {
                note = n
                populateEditor(n)
            } else if (n != null && n.id == noteId) {
                note = n
            }
        }
        setupToolbar()
        setupToolPalette()
        setupCanvasCallbacks()
        setupBottomBar()
        animateEntrance()
        updateTranscriptLayout(resources.configuration.orientation)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTranscriptLayout(newConfig.orientation)
        b.canvas.refreshTheme()
        b.canvas.post { b.canvas.invalidate() }
    }

    private fun updateTranscriptLayout(orientation: Int) {
        val isLandscape = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        b.canvasTranscriptContainer.orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        
        val canvasLp = (b.canvas.parent as View).layoutParams as LinearLayout.LayoutParams
        if (isLandscape) {
            canvasLp.width = 0; canvasLp.height = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            canvasLp.width = LinearLayout.LayoutParams.MATCH_PARENT; canvasLp.height = 0
        }
        canvasLp.weight = 1f; (b.canvas.parent as View).layoutParams = canvasLp

        val lp = b.transcriptCard.layoutParams as LinearLayout.LayoutParams
        if (isLandscape) {
            lp.width = (240 * resources.displayMetrics.density).toInt(); lp.height = LinearLayout.LayoutParams.MATCH_PARENT
        } else {
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT; lp.height = (180 * resources.displayMetrics.density).toInt()
        }
        lp.weight = 0f; b.transcriptCard.layoutParams = lp
        b.canvasTranscriptContainer.requestLayout()
    }

    private fun animateEntrance() {
        b.editorRoot.alpha = 0f
        b.editorRoot.animate().alpha(1f).setDuration(400).start()
        
        // Added smooth fade-in for canvas paper texture
        b.canvas.alpha = 0f
        b.canvas.animate().alpha(1f).setDuration(800).setStartDelay(200).start()
    }

    private fun populateEditor(n: Note) {
        b.editorTitle.setText(if (n.title == "Untitled") "" else n.title)
        b.canvas.template = n.template
        b.canvas.format = n.format
        b.canvas.pdfPath = n.pdfTemplatePath
        b.canvas.isCenterLocked = n.isCenterLocked
        b.canvas.pageCount = n.pageCount
        b.canvas.loadElements(n.elementsJson)
        
        // Pass bookmarks to canvas for rendering
        val type = object : TypeToken<List<Bookmark>>() {}.type
        val bookmarks: List<Bookmark> = try {
            Gson().fromJson(n.bookmarksJson ?: "[]", type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
        b.canvas.bookmarks = bookmarks.map { NoteCanvasView.Bookmark(it.name, it.page) }

        updatePageNavigationUI()
    }

    private fun setupToolbar() {
        b.btnBack.setOnClickListener { saveNow(); findNavController().navigateUp() }
        b.btnUndo.setOnClickListener { b.canvas.undo() }
        b.btnRedo.setOnClickListener { b.canvas.redo() }
        b.btnRecenter.setOnClickListener { b.canvas.recenter() }
        b.btnMore.setOnClickListener { showMoreSheet() }
        b.editorTitle.setOnFocusChangeListener { _, _ -> saveNow() }
    }

    private fun setupToolPalette() {
        val toolMap = mapOf(
            b.toolPen to NoteCanvasView.Tool.PEN,
            b.toolHighlight to NoteCanvasView.Tool.HIGHLIGHTER,
            b.toolEraser to NoteCanvasView.Tool.ERASER,
            b.toolSelect to NoteCanvasView.Tool.SELECT,
            b.toolLasso to NoteCanvasView.Tool.LASSO,
            b.toolText to NoteCanvasView.Tool.TEXT,
            b.toolLink to NoteCanvasView.Tool.LINK,
            b.toolShapes to NoteCanvasView.Tool.SHAPES
        )
        toolMap.forEach { (btn, tool) ->
            btn.setOnClickListener {
                if (b.canvas.tool == tool) {
                    b.canvas.tool = NoteCanvasView.Tool.NONE
                    currentTool = NoteCanvasView.Tool.NONE
                    updateToolSelectionUI(null)
                } else {
                    currentTool = tool; b.canvas.tool = tool
                    b.strokeSlider.value = b.canvas.inkWidth.coerceIn(1f, 40f)
                    updateToolSelectionUI(btn)
                    btn.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).withEndAction { btn.animate().scaleX(1f).scaleY(1f).start() }.start()
                    btn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    // Update icon color to reflect current ink color
                    updateToolColorUI(if (tool == NoteCanvasView.Tool.HIGHLIGHTER) b.canvas.highlightColor else b.canvas.inkColor)
                }
            }
        }
        setupDynamicColorSlots()
        
        b.strokeSlider.addOnChangeListener { _, value, _ -> b.canvas.inkWidth = value }
        b.strokeSlider.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP -> v.performClick()
            }; false
        }
    }

    private fun setupDynamicColorSlots() {
        val slots = listOf(b.colorSlot1, b.colorSlot2, b.colorSlot3)
        val prefs = requireContext().getSharedPreferences("color_slots", Context.MODE_PRIVATE)

        slots.forEachIndexed { i, slot ->
            var color = prefs.getInt("slot_${i}_color", if (i == 0) Color.BLACK else if (i == 1) Color.RED else Color.BLUE)
            var width = prefs.getFloat("slot_${i}_width", 3f)
            
            updateSwatchDrawable(slot, color)

            slot.setOnClickListener {
                slots.forEach { it.isSelected = false }
                slot.isSelected = true
                b.canvas.setCurrentColor(color)
                b.canvas.inkWidth = width
                b.strokeSlider.value = width.coerceIn(1f, 40f)
                
                // If currently eraser or none, switch to Pen
                if (b.canvas.tool != NoteCanvasView.Tool.PEN && 
                    b.canvas.tool != NoteCanvasView.Tool.HIGHLIGHTER &&
                    b.canvas.tool != NoteCanvasView.Tool.SHAPES) {
                    b.canvas.tool = NoteCanvasView.Tool.PEN
                    updateToolSelectionUI(b.toolPen)
                }
                updateToolColorUI(color)
                slot.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }

            slot.setOnLongClickListener {
                showColorPickerDialog { newColor ->
                    color = newColor
                    width = b.canvas.inkWidth
                    prefs.edit().putInt("slot_${i}_color", color).putFloat("slot_${i}_width", width).apply()
                    updateSwatchDrawable(slot, color)
                    if (slot.isSelected) {
                        b.canvas.setCurrentColor(color)
                        updateToolColorUI(color)
                    }
                }
                true
            }
        }
    }

    private fun updateToolColorUI(color: Int) {
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        when (b.canvas.tool) {
            NoteCanvasView.Tool.PEN -> {
                b.toolPen.colorFilter = filter
                b.toolHighlight.colorFilter = null
                b.toolShapes.colorFilter = null
            }
            NoteCanvasView.Tool.HIGHLIGHTER -> {
                b.toolHighlight.colorFilter = filter
                b.toolPen.colorFilter = null
                b.toolShapes.colorFilter = null
            }
            NoteCanvasView.Tool.SHAPES -> {
                b.toolShapes.colorFilter = filter
                b.toolPen.colorFilter = null
                b.toolHighlight.colorFilter = null
            }
            else -> {
                b.toolPen.colorFilter = null
                b.toolHighlight.colorFilter = null
                b.toolShapes.colorFilter = null
            }
        }
    }

    private fun updateSwatchDrawable(view: View, color: Int) {
        val normal = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); if (color == Color.WHITE) setStroke(2, Color.LTGRAY) }
        val selected = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(6, ContextCompat.getColor(requireContext(), R.color.accent_brown)) }
        view.background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_selected), selected); addState(intArrayOf(), normal) }
    }

    private fun showColorPickerDialog(onColorSelected: (Int) -> Unit) {
        val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        val colorHexes = listOf("#1A1A1A", "#757575", "#FFFFFF", "#5C3D1E", "#8B7355", "#C4A882", "#C0392B", "#D35400", "#F1C40F", "#27AE60", "#16A085", "#2C5F8A", "#8E44AD", "#D81B60", "#E67E22")
        val grid = GridLayout(requireContext()).apply { columnCount = 5 }
        val infoLayout = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 32 } }
        val preview = View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(100, 100).apply { marginEnd = 32 }; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED); setStroke(2, Color.LTGRAY) } }
        val hexInput = EditText(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f); hint = "#RRGGBB"; textSize = 14f; maxLines = 1; inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; filters = arrayOf(android.text.InputFilter.LengthFilter(7)) }
        val rgbLayout = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = 16 } }
        fun createRgbInput(h: String) = EditText(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(80, -2).apply { marginStart = 8 }; hint = h; textSize = 12f; maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_NUMBER; filters = arrayOf(android.text.InputFilter.LengthFilter(3)) }
        val rIn = createRgbInput("R"); val gIn = createRgbInput("G"); val bIn = createRgbInput("B")
        infoLayout.addView(preview); infoLayout.addView(hexInput); infoLayout.addView(rgbLayout); rgbLayout.addView(rIn); rgbLayout.addView(gIn); rgbLayout.addView(bIn)
        val wheel = ColorWheelView(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(-1, 450).apply { topMargin = 32 } }
        var updating = false
        fun update(c: Int, s: View?) {
            if (updating) return; updating = true
            val hex = String.format("#%06X", (0xFFFFFF and c))
            if (s != hexInput) hexInput.setText(hex)
            if (s != rIn) rIn.setText(Color.red(c).toString())
            if (s != gIn) gIn.setText(Color.green(c).toString())
            if (s != bIn) bIn.setText(Color.blue(c).toString())
            if (s != wheel) wheel.setColor(c)
            (preview.background as GradientDrawable).setColor(c); updating = false
        }
        wheel.onColorSelected = { update(it, wheel) }
        hexInput.doAfterTextChanged { if (!updating) try { update(Color.parseColor(it.toString()), hexInput) } catch (e: Exception) {} }
        val l: (android.text.Editable?) -> Unit = { if (!updating) try { update(Color.rgb(rIn.text.toString().toInt(), gIn.text.toString().toInt(), bIn.text.toString().toInt()), rIn) } catch (e: Exception) {} }
        rIn.doAfterTextChanged { l(it) }; gIn.doAfterTextChanged { l(it) }; bIn.doAfterTextChanged { l(it) }
        container.addView(grid); container.addView(infoLayout); container.addView(wheel)
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog).setTitle("Select Color").setView(container).setPositiveButton("Select") { _, _ -> val c = try { Color.parseColor(hexInput.text.toString()) } catch (e: Exception) { Color.BLACK }; onColorSelected(c) }.setNegativeButton("Cancel", null).create()
        colorHexes.forEach { h -> val c = Color.parseColor(h); grid.addView(View(requireContext()).apply { layoutParams = GridLayout.LayoutParams().apply { width = 100; height = 100; setMargins(12, 12, 12, 12) }; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); if (c == Color.WHITE) setStroke(2, Color.LTGRAY) }; setOnClickListener { update(c, null) } }) }
        update(if (b.canvas.tool == NoteCanvasView.Tool.HIGHLIGHTER) b.canvas.highlightColor else b.canvas.inkColor, null)
        dialog.show()
    }

    private fun setupCanvasCallbacks() {
        b.canvas.onElementSelected = { el ->
            b.selectionBar.visibility = if (el != null) View.VISIBLE else View.GONE
            b.selectionBar.animate().alpha(if (el != null) 1f else 0f).setDuration(180).start()
            if (el?.id != editingElementId) finishInlineEdit()
            b.btnLockElement.isVisible = el != null && el.type != ElementType.INK
            b.btnEditElement.isVisible = el != null && (el.type == ElementType.TEXT_BOX || el.type == ElementType.LINK)
            
            // Only show checkmark if in placement mode
            b.btnSelectionCheck.isVisible = isPlacementMode && el != null

            if (el != null) {
                if (el.type == ElementType.INK) { 
                    b.canvas.tool = NoteCanvasView.Tool.LASSO
                    currentTool = NoteCanvasView.Tool.LASSO
                    updateToolSelectionUI(b.toolLasso)
                } else { 
                    b.canvas.tool = NoteCanvasView.Tool.SELECT
                    currentTool = NoteCanvasView.Tool.SELECT
                    updateToolSelectionUI(b.toolSelect)
                }
            } else {
                isPlacementMode = false
            }
        }
        b.canvas.onRequestTextInput = { startInlineEdit(it) }
        b.canvas.onRequestLinkInput = { showLinkInputDialog(it) }
        b.canvas.onCanvasChanged = { 
            saveDebounceHandler.removeCallbacks(saveRunnable)
            saveDebounceHandler.postDelayed(saveRunnable, 1000)
        }
        b.canvas.onTransformChanged = { 
            updateInlineEditorPosition()
            if (b.canvas.format == NoteFormat.PAGES) {
                updatePageNavigationUI()
            }
            updateLassoMenuPosition()
        }
        b.canvas.onLassoSelectionActive = { bounds ->
            if (bounds != null) {
                currentLassoBounds = bounds
                b.lassoMenu.isVisible = true
                b.btnLassoCheck.isVisible = isPlacementMode
                updateLassoMenuPosition()
            } else {
                currentLassoBounds = null
                b.lassoMenu.isVisible = false
                isPlacementMode = false
            }
        }
        b.btnLassoDelete.setOnClickListener { b.canvas.deleteSelected(); b.lassoMenu.isVisible = false }
        b.btnLassoDuplicate.setOnClickListener { 
            isPlacementMode = true
            b.canvas.duplicateSelected()
            snack("Selection duplicated")
            b.btnLassoCheck.isVisible = true
        }
        b.btnLassoCheck.setOnClickListener {
            isPlacementMode = false
            b.canvas.clearSelections()
            b.lassoMenu.isVisible = false
        }
        b.btnSelectionCheck.setOnClickListener {
            isPlacementMode = false
            b.canvas.selectedElementId = null
            b.canvas.invalidate()
            b.selectionBar.isVisible = false
        }
        b.btnLassoColor.setOnClickListener { 
            showColorPickerDialog { newColor ->
                b.canvas.changeSelectedInkColor(newColor)
                snack("Color applied")
            }
        }
        b.btnDeleteElement.setOnClickListener { b.canvas.deleteSelected() }
        b.btnDuplicateElement.setOnClickListener { 
            isPlacementMode = true
            b.canvas.duplicateSelected()
            snack("Element duplicated")
            b.btnSelectionCheck.isVisible = true
        }
        b.btnEditElement.setOnClickListener { val id = b.canvas.selectedElementId ?: return@setOnClickListener; val el = b.canvas.elements.find { it.id == id } ?: return@setOnClickListener; if (el.type == ElementType.TEXT_BOX) startInlineEdit(el) else if (el.type == ElementType.LINK) showLinkInputDialog(el) }
        b.btnLockElement.setOnClickListener { b.canvas.lockSelected(); b.btnLockElement.animate().rotationBy(360f).setDuration(300).start(); saveNow() }
        b.btnDuplicateElement.setOnClickListener { snack("Long-press element to duplicate") }
        b.inlineEditor.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) finishInlineEdit() }
        b.inlineEditor.setOnEditorActionListener { _, _, _ -> finishInlineEdit(); true }
    }

    private fun startInlineEdit(el: NoteElement) {
        if (el.type != ElementType.TEXT_BOX) return
        editingElementId = el.id; b.canvas.ignoreElementId = el.id; b.canvas.invalidate()
        b.inlineEditor.setText(el.textContent ?: ""); b.inlineEditor.visibility = View.VISIBLE; b.inlineEditor.requestFocus(); b.inlineEditor.setTextColor(el.textColor); updateInlineEditorPosition()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(b.inlineEditor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun updateInlineEditorPosition() {
        val id = editingElementId ?: return; val el = b.canvas.elements.find { it.id == id } ?: return
        val s = b.canvas.scale; val px = b.canvas.panX; val py = b.canvas.panY
        b.inlineEditor.setTextSize(TypedValue.COMPLEX_UNIT_PX, el.textSize * s)
        b.inlineEditor.x = (el.x + 4f) * s + px; b.inlineEditor.y = (el.y + 4f) * s + py
        b.inlineEditor.layoutParams.width = (el.width * s).toInt().coerceAtLeast(100); b.inlineEditor.layoutParams.height = (el.height * s).toInt().coerceAtLeast(60); b.inlineEditor.requestLayout()
    }

    private fun updateLassoMenuPosition() {
        val bounds = currentLassoBounds ?: return
        val s = b.canvas.scale; val px = b.canvas.panX; val py = b.canvas.panY
        b.lassoMenu.x = (bounds.centerX() * s + px) - b.lassoMenu.width / 2f
        b.lassoMenu.y = (bounds.top * s + py) - 50f
    }

    private fun finishInlineEdit() {
        val id = editingElementId ?: return; val text = b.inlineEditor.text.toString()
        b.canvas.updateElement(id, textContent = text); editingElementId = null; b.canvas.ignoreElementId = null; b.inlineEditor.visibility = View.GONE
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.inlineEditor.windowToken, 0)
        b.canvas.invalidate(); saveNow()
    }

    private fun setupBottomBar() {
        b.btnInsert.setOnClickListener { showInsertSheet() }
        b.btnStopRecording.setOnClickListener { 
            if (audio.isRecording) stopRecording() 
            else if (lecture.isRecording) stopLectureRecording()
        }
        b.btnPauseRecording.setOnClickListener {
            if (audio.isRecording) toggleMicPause()
            else if (lecture.isRecording) toggleVideoPause()
        }
        b.btnPlayLecture.setOnClickListener { showLecturePicker() }
        b.btnCloseLecture.setOnClickListener { b.lectureVideoView.stopPlayback(); b.lectureCard.visibility = View.GONE; playbackHandler.removeCallbacks(updateProgressAction) }
        b.btnShowTranscript.setOnClickListener { val v = b.transcriptCard.isVisible; b.transcriptCard.isVisible = !v; b.btnShowTranscript.alpha = if (!v) 1f else 0.6f; showTranscriptList() }
        b.btnCloseTranscript.setOnClickListener { b.transcriptCard.isVisible = false; b.btnShowTranscript.alpha = 0.6f }
        
        b.transcriptSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean = true
            override fun onQueryTextChange(q: String?): Boolean {
                (b.transcriptList.adapter as? TranscriptAdapter)?.filter(q ?: "")
                return true
            }
        })
        b.transcriptSearch.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.textSize = 13f

        b.btnAddPage.setOnClickListener { b.canvas.pageCount++; saveNow(); updatePageNavigationUI(); b.canvas.invalidate(); snack("Page added") }
        b.btnDeletePage.setOnClickListener { confirmDeletePage() }
        b.btnBookmarks.setOnClickListener { showBookmarksDialog() }
        b.btnPrevPage.setOnClickListener { b.canvas.goToPage(b.canvas.getCurrentPageIdx() - 1); updatePageNavigationUI() }
        b.btnNextPage.setOnClickListener { b.canvas.goToPage(b.canvas.getCurrentPageIdx() + 1); updatePageNavigationUI() }
        setupPlaybackControls()
    }

    private fun showInsertSheet() {
        val sheet = BottomSheetDialog(requireContext(), R.style.BVNotesFloatingSheet)
        val v = layoutInflater.inflate(R.layout.sheet_insert, null)
        v.findViewById<View>(R.id.optionRecordAudio).setOnClickListener { sheet.dismiss(); checkAudioAndRecord() }
        v.findViewById<View>(R.id.optionRecordVideo).setOnClickListener { sheet.dismiss(); checkLecturePerms() }
        v.findViewById<View>(R.id.optionAddImage).setOnClickListener { sheet.dismiss(); showImageSourceSheet() }
        sheet.setContentView(v)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun setupPlaybackControls() {
        b.lectureVideoView.setOnClickListener { if (b.lectureControls.isVisible) { b.lectureControls.visibility = View.GONE; playbackHandler.removeCallbacks(hideControlsAction) } else { b.lectureControls.visibility = View.VISIBLE; resetControlsTimer() } }
        b.btnLecturePlayPause.setOnClickListener { if (b.lectureVideoView.isPlaying) { b.lectureVideoView.pause(); b.btnLecturePlayPause.setImageResource(R.drawable.ic_play); playbackHandler.removeCallbacks(updateProgressAction) } else { b.lectureVideoView.start(); b.btnLecturePlayPause.setImageResource(R.drawable.ic_stop); playbackHandler.post(updateProgressAction) }; resetControlsTimer() }
        b.btnLectureBack10.setOnClickListener { b.lectureVideoView.seekTo((b.lectureVideoView.currentPosition - 10000).coerceAtLeast(0)); resetControlsTimer() }
        b.btnLectureForward10.setOnClickListener { b.lectureVideoView.seekTo((b.lectureVideoView.currentPosition + 10000).coerceAtMost(b.lectureVideoView.duration)); resetControlsTimer() }
        b.lectureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) { val d = b.lectureVideoView.duration; val t = d * p / 100; b.lectureVideoView.seekTo(t); b.tvLectureTime.text = formatTime(t) } }
            override fun onStartTrackingTouch(s: SeekBar?) { playbackHandler.removeCallbacks(hideControlsAction) }
            override fun onStopTrackingTouch(s: SeekBar?) { resetControlsTimer() }
        })
    }

    private fun resetControlsTimer() { playbackHandler.removeCallbacks(hideControlsAction); playbackHandler.postDelayed(hideControlsAction, 3000) }

    private fun updatePageNavigationUI() {
        val p = b.canvas.format == NoteFormat.PAGES
        b.btnPrevPage.isVisible = p
        b.btnNextPage.isVisible = p
        b.tvPageIndicator.isVisible = p
        b.tvPageIndicator.text = "${b.canvas.getCurrentPageIdx() + 1} / ${b.canvas.pageCount}"
        b.btnRecenter.isVisible = !p
        b.btnDeletePage.isVisible = p && b.canvas.pageCount > 1
    }

    private fun confirmDeletePage() {
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Delete Page")
            .setMessage("Are you sure you want to delete the current page? This will remove all items on this page.")
            .setPositiveButton("Delete") { _, _ ->
                b.canvas.deleteCurrentPage()
                updatePageNavigationUI()
                saveNow()
                snack("Page deleted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private data class Bookmark(val name: String, val page: Int)

    private fun showBookmarksDialog() {
        val n = note ?: return
        val type = object : TypeToken<List<Bookmark>>() {}.type
        val bookmarks: MutableList<Bookmark> = try {
            Gson().fromJson(n.bookmarksJson ?: "[]", type) ?: mutableListOf()
        } catch (e: Exception) { mutableListOf() }

        val options = mutableListOf<String>()
        bookmarks.sortedBy { it.page }.forEach { 
            options.add("Page ${it.page + 1}: ${it.name}")
        }
        options.add("+ Add Bookmark here")

        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Chapters & Bookmarks")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < bookmarks.size) {
                    val sorted = bookmarks.sortedBy { it.page }
                    b.canvas.goToPage(sorted[which].page)
                    updatePageNavigationUI()
                } else {
                    showAddBookmarkDialog(bookmarks)
                }
            }
            .show()
    }

    private fun showAddBookmarkDialog(bookmarks: MutableList<Bookmark>) {
        val input = EditText(requireContext()).apply { hint = "Chapter name (e.g. Intro, Part 1)" }
        val currPage = b.canvas.getCurrentPageIdx()
        
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Add Bookmark to Page ${currPage + 1}")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Page ${currPage + 1}" }
                bookmarks.removeAll { it.page == currPage } // Replace if exists
                bookmarks.add(Bookmark(name, currPage))
                b.canvas.bookmarks = bookmarks.map { NoteCanvasView.Bookmark(it.name, it.page) }
                val json = Gson().toJson(bookmarks)
                note = note?.copy(bookmarksJson = json)
                saveNow()
                snack("Bookmark added")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTemplatePicker() {
        val options = PaperTemplate.values().map { it.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() } }
        
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Paper Template")
            .setItems(options.toTypedArray()) { _, idx -> 
                b.canvas.pdfPath = null
                b.canvas.template = PaperTemplate.values()[idx]
                note?.let { vm.saveNote(it.copy(template = b.canvas.template, pdfTemplatePath = null)) }
                b.canvas.invalidate()
            }.show()
    }

    private fun showExportSheet() {
        val sheet = BottomSheetDialog(requireContext(), R.style.BVNotesFloatingSheet)
        val v = layoutInflater.inflate(R.layout.sheet_export, null)
        v.findViewById<View>(R.id.exportPdf).setOnClickListener { sheet.dismiss(); exportPdf() }
        v.findViewById<View>(R.id.exportText).setOnClickListener { sheet.dismiss(); exportText() }
        v.findViewById<View>(R.id.shareNote).setOnClickListener { sheet.dismiss(); shareNote() }
        sheet.setContentView(v)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun exportPdf() { 
        val n = note ?: return
        if (n.format == NoteFormat.PAGES && n.pageCount > 1) {
            val view = layoutInflater.inflate(R.layout.dialog_page_range, null)
            val fromIn = view.findViewById<EditText>(R.id.fromPage)
            val toIn = view.findViewById<EditText>(R.id.toPage)
            fromIn.setText("1")
            toIn.setText(n.pageCount.toString())
            
            MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
                .setTitle("Export PDF")
                .setMessage("Select page range (1 to ${n.pageCount})")
                .setView(view)
                .setPositiveButton("Export All") { _, _ -> performPdfExport(null) }
                .setNeutralButton("Export Range") { _, _ ->
                    val from = (fromIn.text.toString().toIntOrNull() ?: 1).minus(1).coerceAtLeast(0)
                    val to = (toIn.text.toString().toIntOrNull() ?: n.pageCount).minus(1).coerceIn(0, n.pageCount - 1)
                    performPdfExport(from..to)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            performPdfExport(null)
        }
    }

    private fun performPdfExport(range: IntRange?) {
        val n = note ?: return
        val folderName = vm.folders.value?.find { it.id == n.folderId }?.name ?: "General"
        val f = PdfExporter.export(requireContext(), n, b.canvas.elements, folderName, range)
        val u = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", f)
        startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(u, "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        snack("Exported to PDF") 
    }
    private fun exportText() { val n = note ?: return; val t = b.canvas.elements.filter { it.type == ElementType.TEXT_BOX }.joinToString("\n") { it.textContent ?: "" }; val f = File(requireContext().filesDir, "exports/${n.title}_transcript.txt").apply { parentFile?.mkdirs(); writeText(t) }; val u = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", f); startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(u, "text/plain"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
    private fun shareNote() { val n = note ?: return; val s = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, n.title); putExtra(Intent.EXTRA_TEXT, "BVNotes: ${n.title}\n\n${n.transcript ?: ""}") }; startActivity(Intent.createChooser(s, "Share note via")) }

    private fun showImageSourceSheet() {
        val sheet = BottomSheetDialog(requireContext(), R.style.BVNotesFloatingSheet)
        val v = layoutInflater.inflate(R.layout.sheet_image_source, null)
        v.findViewById<View>(R.id.optionCamera).setOnClickListener { sheet.dismiss(); checkCameraAndOpen() }
        v.findViewById<View>(R.id.optionGallery).setOnClickListener { sheet.dismiss(); openGallery() }
        sheet.setContentView(v)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun checkCameraAndOpen() { if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA) }
    
    private fun openCamera() {
        val folderName = vm.folders.value?.find { it.id == note?.folderId }?.name ?: "General"
        val cleanFolder = folderName.replace(" ", "_").take(15)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(requireContext().filesDir, "images/IMG_${cleanFolder}_$ts.jpg").apply { parentFile?.mkdirs() }
        pendingImageUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", f)
        cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, pendingImageUri))
    }
    
    private fun openGallery() { galleryLauncher.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) }
    private fun addImageFromUri(uri: Uri) { try { b.canvas.addImage(uri.toString()); b.canvas.tool = NoteCanvasView.Tool.SELECT; currentTool = NoteCanvasView.Tool.SELECT; updateToolSelectionUI(b.toolSelect); note?.let { vm.saveNote(it.copy(elementsJson = b.canvas.serialiseElements())) } } catch (e: Exception) { snack("Failed to add image") } }

    private fun updateToolSelectionUI(selectedBtn: View?) { 
        if (_b == null) return
        val bts = listOf(b.toolPen, b.toolHighlight, b.toolShapes, b.toolEraser, b.toolSelect, b.toolLasso, b.toolText, b.toolLink)
        bts.forEach { it.isSelected = false }
        selectedBtn?.isSelected = true 
        
        // Show/Hide color slots and stroke slider based on tool
        val tool = b.canvas.tool
        val showInkControls = tool == NoteCanvasView.Tool.PEN || 
                             tool == NoteCanvasView.Tool.HIGHLIGHTER || 
                             tool == NoteCanvasView.Tool.SHAPES
        
        b.inkControls.isVisible = showInkControls
        if (showInkControls) {
            b.inkControls.alpha = 0f
            b.inkControls.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun checkAudioAndRecord() { if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording() else audioPermission.launch(Manifest.permission.RECORD_AUDIO) }

    private fun startRecording() {
        val noteId = note?.id ?: return
        b.btnInsert.visibility = View.GONE
        b.recordingControls.visibility = View.VISIBLE
        b.btnPauseRecording.setImageResource(R.drawable.ic_pause)
        b.recordingPulse.visibility = View.VISIBLE
        b.recordingPulse.animate().alpha(0.3f).setDuration(600).withEndAction {
            b.recordingPulse.animate().alpha(1f).setDuration(600).start()
        }.start()
        audio.start(noteId) { e -> activity?.runOnUiThread { snack(e) } }
        ampHandler.post(object : Runnable { override fun run() { if (audio.isRecording && !audio.isPaused) { val s = 1f + audio.amplitude() / 32767f * 0.5f; b.btnInsert.animate().scaleX(s).scaleY(s).setDuration(120).start(); ampHandler.postDelayed(this, 160) } else if (audio.isRecording) { ampHandler.postDelayed(this, 160) } } }); snack("Audio recording started") 
    }

    private fun toggleMicPause() {
        if (!audio.isRecording) return
        if (audio.isPaused) {
            audio.resume()
            b.btnPauseRecording.setImageResource(R.drawable.ic_pause)
            b.recordingPulse.visibility = View.VISIBLE
            snack("Recording resumed")
        } else {
            audio.pause()
            b.btnPauseRecording.setImageResource(R.drawable.ic_play)
            b.recordingPulse.visibility = View.GONE
            b.btnInsert.animate().scaleX(1f).scaleY(1f).start()
            snack("Recording paused")
        }
    }

    private fun stopRecording() {
        ampHandler.removeCallbacksAndMessages(null)
        b.recordingControls.visibility = View.GONE
        b.btnInsert.visibility = View.VISIBLE
        b.recordingPulse.animate().alpha(0f).setDuration(200).withEndAction {
            b.recordingPulse.visibility = View.GONE
        }.start()
        
        audio.stop()?.let { (name, uri) ->
            note?.let { n ->
                val updatedNote = n.copy(audioPath = uri.toString())
                vm.saveNote(updatedNote)
                vm.developTranscript(requireContext(), name, uri, updatedNote.id)
            }
        }
    }

    private fun checkLecturePerms() { val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO); val all = perms.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }; if (all) startLectureRecording() else lecturePermissions.launch(perms) }
    private fun startLectureRecording() {
        val noteId = note?.id ?: return
        b.lectureCard.visibility = View.VISIBLE
        b.lectureCard.layoutParams.width = 1
        b.lectureCard.layoutParams.height = 1
        b.lectureCard.requestLayout()
        b.tvLectureStatus.visibility = View.VISIBLE
        b.tvLectureStatus.text = "● RECORDING"
        
        b.btnInsert.visibility = View.GONE
        b.recordingControls.visibility = View.VISIBLE
        b.btnPauseRecording.setImageResource(R.drawable.ic_pause)
        
        // Stop any ongoing playback
        b.lectureVideoView.stopPlayback()

        val holder = b.lectureVideoView.holder
        if (holder.surface.isValid) {
            lecture.startRecording(noteId, holder.surface) { err -> snack(err) }
        } else {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    lecture.startRecording(noteId, h.surface) { err -> snack(err) }
                    holder.removeCallback(this)
                }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hI: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        }
        snack("Lecture recording started")
    }

    private fun toggleVideoPause() {
        if (!lecture.isRecording) return
        if (lecture.isPaused) {
            lecture.resume()
            b.btnPauseRecording.setImageResource(R.drawable.ic_pause)
            b.tvLectureStatus.text = "● RECORDING"
            snack("Lecture recording resumed")
        } else {
            lecture.pause()
            b.btnPauseRecording.setImageResource(R.drawable.ic_play)
            b.tvLectureStatus.text = "● PAUSED"
            snack("Lecture recording paused")
        }
    }
    
    private fun stopLectureRecording() {
        lecture.stopRecording()?.let { (name, uri) ->
            note?.let { n ->
                val updatedNote = n.copy(audioPath = uri.toString())
                vm.saveNote(updatedNote)
                vm.developTranscript(requireContext(), name, uri, updatedNote.id)
            }
        }
        b.btnInsert.visibility = View.VISIBLE
        b.recordingControls.visibility = View.GONE
        b.tvLectureStatus.visibility = View.GONE
        b.lectureCard.visibility = View.GONE
        b.lectureCard.layoutParams.width = (280 * resources.displayMetrics.density).toInt()
        b.lectureCard.layoutParams.height = (200 * resources.displayMetrics.density).toInt()
        b.lectureCard.requestLayout()
    }

    private fun showLecturePicker() {
        val resolver = requireContext().contentResolver
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf("_display_name", "_id")
        
        val noteId = note?.id ?: return
        val notePattern = "ID$noteId"
        val legacyPattern = "NOTE$noteId"
        
        val items = mutableListOf<Triple<String, Uri, Boolean>>() // Name, Uri, IsVideo
        
        // Videos
        resolver.query(videoUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (name.contains(notePattern) || name.contains(legacyPattern)) {
                    items.add(Triple(name, Uri.withAppendedPath(videoUri, c.getLong(1).toString()), true))
                }
            }
        }
        // Audios
        resolver.query(audioUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (name.contains(notePattern) || name.contains(legacyPattern)) {
                    items.add(Triple(name, Uri.withAppendedPath(audioUri, c.getLong(1).toString()), false))
                }
            }
        }
        
        if (items.isEmpty()) { snack("No recordings found for this note"); return }
        
        // Sort chronologically by name timestamp
        val sorted = items.sortedByDescending { it.first }

        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Select Recording")
            .setItems(sorted.map { 
                val type = if (it.third) "🎬" else "🎤"
                "$type ${it.first.substringAfter("ID${noteId}_").substringAfter("NOTE${noteId}_")}"
            }.toTypedArray()) { _, idx -> playRecording(sorted[idx].second, sorted[idx].third) }
            .show()
    }

    private fun playRecording(uri: Uri, isVideo: Boolean) { 
        b.lectureCard.visibility = View.VISIBLE
        b.tvLectureStatus.visibility = View.GONE
        
        val d = resources.displayMetrics.density
        // If audio only, we can make the player shorter/more "bar" like
        val heightDp = if (isVideo) 200 else 80
        b.lectureCard.layoutParams.height = (heightDp * d).toInt()
        b.lectureCard.requestLayout()
        
        b.lectureVideoView.setVideoURI(uri)
        b.lectureVideoView.setOnPreparedListener { 
            b.tvLectureDuration.text = formatTime(b.lectureVideoView.duration)
            b.btnLecturePlayPause.setImageResource(R.drawable.ic_stop)
            b.lectureVideoView.start()
            playbackHandler.post(updateProgressAction)
            resetControlsTimer()
        }
        b.lectureControls.visibility = View.VISIBLE
    }

    private fun showLinkInputDialog(el: NoteElement) { val v = layoutInflater.inflate(R.layout.dialog_link_input, null); val ui = v.findViewById<EditText>(R.id.inputUrl); val li = v.findViewById<EditText>(R.id.inputLabel); ui.setText(el.linkUrl ?: "https://"); li.setText(el.linkLabel ?: ""); MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog).setTitle("Embed Link").setView(v).setPositiveButton("Add") { _, _ -> val u = ui.text.toString().trim(); val l = li.text.toString().trim().ifBlank { u }; b.canvas.updateElement(el.id, linkUrl = u, linkLabel = l); saveNow() }.setNegativeButton("Cancel") { _, _ -> b.canvas.deleteSelected() }.show() }

    private fun showMoreSheet() {
        val sheet = BottomSheetDialog(requireContext(), R.style.BVNotesFloatingSheet)
        val v = layoutInflater.inflate(R.layout.sheet_more_options, null)
        v.findViewById<View>(R.id.optionTags).setOnClickListener { sheet.dismiss(); showTagDialog() }
        v.findViewById<View>(R.id.optionFormat).setOnClickListener { sheet.dismiss(); showFormatDialog() }
        v.findViewById<View>(R.id.optionTemplate).setOnClickListener { sheet.dismiss(); showTemplatePicker() }
        v.findViewById<View>(R.id.optionExport).setOnClickListener { sheet.dismiss(); exportPdf() }
        v.findViewById<View>(R.id.optionMove).setOnClickListener { sheet.dismiss(); showMoveNoteDialog() }
        
        // Lock Center only visible in PAGES mode
        val optionLockCenter = v.findViewById<View>(R.id.optionLockCenter)
        if (b.canvas.format == NoteFormat.CANVAS) {
            optionLockCenter.visibility = View.GONE
        } else {
            optionLockCenter.visibility = View.VISIBLE
        }

        val s = v.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchLockCenter)
        s.isChecked = b.canvas.isCenterLocked; v.findViewById<View>(R.id.optionLockCenter).setOnClickListener { s.isChecked = !s.isChecked; b.canvas.isCenterLocked = s.isChecked; saveNow() }
        
        v.findViewById<View>(R.id.optionDelete).setOnClickListener { sheet.dismiss(); note?.let { vm.deleteNote(it); findNavController().navigateUp() } }
        sheet.setContentView(v)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun showFormatDialog() { val opts = arrayOf("Infinite Canvas", "Pages (A4)"); val curr = if (b.canvas.format == NoteFormat.CANVAS) 0 else 1; MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog).setTitle("Note Format").setSingleChoiceItems(opts, curr) { d, w -> val nf = if (w == 0) NoteFormat.CANVAS else NoteFormat.PAGES; b.canvas.format = nf; b.btnAddPage.isVisible = nf == NoteFormat.PAGES; updatePageNavigationUI(); saveNow(); b.canvas.invalidate(); d.dismiss() }.show() }

    private fun showMoveNoteDialog() {
        val n = note ?: return
        val folders = vm.folders.value ?: return
        val folderNames = folders.map { "${it.iconEmoji} ${it.name}" }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Move to Folder")
            .setItems(folderNames) { _, which ->
                val targetFolder = folders[which]
                vm.moveNote(n, targetFolder.id)
                snack("Moved to ${targetFolder.name}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTagDialog() { val n = note ?: return; val i = EditText(requireContext()).apply { setText(n.tags.joinToString(", ")); setHint("Tag1, Tag2...") }; MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog).setTitle("Edit Tags").setView(i).setPositiveButton("Save") { _, _ -> val ts = i.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }; vm.saveNote(n.copy(tags = ts)) }.setNegativeButton("Cancel", null).show() }

    private fun setupTranscriptionObserver() {
        var wasTranscribing = false
        lifecycleScope.launch {
            vm.isTranscribing.collect { active ->
                if (_b != null) {
                    if (active) {
                        b.developingIndicator.isVisible = true
                        b.tvDevelopingStatus.text = "Developing"
                        b.developingProgress.isVisible = true
                        b.developingCheck.isVisible = false
                        wasTranscribing = true
                    } else if (wasTranscribing) {
                        b.tvDevelopingStatus.text = "Completed"
                        b.developingProgress.isVisible = false
                        b.developingCheck.isVisible = true
                        b.developingIndicator.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).withEndAction {
                            b.developingIndicator.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        }.start()
                        
                        b.developingIndicator.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        
                        kotlinx.coroutines.delay(3000)
                        b.developingIndicator.isVisible = false
                        wasTranscribing = false
                    } else {
                        b.developingIndicator.isVisible = false
                    }
                }
            }
        }
    }

    private fun showTranscriptList() {
        val dir = File(requireContext().filesDir, "transcripts").apply { mkdirs() }
        val noteId = note?.id ?: return
        
        // Match both new (ID#) and old (NOTE#) patterns for robustness
        val files = dir.list()?.filter { it.contains("ID$noteId") || it.contains("NOTE$noteId") } ?: emptyList()
        
        if (files.isEmpty()) { 
            MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
                .setTitle("Transcripts")
                .setMessage("No transcripts found for this note.")
                .setPositiveButton("OK", null).show()
            return 
        }

        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Lecture History")
            .setItems(arrayOf("View All Combined") + files.map { 
                it.replace("TRANSCRIPT_", "")
                  .replace(".txt", "")
                  .replace("LECTURE_ID${noteId}_", "Lecture: ")
                  .replace("REC_ID${noteId}_", "Audio: ")
                  .replace("LECTURE_NOTE${noteId}_", "Lecture: ")
                  .replace("REC_NOTE${noteId}_", "Audio: ") 
                  .replace("_", " ")
            }.toTypedArray()) { _, idx -> 
                if (idx == 0) loadCombinedTranscripts(files.map { File(dir, it) })
                else loadTranscript(File(dir, files[idx - 1]))
            }
            .show()
    }

    private fun loadCombinedTranscripts(files: List<File>) {
        val allSegments = mutableListOf<TranscriptSegment>()
        val tsParser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val dayFormatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)
        val timeFormatter = SimpleDateFormat("h:mm a", Locale.US)
        var lastDay: String? = null

        // Sort files chronologically by the timestamp in their names
        val sortedFiles = files.sortedBy { f ->
            val parts = f.name.substringBeforeLast(".txt").split("_")
            if (parts.size >= 2) parts[parts.size - 2] + "_" + parts.last() else f.name
        }

        sortedFiles.forEach { file ->
            try {
                val json = file.readText()
                val type = object : TypeToken<List<TranscriptSegment>>() {}.type
                val segments: List<TranscriptSegment> = Gson().fromJson(json, type)
                
                // Extract timestamp (yyyyMMdd and HHmmss)
                val parts = file.name.substringBeforeLast(".txt").split("_")
                if (parts.size >= 2) {
                    val fullTs = parts[parts.size - 2] + "_" + parts.last()
                    val date = tsParser.parse(fullTs)
                    if (date != null) {
                        val currentDay = dayFormatter.format(date)
                        val currentTime = timeFormatter.format(date)
                        
                        if (currentDay != lastDay) {
                            lastDay = currentDay
                            // Marker t1 = -1 for Date header
                            allSegments.add(TranscriptSegment(-1, -1, currentDay))
                        } else {
                            // Marker t1 = -2 for Time header
                            allSegments.add(TranscriptSegment(-1, -2, currentTime))
                        }
                    } else {
                        allSegments.add(TranscriptSegment(-1, -1, file.name))
                    }
                } else {
                    allSegments.add(TranscriptSegment(-1, -1, file.name))
                }
                
                allSegments.addAll(segments.map { it.copy(text = "[${file.name}] " + it.text) })
            } catch (e: Exception) {}
        }
        
        b.transcriptCard.isVisible = true
        b.btnShowTranscript.alpha = 1f
        
        b.transcriptList.layoutManager = LinearLayoutManager(requireContext())
        b.transcriptList.adapter = TranscriptAdapter(allSegments) { segment ->
            if (segment.t0 >= 0) {
                val sourceFile = segment.text.substringAfter("[").substringBefore("]")
                val mediaName = sourceFile.replace("TRANSCRIPT_", "").replace(".txt", "")
                findAndPrepMedia(mediaName)
                jumpToTime(segment.t0)
            }
        }
    }

    private fun loadTranscript(file: File) {
        try {
            val json = file.readText()
            val type = object : TypeToken<List<TranscriptSegment>>() {}.type
            val segments: List<TranscriptSegment> = Gson().fromJson(json, type)
            
            b.transcriptCard.isVisible = true
            b.btnShowTranscript.alpha = 1f
            
            val adapter = TranscriptAdapter(segments) { segment ->
                // JUMP TO TIME
                jumpToTime(segment.t0)
            }
            b.transcriptList.layoutManager = LinearLayoutManager(requireContext())
            b.transcriptList.adapter = adapter
            
            // Try to find the associated media and prep the player
            val mediaName = file.name.replace("TRANSCRIPT_", "").replace(".txt", "")
            findAndPrepMedia(mediaName)
            
        } catch (e: Exception) {
            // Fallback for old plain text transcripts
            val text = file.readText()
            val segments = listOf(TranscriptSegment(0, 0, text))
            b.transcriptList.layoutManager = LinearLayoutManager(requireContext())
            b.transcriptList.adapter = TranscriptAdapter(segments) {}
            b.transcriptCard.isVisible = true
        }
    }

    private fun findAndPrepMedia(baseName: String) {
        // Only prep miniplayer for Video Lectures. 
        // Audio recordings (starting with REC_) won't trigger the floating player.
        if (baseName.startsWith("REC_", ignoreCase = true)) return

        val resolver = requireContext().contentResolver
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        
        var foundUri: Uri? = null
        
        // Check Video
        resolver.query(videoUri, arrayOf("_id"), "_display_name LIKE ?", arrayOf("$baseName%"), null)?.use {
            if (it.moveToFirst()) foundUri = Uri.withAppendedPath(videoUri, it.getLong(0).toString())
        }
        
        foundUri?.let { uri ->
            b.lectureCard.visibility = View.VISIBLE
            b.lectureVideoView.setVideoURI(uri)
            b.lectureVideoView.setOnPreparedListener {
                b.tvLectureDuration.text = formatTime(it.duration)
                b.lectureControls.isVisible = true
            }
        }
    }

    private fun jumpToTime(tMillis: Long) {
        // Whisper time is in 1/100th of a second? 
        // No, in whisper.cpp JNI.c: t0 = whisper_full_get_segment_t0(ctx, i);
        // Usually these are in deciseconds (100ms units) or similar.
        // Let's check the toTimestamp in WhisperContext: t * 10 = msec.
        // So t is indeed centiseconds (10ms).
        val targetMs = (tMillis * 10).toInt()
        
        if (b.lectureCard.isVisible) {
            b.lectureVideoView.seekTo(targetMs)
            b.lectureVideoView.start()
            b.btnLecturePlayPause.setImageResource(R.drawable.ic_stop)
            playbackHandler.post(updateProgressAction)
        } else {
            snack("Open the media player to sync with transcript")
        }
    }

    inner class TranscriptAdapter(
        private val originalSegments: List<TranscriptSegment>,
        private val onSegmentClick: (TranscriptSegment) -> Unit
    ) : RecyclerView.Adapter<TranscriptAdapter.VH>() {
        
        private var segments = originalSegments.toList()
        private var query = ""

        fun filter(q: String) {
            query = q.lowercase()
            segments = if (query.isEmpty()) {
                originalSegments
            } else {
                originalSegments.filter { it.text.lowercase().contains(query) || it.t0 < 0 }
            }
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val time: TextView = v.findViewById(R.id.tvSegmentTime)
            val text: TextView = v.findViewById(R.id.tvSegmentText)
            val row: View = v
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_transcript_row, p, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = segments[pos]
            if (s.t0 < 0) {
                // ... same as before ...
                h.time.visibility = View.GONE
                h.text.text = s.text
                if (s.t1 == -1L) {
                    h.text.gravity = Gravity.CENTER
                    h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    h.text.setTypeface(null, Typeface.BOLD)
                    h.text.setTextColor(ContextCompat.getColor(h.row.context, R.color.text_primary))
                    (h.text.layoutParams as LinearLayout.LayoutParams).marginStart = 0
                } else {
                    h.text.gravity = Gravity.START
                    h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    h.text.setTypeface(null, Typeface.NORMAL)
                    h.text.setTextColor(ContextCompat.getColor(h.row.context, R.color.text_tertiary))
                    (h.text.layoutParams as LinearLayout.LayoutParams).marginStart = (64 * h.row.resources.displayMetrics.density).toInt()
                }
                h.row.isClickable = false
            } else {
                h.time.visibility = View.VISIBLE
                h.text.gravity = Gravity.START
                h.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                val msec = s.t0 * 10
                val min = (msec / 60000)
                val sec = (msec % 60000) / 1000
                h.time.text = String.format(Locale.US, "%02d:%02d", min, sec)
                
                val cleanText = if (s.text.startsWith("[")) s.text.substringAfter("] ") else s.text
                
                if (query.isNotEmpty() && cleanText.lowercase().contains(query)) {
                    val start = cleanText.lowercase().indexOf(query)
                    val sb = SpannableStringBuilder(cleanText.trim())
                    sb.setSpan(BackgroundColorSpan(Color.YELLOW), start, start + query.length, 0)
                    h.text.text = sb
                } else {
                    h.text.text = cleanText.trim()
                }

                h.text.setTypeface(null, Typeface.NORMAL)
                h.text.setTextColor(ContextCompat.getColor(h.row.context, R.color.text_primary))
                h.row.setOnClickListener { onSegmentClick(s) }
                (h.text.layoutParams as LinearLayout.LayoutParams).marginStart = (8 * h.row.resources.displayMetrics.density).toInt()
            }
        }
        override fun getItemCount() = segments.size
    }

    private fun saveNow() { 
        val n = note ?: return
        vm.saveNote(n.copy(
            title = b.editorTitle.text?.toString() ?: n.title, 
            pageCount = b.canvas.pageCount, 
            format = b.canvas.format, 
            isCenterLocked = b.canvas.isCenterLocked, 
            elementsJson = b.canvas.serialiseElements() ?: "[]",
            bookmarksJson = n.bookmarksJson
        )) 
    }
    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
    override fun onPause() { super.onPause(); saveNow(); if (audio.isRecording) stopRecording(); if (lecture.isRecording) stopLectureRecording(); b.lectureVideoView.stopPlayback() }
    override fun onDestroyView() { b.canvas.releaseResources(); super.onDestroyView(); _b = null }
}
