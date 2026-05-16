package com.bv.notes.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.*

class LectureManager(private val ctx: Context) {
    private var recorder: MediaRecorder? = null
    private var videoUri: Uri? = null
    private var lastFileName: String? = null
    var isRecording = false
        private set
    var isPaused = false
        private set

    fun startRecording(noteId: Long, previewSurface: Surface, onError: (String) -> Unit) {
        if (isRecording) return
        
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        lastFileName = "LECTURE_ID${noteId}_$ts.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, lastFileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BVNotes/")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        videoUri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        val pfd = videoUri?.let { ctx.contentResolver.openFileDescriptor(it, "w") }

        try {
            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx)
            else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5000000)
                setAudioSamplingRate(48000)
                setAudioEncodingBitRate(192000)

                if (pfd != null) setOutputFile(pfd.fileDescriptor)
                setPreviewDisplay(previewSurface)
                prepare()
                start()
            }
            isRecording = true
            isPaused = false
        } catch (e: Exception) {
            Log.e("LectureManager", "Video start failed", e)
            isRecording = false
            onError("Video recording failed: ${e.message}")
        }
    }

    fun pause() {
        if (isRecording && !isPaused) {
            try {
                recorder?.pause()
                isPaused = true
            } catch (e: Exception) {
                Log.e("LectureManager", "Pause failed", e)
            }
        }
    }

    fun resume() {
        if (isRecording && isPaused) {
            try {
                recorder?.resume()
                isPaused = false
            } catch (e: Exception) {
                Log.e("LectureManager", "Resume failed", e)
            }
        }
    }

    fun stopRecording(): Pair<String, Uri>? {
        if (!isRecording) return null
        isRecording = false
        
        try {
            recorder?.stop()
            recorder?.release()
        } catch (e: Exception) {}
        recorder = null

        val resultUri = videoUri
        val resultName = lastFileName

        videoUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, values, null, null)
            }
        }
        videoUri = null
        lastFileName = null
        return if (resultName != null && resultUri != null) Pair(resultName, resultUri) else null
    }
}
