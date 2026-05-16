package com.bv.notes.ui.home

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bv.notes.R
import com.bv.notes.data.model.Folder
import com.bv.notes.data.model.Note
import java.text.SimpleDateFormat
import java.util.*

// ── Folder Sidebar ────────────────────────────────────────────────────────────

class FolderSidebarAdapter(
    private val onFolderClick: (Folder) -> Unit,
    private val onFolderLongClick: (Folder) -> Unit
) : ListAdapter<Folder, FolderSidebarAdapter.VH>(FolderDiff) {

    private var selectedId = -1L

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val emoji: TextView = v.findViewById(R.id.folderEmoji)
        val name: TextView = v.findViewById(R.id.folderName)
        val row: View = v.findViewById(R.id.folderRow)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_folder_row, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val f = getItem(pos)
        h.emoji.text = f.iconEmoji
        h.name.text = f.name
        h.row.isSelected = f.id == selectedId
        h.row.setOnClickListener { setSelected(f.id); onFolderClick(f) }
        h.row.setOnLongClickListener { onFolderLongClick(f); true }
    }

    fun setSelected(id: Long) { selectedId = id; notifyDataSetChanged() }

    object FolderDiff : DiffUtil.ItemCallback<Folder>() {
        override fun areItemsTheSame(a: Folder, b: Folder) = a.id == b.id
        override fun areContentsTheSame(a: Folder, b: Folder) = a == b
    }
}

// ── Note Card ─────────────────────────────────────────────────────────────────

class NoteCardAdapter(
    private val onClick: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit
) : ListAdapter<Note, NoteCardAdapter.VH>(NoteDiff) {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: com.google.android.material.card.MaterialCardView = v.findViewById(R.id.noteCard)
        val title: TextView = v.findViewById(R.id.noteTitle)
        val preview: TextView = v.findViewById(R.id.notePreview)
        val date: TextView = v.findViewById(R.id.noteDate)
        val badges: TextView = v.findViewById(R.id.noteBadges)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_note_card, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val note = getItem(pos)
        h.title.text = note.title.ifBlank { "Untitled" }
        h.preview.text = when {
            !note.transcript.isNullOrBlank() -> note.transcript?.trim()?.take(120)
            note.audioPath != null -> "Voice recording attached"
            note.elementsJson == "EXTERNAL" -> "Handwritten notes & sketches"
            (note.elementsJson?.length ?: 0) > 10 -> "Visual notes & sketches"
            else -> "Empty note"
        }
        h.date.text = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(note.updatedAt))
        val badges = buildString {
            note.audioPath?.let {
                if (it.contains("LECTURE")) append("🎬 ") else append("🎤 ")
            }
            // Check for elements in both database field and external marker
            if (note.elementsJson == "EXTERNAL" || note.elementsJson?.contains("IMAGE") == true) append("📷 ")
            if (note.elementsJson == "EXTERNAL" || note.elementsJson?.contains("INK") == true) append("✒️ ")
            if (note.tags.isNotEmpty()) append("🏷️ ${note.tags.first()}")
        }
        h.badges.text = badges.trim()
        h.badges.visibility = if (badges.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        
        h.card.setOnClickListener {
            h.card.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).withEndAction {
                h.card.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                onClick(note)
            }.start()
        }
        h.card.setOnLongClickListener { onLongClick(note); true }

        // Fixed entrance animation (prevents invisible items on scroll)
        h.card.alpha = 1f
        h.card.translationY = 0f
    }

    object NoteDiff : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(a: Note, b: Note) = a.id == b.id
        override fun areContentsTheSame(a: Note, b: Note) = a == b
    }
}
