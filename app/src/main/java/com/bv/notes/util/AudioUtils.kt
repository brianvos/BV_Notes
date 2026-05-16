package com.bv.notes.util

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder

object AudioUtils {

    /**
     * Extracts raw 16kHz mono PCM samples from a media file (video or audio).
     */
    fun getSamplesFromUri(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e("AudioUtils", "Failed to set data source: ${e.message}")
            return null
        }

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0) {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) 
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val rawSamples = mutableListOf<Short>()

        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferIndex >= 0) {
                val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)!!
                val shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                
                while (shortBuffer.hasRemaining()) {
                    rawSamples.add(shortBuffer.get())
                }
                
                decoder.releaseOutputBuffer(outputBufferIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        // Resample to 16kHz if necessary
        return if (sampleRate == 16000) {
            FloatArray(rawSamples.size) { i -> rawSamples[i] / 32768f }
        } else {
            resampleTo16k(rawSamples.toShortArray(), sampleRate)
        }
    }

    private fun resampleTo16k(input: ShortArray, sourceSampleRate: Int): FloatArray {
        val targetSampleRate = 16000
        if (input.isEmpty()) return FloatArray(0)
        
        val ratio = sourceSampleRate.toDouble() / targetSampleRate
        val outputSize = (input.size / ratio).toInt()
        val output = FloatArray(outputSize)
        
        for (i in 0 until outputSize) {
            val sourceIndex = (i * ratio).toInt()
            if (sourceIndex < input.size) {
                output[i] = input[sourceIndex] / 32768f
            }
        }
        return output
    }
}
