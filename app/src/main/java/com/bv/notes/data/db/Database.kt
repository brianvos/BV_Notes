package com.bv.notes.data.db

import android.content.Context
import androidx.room.*
import com.bv.notes.data.model.*
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun fromList(v: List<String>) = v.joinToString("||")
    @TypeConverter fun toList(v: String) = if (v.isBlank()) emptyList() else v.split("||")
    @TypeConverter fun fromTemplate(v: PaperTemplate) = v.name
    @TypeConverter fun toTemplate(v: String?) = if (v == null) PaperTemplate.LINED else try { PaperTemplate.valueOf(v) } catch (e: Exception) { PaperTemplate.LINED }
    @TypeConverter fun fromFormat(v: NoteFormat) = v.name
    @TypeConverter fun toFormat(v: String?) = if (v == null) NoteFormat.PAGES else try { NoteFormat.valueOf(v) } catch (e: Exception) { NoteFormat.PAGES }
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun all(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE folderId=:fid ORDER BY updatedAt DESC")
    fun byFolder(fid: Long): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE id=:id")
    suspend fun byId(id: Long): Note?

    @Query("SELECT * FROM notes WHERE audioPath = :path LIMIT 1")
    suspend fun byAudioPath(path: String): Note?

    @Query("SELECT * FROM notes WHERE (title LIKE '%'||:q||'%' OR transcript LIKE '%'||:q||'%' OR tags LIKE '%'||:q||'%') ORDER BY updatedAt DESC")
    fun search(q: String): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(n: Note): Long

    @Update
    suspend fun update(n: Note)

    @Delete
    suspend fun delete(n: Note)

    @Query("DELETE FROM notes WHERE folderId = :fid")
    suspend fun deleteByFolder(fid: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY createdAt ASC")
    fun all(): Flow<List<Folder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: Folder): Long

    @Update
    suspend fun update(f: Folder)

    @Delete
    suspend fun delete(f: Folder)
}

@Database(entities = [Note::class, Folder::class], version = 10, exportSchema = false)
@TypeConverters(Converters::class)
abstract class NNDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile private var INST: NNDatabase? = null
        fun get(ctx: Context) = INST ?: synchronized(this) {
            Room.databaseBuilder(ctx.applicationContext, NNDatabase::class.java, "nn_db")
                .fallbackToDestructiveMigration()
                .build().also { INST = it }
        }
    }
}
