package com.bv.notes.ui.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.bv.notes.R
import com.bv.notes.databinding.FragmentFilesBinding
import com.bv.notes.ui.editor.NoteViewModel
import com.bv.notes.ui.editor.NoteViewModelFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FilesFragment : Fragment() {
    private var _b: FragmentFilesBinding? = null
    private val b get() = _b!!
    private val vm: NoteViewModel by activityViewModels { NoteViewModelFactory(requireActivity().application) }

    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<FileItem>()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        loadFiles()
    }

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == AppCompatActivity.RESULT_OK) {
            selectedItems.clear()
            exitSelectionMode()
            loadFiles()
        }
    }

    data class FileItem(val name: String, val lastModified: Long, val size: Long, val uri: Uri, val isInternal: Boolean, val path: String)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentFilesBinding.inflate(i, c, false); return b.root
    }

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)
        b.rvFiles.layoutManager = LinearLayoutManager(requireContext())
        setupClickListeners()
        checkPermissionsAndLoad()
    }

    private fun setupClickListeners() {
        b.btnSelectMode.setOnClickListener {
            if (isSelectionMode) exitSelectionMode() else enterSelectionMode()
        }
        b.btnBatchDelete.setOnClickListener {
            if (selectedItems.isNotEmpty()) {
                confirmBatchDelete()
            } else {
                Toast.makeText(requireContext(), "Select files to delete", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        b.btnSelectMode.text = "Cancel"
        b.batchActionBar.alpha = 0f
        b.batchActionBar.visibility = View.VISIBLE
        b.batchActionBar.animate().alpha(1f).setDuration(200).start()
        updateSelectionUI()
        b.rvFiles.adapter?.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        b.btnSelectMode.text = "Select"
        b.batchActionBar.animate().alpha(0f).setDuration(200).withEndAction {
            b.batchActionBar.visibility = View.GONE
        }.start()
        b.rvFiles.adapter?.notifyDataSetChanged()
    }

    private fun toggleSelection(item: FileItem) {
        if (selectedItems.any { it.path == item.path }) {
            selectedItems.removeAll { it.path == item.path }
        } else {
            selectedItems.add(item)
        }
        updateSelectionUI()
    }

    private fun updateSelectionUI() {
        val count = selectedItems.size
        b.tvSelectionCount.text = "$count selected"
        b.btnBatchDelete.isEnabled = count > 0
        b.btnBatchDelete.alpha = if (count > 0) 1f else 0.5f
    }

    private fun confirmBatchDelete() {
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Delete ${selectedItems.size} files")
            .setMessage("Are you sure you want to delete these items? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> performBatchDelete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performBatchDelete() {
        val internalFiles = selectedItems.filter { it.isInternal }
        val externalUris = selectedItems.filter { !it.isInternal }.map { it.uri }

        // Delete internal files first
        internalFiles.forEach { item ->
            try {
                File(item.path).delete()
            } catch (e: Exception) {}
        }

        if (externalUris.isNotEmpty()) {
            try {
                val resolver = requireContext().contentResolver
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pendingIntent = MediaStore.createDeleteRequest(resolver, externalUris)
                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    var deletedCount = 0
                    externalUris.forEach { uri ->
                        try {
                            if (resolver.delete(uri, null, null) > 0) deletedCount++
                        } catch (e: Exception) {}
                    }
                    Toast.makeText(requireContext(), "Deleted $deletedCount media files", Toast.LENGTH_SHORT).show()
                    selectedItems.clear()
                    exitSelectionMode()
                    loadFiles()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                selectedItems.clear()
                exitSelectionMode()
                loadFiles()
            }
        } else {
            Toast.makeText(requireContext(), "Deleted ${internalFiles.size} internal files", Toast.LENGTH_SHORT).show()
            selectedItems.clear()
            exitSelectionMode()
            loadFiles()
        }
    }

    private fun checkPermissionsAndLoad() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) loadFiles() else permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun loadFiles() {
        val allFiles = mutableListOf<FileItem>()
        
        // 1. Scan Internal Storage (App Private)
        val internalDirs = listOf("exports", "audio", "images", "transcripts")
        internalDirs.forEach { dirName ->
            val dir = File(requireContext().filesDir, dirName)
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                try {
                    val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
                    allFiles.add(FileItem(file.name, file.lastModified(), file.length(), uri, true, file.absolutePath))
                } catch (e: Exception) {}
            }
        }

        // 2. Scan MediaStore (Public BVNotes Folders)
        scanMediaStore(allFiles)

        val sortedFiles = allFiles.sortedByDescending { it.lastModified }
        b.emptyFiles.visibility = if (sortedFiles.isEmpty()) View.VISIBLE else View.GONE
        b.rvFiles.adapter = FilesAdapter(
            files = sortedFiles, 
            isSelectionMode = { isSelectionMode },
            isSelected = { sel -> selectedItems.any { it.path == sel.path } },
            onClick = { item -> 
                if (isSelectionMode) {
                    toggleSelection(item)
                    b.rvFiles.adapter?.notifyItemChanged(sortedFiles.indexOf(item))
                } else {
                    openFile(item)
                }
            },
            onAction = { item -> showFileOptions(item) }
        )
    }

    private fun showFileOptions(item: FileItem) {
        val isMedia = item.name.endsWith(".m4a", true) || 
                      item.name.endsWith(".mp3", true) || 
                      item.name.endsWith(".mp4", true)
        
        val options = if (isMedia) arrayOf("Develop Transcript", "Delete") else arrayOf("Delete")
        
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle(item.name)
            .setItems(options) { _, idx ->
                when (options[idx]) {
                    "Develop Transcript" -> vm.developTranscript(requireContext(), item.name, item.uri)
                    "Delete" -> confirmDelete(item)
                }
            }
            .show()
    }

    private fun scanMediaStore(list: MutableList<FileItem>) {
        val resolver = requireContext().contentResolver
        
        // Images
        val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imageProjection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE, MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        val imageSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" else "${MediaStore.Images.Media.DATA} LIKE ?"
        val imageArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf("Pictures/BVNotes/%") else arrayOf("%/Pictures/BVNotes/%")
        
        resolver.query(imageUri, imageProjection, imageSelection, imageArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val date = cursor.getLong(1) * 1000
                val size = cursor.getLong(2)
                val id = cursor.getLong(3)
                val path = cursor.getString(4)
                val uri = Uri.withAppendedPath(imageUri, id.toString())
                list.add(FileItem(name, date, size, uri, false, path))
            }
        }

        // Audio
        val audioUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val audioProjection = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
        val audioSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" else "${MediaStore.Audio.Media.DATA} LIKE ?"
        val audioArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf("Music/BVNotes/%") else arrayOf("%/Music/BVNotes/%")
        
        resolver.query(audioUri, audioProjection, audioSelection, audioArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val date = cursor.getLong(1) * 1000
                val size = cursor.getLong(2)
                val id = cursor.getLong(3)
                val path = cursor.getString(4)
                val uri = Uri.withAppendedPath(audioUri, id.toString())
                list.add(FileItem(name, date, size, uri, false, path))
            }
        }

        // Video
        val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videoProjection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_MODIFIED, MediaStore.Video.Media.SIZE, MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA)
        val videoSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" else "${MediaStore.Video.Media.DATA} LIKE ?"
        val videoArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf("Movies/BVNotes/%") else arrayOf("%/Movies/BVNotes/%")
        
        resolver.query(videoUri, videoProjection, videoSelection, videoArgs, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val date = cursor.getLong(1) * 1000
                val size = cursor.getLong(2)
                val id = cursor.getLong(3)
                val path = cursor.getString(4)
                val uri = Uri.withAppendedPath(videoUri, id.toString())
                list.add(FileItem(name, date, size, uri, false, path))
            }
        }

        // Downloads (PDFs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val downloadUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val downloadProjection = arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED, MediaStore.Downloads.SIZE, MediaStore.Downloads._ID, MediaStore.Downloads.DATA)
            resolver.query(downloadUri, downloadProjection, "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("Download/BVNotes/%"), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val date = cursor.getLong(1) * 1000
                    val size = cursor.getLong(2)
                    val id = cursor.getLong(3)
                    val path = cursor.getString(4)
                    val uri = Uri.withAppendedPath(downloadUri, id.toString())
                    list.add(FileItem(name, date, size, uri, false, path))
                }
            }
        }
    }

    private fun openFile(item: FileItem) {
        val mime = when {
            item.name.endsWith(".pdf", true) -> "application/pdf"
            item.name.endsWith(".m4a", true) || item.name.endsWith(".mp3", true) -> "audio/*"
            item.name.endsWith(".jpg", true) || item.name.endsWith(".png", true) -> "image/*"
            item.name.endsWith(".mp4", true) -> "video/*"
            item.name.endsWith(".txt", true) -> "text/plain"
            else -> "*/*"
        }
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun confirmDelete(item: FileItem) {
        MaterialAlertDialogBuilder(requireContext(), R.style.BVNotesDialog)
            .setTitle("Delete File")
            .setMessage("Are you sure you want to delete '${item.name}'? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteFile(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFile(item: FileItem) {
        if (item.isInternal) {
            val file = File(item.path)
            if (file.delete()) {
                loadFiles()
            } else {
                Toast.makeText(requireContext(), "Failed to delete internal file", Toast.LENGTH_SHORT).show()
            }
        } else {
            try {
                val resolver = requireContext().contentResolver
                // Primary attempt: standard delete (often works for app-owned files)
                val deleted = resolver.delete(item.uri, null, null)
                if (deleted > 0) {
                    loadFiles()
                    return
                }
                
                // Fallback for modern Android if standard delete doesn't return > 0 or throws
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val pendingIntent = MediaStore.createDeleteRequest(resolver, listOf(item.uri))
                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                    deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Final fallback to request-based deletion
                    val pendingIntent = MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(item.uri))
                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } else {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

class FilesAdapter(
    private val files: List<FilesFragment.FileItem>,
    private val isSelectionMode: () -> Boolean,
    private val isSelected: (FilesFragment.FileItem) -> Boolean,
    private val onClick: (FilesFragment.FileItem) -> Unit,
    private val onAction: (FilesFragment.FileItem) -> Unit
) : RecyclerView.Adapter<FilesAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: android.widget.TextView = v.findViewById(R.id.fileName)
        val meta: android.widget.TextView = v.findViewById(R.id.fileMeta)
        val icon: android.widget.TextView = v.findViewById(R.id.fileIcon)
        val action: android.widget.ImageButton = v.findViewById(R.id.btnFileAction)
        val checkBox: android.widget.CheckBox = v.findViewById(R.id.fileCheckBox)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_file_row, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val f = files[pos]
        h.name.text = f.name
        h.meta.text = "${SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(f.lastModified))} · ${f.size / 1024}KB"
        h.icon.text = when {
            f.name.endsWith(".pdf", true) -> "📄"
            f.name.endsWith(".m4a", true) || f.name.endsWith(".mp3", true) -> "🎤"
            f.name.endsWith(".jpg", true) || f.name.endsWith(".png", true) -> "🖼"
            f.name.endsWith(".mp4", true) -> "🎬"
            f.name.endsWith(".txt", true) -> "📝"
            else -> "📁"
        }

        val inSelection = isSelectionMode()
        h.checkBox.visibility = if (inSelection) View.VISIBLE else View.GONE
        h.checkBox.isChecked = isSelected(f)
        h.action.visibility = if (inSelection) View.GONE else View.VISIBLE
        
        h.itemView.isSelected = inSelection && isSelected(f)
        h.itemView.setOnClickListener { onClick(f) }
        h.action.setOnClickListener { onAction(f) }
    }

    override fun getItemCount() = files.size
}
