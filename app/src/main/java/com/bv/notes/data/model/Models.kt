package com.bv.notes.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.bv.notes.data.db.Converters

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class PaperTemplate {
    BLANK, LINED, GRID, DOTTED, CORNELL, MUSIC_STAFF
}

enum class NoteFormat {
    CANVAS, PAGES
}

enum class ElementType {
    INK, IMAGE, TEXT_BOX, LINK
}

// ── Note Element (canvas object) ─────────────────────────────────────────────
// Each element on the canvas is serialised to JSON and stored in the note.

data class NoteElement(
    val id: String,
    val type: ElementType,
    // Position & size (as fraction of canvas width/height for resolution independence)
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f,
    var rotation: Float = 0f,
    var isLocked: Boolean = false,
    // Type-specific payload
    val inkJson: String? = null,          // serialised strokes
    val imagePath: String? = null,        // path to image file
    val textContent: String? = null,      // text box content
    val linkUrl: String? = null,          // embedded URL
    val linkLabel: String? = null,        // display label for link
    val inkColor: Int = 0xFF1A1A1A.toInt(),
    val inkWidth: Float = 3f,
    val textSize: Float = 16f,
    val textColor: Int = 0xFF1A1A1A.toInt()
)

// ── Note ─────────────────────────────────────────────────────────────────────

@Entity(tableName = "notes")
@TypeConverters(Converters::class)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val folderId: Long = 1L,
    val template: PaperTemplate = PaperTemplate.LINED,
    val format: NoteFormat = NoteFormat.PAGES,
    val isCenterLocked: Boolean = true,
    val pageCount: Int = 1,
    val tags: List<String> = emptyList(),
    // All canvas elements serialised as JSON array string
    val elementsJson: String? = "[]",
    // Audio
    val audioPath: String? = null,
    val transcript: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Export & Templates
    val exportedPdfPath: String? = null,
    val pdfTemplatePath: String? = null,
    val bookmarksJson: String? = "[]"
)

// ── Folder ───────────────────────────────────────────────────────────────────

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val iconEmoji: String = "📁",
    val colorHex: String = "#8B7355",
    val createdAt: Long = System.currentTimeMillis()
)
