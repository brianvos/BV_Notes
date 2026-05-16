package com.bv.notes.data.repository

import com.bv.notes.data.db.FolderDao
import com.bv.notes.data.db.NoteDao
import com.bv.notes.data.model.*
import kotlinx.coroutines.flow.Flow

class NoteRepository(private val nd: NoteDao, private val fd: FolderDao) {
    fun allNotes() = nd.all()
    fun notesByFolder(fid: Long) = nd.byFolder(fid)
    fun search(q: String) = nd.search(q)
    suspend fun noteById(id: Long) = nd.byId(id)
    suspend fun noteByAudioPath(path: String) = nd.byAudioPath(path)
    suspend fun insertNote(n: Note) = nd.insert(n)
    suspend fun updateNote(n: Note) = nd.update(n)
    suspend fun deleteNote(n: Note) = nd.delete(n)
    suspend fun deleteNotesByFolder(fid: Long) = nd.deleteByFolder(fid)

    fun allFolders() = fd.all()
    suspend fun insertFolder(f: Folder) = fd.insert(f)
    suspend fun updateFolder(f: Folder) = fd.update(f)
    suspend fun deleteFolder(f: Folder) = fd.delete(f)
}
