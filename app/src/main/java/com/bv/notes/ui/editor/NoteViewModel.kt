package com.bv.notes.ui.editor

import android.app.Application
import androidx.lifecycle.*
import com.bv.notes.data.db.NNDatabase
import com.bv.notes.data.model.*
import com.bv.notes.data.repository.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.google.gson.Gson
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class NoteViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NNDatabase.get(app)
    private val repo = NoteRepository(db.noteDao(), db.folderDao())

    val folders: LiveData<List<Folder>> = repo.allFolders().asLiveData()

    private val _activeFolderId = MutableStateFlow(-1L)
    val activeFolderId: StateFlow<Long> = _activeFolderId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val notes: LiveData<List<Note>> = combine(_activeFolderId, _searchQuery) { fid, q ->
        Pair(fid, q)
    }.flatMapLatest { (fid, q) ->
        repo.allNotes().map { list ->
            val df = java.text.SimpleDateFormat("MMMM MMM dd yyyy", java.util.Locale.US)
            list.filter { note ->
                val folderMatch = fid == -1L || note.folderId == fid
                
                val query = q.lowercase()
                val dateStr = df.format(java.util.Date(note.updatedAt)).lowercase()
                
                val queryMatch = query.isBlank() || 
                    note.title.lowercase().contains(query) || 
                    note.tags.any { it.lowercase().contains(query) } ||
                    dateStr.contains(query)
                
                folderMatch && queryMatch
            }
        }
    }.asLiveData()

    private val _currentNote = MutableLiveData<Note?>()
    val currentNote: LiveData<Note?> = _currentNote

    // Background Transcription State
    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _transcriptionTask = MutableStateFlow<String?>(null)
    val transcriptionTask: StateFlow<String?> = _transcriptionTask.asStateFlow()

    init {
        viewModelScope.launch {
            val folders = repo.allFolders().first()
            if (folders.isEmpty()) {
                createFolder("General", "📓", "#7A5C3C")
            }
        }
    }

    fun setActiveFolder(id: Long) { _activeFolderId.value = id }
    
    fun setSearchQuery(q: String) { 
        _searchQuery.value = q
    }

    fun loadNote(id: Long) = viewModelScope.launch {
        _currentNote.value = null
        val note = repo.noteById(id)
        if (note != null) {
            val contentFile = getNoteContentFile(id)
            if (contentFile.exists()) {
                try {
                    val fileContent = contentFile.readText()
                    _currentNote.value = note.copy(elementsJson = fileContent)
                } catch (e: Exception) {
                    _currentNote.value = note
                }
            } else {
                _currentNote.value = note
            }
        }
    }

    fun createNote(folderId: Long = 1L, title: String = "", pdfPath: String? = null, pageCount: Int = 1): LiveData<Long> {
        val r = MutableLiveData<Long>()
        viewModelScope.launch { 
            _currentNote.postValue(null)
            val newNote = Note(
                folderId = folderId,
                title = title,
                pdfTemplatePath = pdfPath,
                pageCount = pageCount,
                format = NoteFormat.PAGES
            )
            r.postValue(repo.insertNote(newNote))
        }
        return r
    }

    private fun getNoteContentFile(id: Long): File {
        val dir = File(getApplication<Application>().filesDir, "note_contents").apply { mkdirs() }
        return File(dir, "note_$id.json")
    }

    fun saveNote(note: Note) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val elements = note.elementsJson ?: "[]"
        
        // Step 1: Handle ID assignment for new notes
        var targetNote = note
        if (note.id == 0L) {
            val id = repo.insertNote(note.copy(elementsJson = "[]")) // Save metadata first
            targetNote = note.copy(id = id)
        }

        // Step 2: Save the heavy content to a file to avoid SQLite CursorWindow limits
        try {
            getNoteContentFile(targetNote.id).writeText(elements)
        } catch (e: Exception) {
            android.util.Log.e("NoteViewModel", "Failed to save large content to file", e)
        }

        // Step 3: Update metadata in DB (keep elementsJson empty in DB to save space and prevent crashes)
        val metadataOnly = targetNote.copy(
            updatedAt = System.currentTimeMillis(),
            elementsJson = "EXTERNAL" // Marker that data is in a file
        )
        repo.updateNote(metadataOnly)
        
        // Post back to UI with full data so the canvas doesn't clear
        _currentNote.postValue(targetNote.copy(updatedAt = metadataOnly.updatedAt))
    }

    fun deleteNote(note: Note) = viewModelScope.launch { 
        repo.deleteNote(note)
        try {
            getNoteContentFile(note.id).delete()
        } catch (e: Exception) {}
        _currentNote.value = null 
    }

    fun moveNote(note: Note, targetFolderId: Long) = viewModelScope.launch {
        repo.updateNote(note.copy(folderId = targetFolderId, updatedAt = System.currentTimeMillis()))
    }

    fun createFolder(name: String, emoji: String = "📁", color: String = "#8B7355") = viewModelScope.launch {
        val id = repo.insertFolder(Folder(name = name, iconEmoji = emoji, colorHex = color))
        // Seed with "All Notes" on first run if needed
        if (repo.allFolders().first().size == 1) {
            repo.insertNote(Note(folderId = id, title = "Welcome to BV Notes"))
        }
    }

    fun deleteFolder(folder: Folder) = viewModelScope.launch { 
        repo.deleteNotesByFolder(folder.id)
        repo.deleteFolder(folder) 
    }

    fun renameFolder(folder: Folder, newName: String) = viewModelScope.launch {
        repo.updateFolder(folder.copy(name = newName))
    }

    fun developTranscript(context: android.content.Context, fileName: String, uri: android.net.Uri, noteId: Long? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isTranscribing.value = true
            _transcriptionTask.value = fileName
            
            try {
                val samples = com.bv.notes.util.AudioUtils.getSamplesFromUri(context, uri)
                if (samples != null) {
                    val whisper = com.whispercpp.whisper.WhisperContext.createContextFromAsset(context.assets, "whisper-tiny.bin")
                    val segments = whisper.transcribeData(samples)
                    
                    val name = "TRANSCRIPT_" + fileName.replace(".mp4", "").replace(".m4a", "") + ".txt"
                    val dir = File(context.filesDir, "transcripts").apply { mkdirs() }
                    
                    val json = Gson().toJson(segments)
                    File(dir, name).writeText(json)

                    // NEW: Update the note in the database so it shows up in previews
                    val fullText = segments.joinToString(" ") { it.text }
                    
                    // Try to find the target note
                    val targetNote = when {
                        noteId != null -> repo.noteById(noteId)
                        else -> repo.noteByAudioPath(uri.toString())
                    }

                    targetNote?.let { n ->
                        val dateHeader = "\n\n--- ${java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.US).format(java.util.Date())} ---\n"
                        val updatedTranscript = (n.transcript ?: "") + dateHeader + fullText
                        
                        // We store the latest path in audioPath for quick access, but transcript is chained
                        repo.updateNote(n.copy(transcript = updatedTranscript, audioPath = uri.toString()))
                    }
                    
                    whisper.release()
                }
            } catch (e: Exception) {
                android.util.Log.e("NoteViewModel", "Transcription failed", e)
            } finally {
                _isTranscribing.value = false
                _transcriptionTask.value = null
            }
        }
    }
}

class NoteViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(cls: Class<T>) = NoteViewModel(app) as T
}
