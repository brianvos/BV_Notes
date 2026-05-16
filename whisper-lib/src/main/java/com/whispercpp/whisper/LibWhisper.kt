package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

data class TranscriptSegment(val t0: Long, val t1: Long, val text: String)

class WhisperContext private constructor(private var ptr: Long) {
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    suspend fun transcribeData(data: FloatArray): List<TranscriptSegment> = withContext(scope.coroutineContext) {
        if (ptr == 0L || data.isEmpty()) return@withContext emptyList()
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads for ${data.size} samples")
        WhisperLib.fullTranscribe(ptr, numThreads, data)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        val segments = mutableListOf<TranscriptSegment>()
        for (i in 0 until textCount) {
            val t0 = WhisperLib.getTextSegmentT0(ptr, i)
            val t1 = WhisperLib.getTextSegmentT1(ptr, i)
            val text = WhisperLib.getTextSegment(ptr, i)
            segments.add(TranscriptSegment(t0, t1, text))
        }
        return@withContext segments
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    companion object {
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            return WhisperContext(ptr)
        }

        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            return WhisperContext(ptr)
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            val cpuInfo = try { File("/proc/cpuinfo").readText() } catch (e: Exception) { "" }
            if (cpuInfo.contains("fphp")) {
                System.loadLibrary("whisper_v8fp16_va")
            } else if (cpuInfo.contains("vfpv4")) {
                System.loadLibrary("whisper_vfpv4")
            } else {
                System.loadLibrary("whisper")
            }
        }

        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
    }
}
