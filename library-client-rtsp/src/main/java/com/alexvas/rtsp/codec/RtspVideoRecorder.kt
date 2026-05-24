package com.alexvas.rtsp.codec

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

class RtspVideoRecorder {

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isStarted = false
    private var baseTimestampUs = -1L
    private var firstKeyframeReceived = false

    constructor(fileDescriptor: FileDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            muxer = MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            Log.e(TAG, "MediaMuxer(FileDescriptor) requires API 26+")
        }
    }

    constructor(path: String) {
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    @Synchronized
    fun start(format: MediaFormat) {
        if (isStarted || muxer == null) return
        try {
            videoTrackIndex = muxer!!.addTrack(format)
            muxer!!.start()
            isStarted = true
            baseTimestampUs = -1L
            firstKeyframeReceived = false
            Log.i(TAG, "Muxer started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start muxer", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!isStarted || muxer == null) return
        try {
            isStarted = false
            muxer!!.stop()
            muxer!!.release()
            muxer = null
            Log.i(TAG, "Muxer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop muxer", e)
        }
    }

    @Synchronized
    fun writeVideoData(data: ByteArray, offset: Int, length: Int, timestampUs: Long, isKeyframe: Boolean) {
        if (!isStarted || muxer == null) return
        
        // Ensure we start with a keyframe for better compatibility
        if (!firstKeyframeReceived) {
            if (!isKeyframe) return
            firstKeyframeReceived = true
        }

        if (baseTimestampUs == -1L) {
            baseTimestampUs = timestampUs
        }

        try {
            val buffer = ByteBuffer.wrap(data, offset, length)
            val info = MediaCodec.BufferInfo().apply {
                this.offset = offset
                this.size = length
                // Normalize timestamp to start from 0
                this.presentationTimeUs = timestampUs - baseTimestampUs
                this.flags = if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            }
            muxer!!.writeSampleData(videoTrackIndex, buffer, info)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sample data", e)
        }
    }

    companion object {
        private val TAG = RtspVideoRecorder::class.java.simpleName
    }
}
