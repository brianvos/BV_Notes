package com.bv.notes.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class AudioRecorderHelper(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var audioUri: Uri? = null
    private var lastFileName: String? = null
    var isRecording = false
        private set
    var isPaused = false
        private set

    fun start(noteId: Long, onError: (String) -> Unit) {
        if (isRecording) return
        
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        lastFileName = "REC_ID${noteId}_$ts.m4a"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, lastFileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/BVNotes/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        audioUri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        val pfd = audioUri?.let { ctx.contentResolver.openFileDescriptor(it, "w") }

        try {
            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
            else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                if (pfd != null) {
                    setOutputFile(pfd.fileDescriptor)
                }
                prepare(); start()
            }
            isRecording = true
            isPaused = false
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Recorder start failed", e)
            isRecording = false
            onError("Recorder failed: ${e.message}")
        }
    }

    fun pause() {
        if (isRecording && !isPaused) {
            try {
                recorder?.pause()
                isPaused = true
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Pause failed", e)
            }
        }
    }

    fun resume() {
        if (isRecording && isPaused) {
            try {
                recorder?.resume()
                isPaused = false
            } catch (e: Exception) {
                Log.e("AudioRecorder", "Resume failed", e)
            }
        }
    }

    fun stop(): Pair<String, Uri>? {
        if (!isRecording) return null
        isRecording = false
        
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Stop failed", e)
        }
        recorder = null
        
        val resultUri = audioUri
        val resultName = lastFileName
        
        audioUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, values, null, null)
            }
        }
        
        audioUri = null
        lastFileName = null
        return if (resultName != null && resultUri != null) Pair(resultName, resultUri) else null
    }

    fun amplitude() = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
}
