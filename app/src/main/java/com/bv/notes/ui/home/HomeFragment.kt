package com.bv.notes.ui.home

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.view.animation.*
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.result.contract.ActivityResultContracts
import com.bv.notes.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.bv.notes.data.model.Folder
import com.bv.notes.data.model.Note
import com.bv.notes.databinding.FragmentHomeBinding
import com.bv.notes.ui.editor.NoteViewModel
import com.bv.notes.ui.editor.NoteViewModelFactory
import com.whispercpp.whisper.TranscriptSegment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.*
import android.view.Gravity
import android.app.Dialog
import android.os.ParcelFileDescriptor
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private val vm: NoteViewModel by activityViewModels { NoteViewModelFactory(requireActivity().application) }
    private var currentFolderId: Long? = null

    private val pdfImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importPdfAsNote(it) }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        
        // Tablet check: collapse sidebar by default on smaller screens
        val isTablet = resources.configuration.smallestScreenWidthDp >= 600
        if (!isTablet) {
            b.sidebarContainer.visibility = View.GONE
        }

        setupFolderList()
        setupNoteGrid()
        setupSearch()
        setupClickListeners()
        setupTranscriptionObserver()
        
        // Initial load
        if (currentFolderId == null) selectFolder(null)
    }

    private fun setupTranscriptionObserver() {
        var wasTranscribing = false
        lifecycleScope.launch {
            vm.isTranscribing.collectLatest { active ->
                if (_b != null) {
                    if (active) {
                        b.developingIndicator.isVisible = true
                        b.tvDevelopingStatus.text = "Developing"
                        b.developingProgress.isVisible = true
                        b.developingCheck.isVisible = false
                        wasTranscribing = true
                    } else if (wasTranscribing) {
                        // Just finished
                        b.tvDevelopingStatus.text = "Completed"
                        b.developingProgress.isVisible = false
                        b.developingCheck.isVisible = true
                        b.developingIndicator.animate().scaleX(1.1f).scaleY(1.1f).setDuration(200).withEndAction {
                            b.developingIndicator.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        }.start()
                        
                        b.developingIndicator.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        
                        // Hide after 3 seconds
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

    private fun setupSearch() {
        b.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                vm.setSearchQuery(q ?: "")
                return true
            }
            override fun onQueryTextChange(q: String?): Boolean {
                vm.setSearchQuery(q ?: "")
                return true
            }
        })
    }

    private fun setupFolderList() {
        val adapter = FolderSidebarAdapter(
            onFolderClick = { selectFolder(it) },
            onFolderLongClick = { showFolderOptions(it) }
        )
        b.rvFolders.layoutManager = LinearLayoutManager(requireContext())
        b.rvFolders.adapter = adapter
        vm.folders.observe(viewLifecycleOwner) { 
            adapter.submitList(it)
            adapter.setSelected(currentFolderId ?: -1L)
        }
    }

    private fun showFolderOptions(folder: Folder) {
        val options = arrayOf("Rename", "Delete")
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle(folder.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> confirmDeleteFolder(folder)
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(folder: Folder) {
        val input = TextInputEditText(requireContext()).apply { 
            setText(folder.name)
            setHint("Folder name") 
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Rename Folder")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != folder.name) {
                    vm.renameFolder(folder, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteFolder(folder: Folder) {
        if (folder.id == 1L) {
            Snackbar.make(b.root, "The default folder cannot be deleted", Snackbar.LENGTH_SHORT).show()
            return
        }
        
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Delete Folder")
            .setMessage("Are you sure you want to delete '${folder.name}'? All notes inside will also be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                if (currentFolderId == folder.id) selectFolder(null)
                vm.deleteFolder(folder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupNoteGrid() {
        val adapter = NoteCardAdapter(
            onClick = { findNavController().navigate(R.id.action_home_to_editor, bundleOf("noteId" to it.id)) },
            onLongClick = { showNoteOptions(it) }
        )
        b.rvNotes.layoutManager = StaggeredGridLayoutManager(2, 1)
        b.rvNotes.adapter = adapter
        vm.notes.observe(viewLifecycleOwner) { all ->
            adapter.submitList(all)
            b.emptyState.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showNoteOptions(note: Note) {
        val options = arrayOf("Rename", "View Transcript", "Move", "Delete")
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle(note.title.ifBlank { "Untitled Note" })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameNoteDialog(note)
                    1 -> showTranscriptViewer(note)
                    2 -> showMoveNoteDialog(note)
                    3 -> confirmDeleteNote(note)
                }
            }
            .show()
    }

    private fun showMoveNoteDialog(note: Note) {
        val folders = vm.folders.value ?: return
        val folderNames = folders.map { "${it.iconEmoji} ${it.name}" }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Move to Folder")
            .setItems(folderNames) { _, which ->
                val targetFolder = folders[which]
                vm.moveNote(note, targetFolder.id)
                Snackbar.make(b.root, "Moved to ${targetFolder.name}", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameNoteDialog(note: Note) {
        val input = TextInputEditText(requireContext()).apply {
            setText(note.title)
            setHint("Note title")
        }
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Rename Note")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    vm.saveNote(note.copy(title = newTitle))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTranscriptViewer(note: Note) {
        val dir = File(requireContext().filesDir, "transcripts").apply { mkdirs() }
        val noteId = note.id
        val files = dir.list()?.filter { it.contains("ID$noteId") || it.contains("NOTE$noteId") }?.sorted() ?: emptyList()
        
        if (files.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
                .setTitle("View Transcript")
                .setMessage("No developed transcripts found for this note.")
                .setPositiveButton("Close", null).show()
            return
        }

        val sb = SpannableStringBuilder()
        val tsParser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val dayFormatter = SimpleDateFormat("MM/dd/yyyy", Locale.US)
        val timeFormatter = SimpleDateFormat("h:mm a", Locale.US)
        var lastDay: String? = null

        files.forEach { fileName ->
            try {
                val file = File(dir, fileName)
                val json = file.readText()
                val type = object : TypeToken<List<TranscriptSegment>>() {}.type
                val segments: List<TranscriptSegment> = Gson().fromJson(json, type)

                // Header logic
                val parts = fileName.substringBeforeLast(".txt").split("_")
                if (parts.size >= 2) {
                    val fullTs = parts[parts.size - 2] + "_" + parts.last()
                    val date = tsParser.parse(fullTs)
                    if (date != null) {
                        val currentDay = dayFormatter.format(date)
                        val currentTime = timeFormatter.format(date)
                        
                        if (currentDay != lastDay) {
                            lastDay = currentDay
                            val dayHeader = "\n\n$currentDay\n\n"
                            val start = sb.length
                            sb.append(dayHeader)
                            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
                            sb.setSpan(RelativeSizeSpan(1.2f), start, sb.length, 0)
                            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, 0)
                        } else {
                            val timeHeader = "\n$currentTime\n"
                            val start = sb.length
                            sb.append(timeHeader)
                            sb.setSpan(ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.text_tertiary)), start, sb.length, 0)
                            sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, 0)
                        }
                    }
                }

                segments.forEach { seg ->
                    val msec = seg.t0 * 10
                    val timePrefix = String.format(Locale.US, "[%02d:%02d] ", (msec/60000), (msec%60000)/1000)
                    val start = sb.length
                    sb.append(timePrefix)
                    sb.setSpan(ForegroundColorSpan(ContextCompat.getColor(requireContext(), R.color.accent_brown)), start, sb.length, 0)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, 0)
                    sb.setSpan(RelativeSizeSpan(0.8f), start, sb.length, 0)
                    
                    sb.append(seg.text.trim() + "\n")
                }
            } catch (e: Exception) {}
        }

        // Fullscreen Dialog setup
        val dialog = Dialog(requireContext(), R.style.Theme_BVNotes_FullscreenDialog)
        dialog.setContentView(R.layout.dialog_fullscreen_transcript)
        
        dialog.findViewById<TextView>(R.id.tvTitle).text = note.title.ifBlank { "Transcript" }
        dialog.findViewById<TextView>(R.id.tvTranscriptContent).text = sb
        dialog.findViewById<View>(R.id.btnBack).setOnClickListener { dialog.dismiss() }
        
        dialog.show()
    }

    private fun confirmDeleteNote(note: Note) {
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Delete Note")
            .setMessage("Are you sure you want to delete '${note.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                vm.deleteNote(note)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectFolder(f: Folder?) {
        currentFolderId = f?.id
        b.tvCurrentFolder.text = f?.name ?: "All Notes"
        vm.setActiveFolder(currentFolderId ?: -1L)
        (b.rvFolders.adapter as? FolderSidebarAdapter)?.setSelected(currentFolderId ?: -1L)
        b.btnAllNotes.alpha = if (f == null) 1f else 0.6f
    }

    private fun showNewFolderDialog() {
        val input = TextInputEditText(requireContext()).apply { hint = "Folder name" }
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog).setTitle("New Folder").setView(input).setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) vm.createFolder(name)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun setupClickListeners() {
        b.fabNewNote.setOnClickListener {
            vm.createNote(currentFolderId ?: 1L).observe(viewLifecycleOwner) { noteId ->
                if (noteId != null) {
                    findNavController().navigate(R.id.action_home_to_editor, bundleOf("noteId" to noteId))
                }
            }
        }
        b.fabNewNote.setOnLongClickListener {
            val options = arrayOf("New Note", "Import PDF as Note")
            MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
                .setItems(options) { _, which ->
                    if (which == 0) b.fabNewNote.performClick()
                    else pdfImportLauncher.launch("application/pdf")
                }.show()
            true
        }
        b.btnNewFolder.setOnClickListener { showNewFolderDialog() }
        b.btnFilesAccess.setOnClickListener { findNavController().navigate(R.id.action_home_to_files) }
        b.btnSettingsAccess.setOnClickListener { findNavController().navigate(R.id.action_home_to_settings) }
        b.btnAllNotes.setOnClickListener { selectFolder(null) }
        b.btnToggleSidebar.setOnClickListener {
            if (b.sidebarContainer.isGone) {
                b.sidebarContainer.visibility = View.VISIBLE
                b.sidebarContainer.translationX = -b.sidebarContainer.width.toFloat()
                b.sidebarContainer.animate().translationX(0f).setDuration(200).start()
            } else {
                b.sidebarContainer.animate().translationX(-b.sidebarContainer.width.toFloat())
                    .setDuration(200)
                    .withEndAction { b.sidebarContainer.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun importPdfAsNote(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(requireContext().filesDir, "templates/PDF_${System.currentTimeMillis()}.pdf")
                file.parentFile?.mkdirs()
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                
                // Get PDF page count to set the initial page count
                var pgCount = 1
                try {
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = android.graphics.pdf.PdfRenderer(fd)
                    pgCount = renderer.pageCount
                    renderer.close()
                    fd.close()
                } catch (e: Exception) {}

                withContext(Dispatchers.Main) {
                    val noteName = uri.path?.substringAfterLast("/")?.substringBeforeLast(".") ?: "Imported PDF"
                    vm.createNote(currentFolderId ?: 1L, noteName, file.absolutePath, pgCount).observe(viewLifecycleOwner) { noteId ->
                        if (noteId != null) {
                            findNavController().navigate(R.id.action_home_to_editor, bundleOf("noteId" to noteId))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Snackbar.make(b.root, "Failed to import PDF", Snackbar.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
