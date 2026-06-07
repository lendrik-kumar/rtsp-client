package com.alexvas.rtsp.codec

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.*
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class RtspVideoRecorder {

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isStarted = false
    private var width = 0
    private var height = 0
    private var path: String? = null
    private var fd: FileDescriptor? = null

    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private val frameRate = 20
    private val frameIntervalUs = 1000000L / frameRate

    private var recordingThread: HandlerThread? = null
    private var recordingHandler: Handler? = null
    private val isProcessing = AtomicBoolean(false)

    constructor(fileDescriptor: FileDescriptor) {
        this.fd = fileDescriptor
    }

    constructor(path: String) {
        this.path = path
    }

    @Synchronized
    fun start(width: Int, height: Int) {
        if (isStarted) return
        
        // Ensure even dimensions for encoder
        this.width = if (width % 2 == 0) width else width - 1
        this.height = if (height % 2 == 0) height else height - 1

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, this.width, this.height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder!!.start()

            muxer = if (fd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                MediaMuxer(fd!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(path!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            recordingThread = HandlerThread("VideoRecorderThread")
            recordingThread!!.start()
            recordingHandler = Handler(recordingThread!!.looper)

            isStarted = true
            presentationTimeUs = 0L
            videoTrackIndex = -1
            Log.i(TAG, "Recorder started (${this.width} x ${this.height})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recorder", e)
            stop()
        }
    }

    @Synchronized
    fun stop() {
        if (!isStarted) return
        isStarted = false
        
        recordingHandler?.post {
            try {
                drainEncoder(true)
                
                encoder?.stop()
                encoder?.release()
                encoder = null

                muxer?.stop()
                muxer?.release()
                muxer = null
                
                videoTrackIndex = -1
                Log.i(TAG, "Recorder stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recorder", e)
            } finally {
                recordingThread?.quitSafely()
                recordingThread = null
                recordingHandler = null
            }
        }
    }

    fun isRecording(): Boolean = isStarted

    fun recordBitmap(bitmap: Bitmap) {
        if (!isStarted || recordingHandler == null) return
        
        // Prevent stacking up too many frames if encoding is slow
        if (isProcessing.get()) return
        
        // Create a copy of the bitmap to work on background thread
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmapCopy = try {
            bitmap.copy(config, false)
        } catch (e: Exception) {
            null
        } ?: return

        isProcessing.set(true)
        recordingHandler?.post {
            try {
                processBitmap(bitmapCopy)
            } finally {
                bitmapCopy.recycle()
                isProcessing.set(false)
            }
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        val encoder = this.encoder ?: return
        try {
            val inputIndex = encoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    
                    val yuvData = encodeYUV420SP(width, height, bitmap)
                    inputBuffer.put(yuvData)
                    
                    encoder.queueInputBuffer(inputIndex, 0, yuvData.size, presentationTimeUs, 0)
                    presentationTimeUs += frameIntervalUs
                }
            }
            drainEncoder(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process bitmap", e)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = this.encoder ?: return
        val muxer = this.muxer ?: return
        
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (videoTrackIndex == -1) {
                    videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                }
            } else if (outputIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                if (outputBuffer != null && videoTrackIndex != -1) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    private fun encodeYUV420SP(width: Int, height: Int, bitmap: Bitmap): ByteArray {
        val size = width * height
        val yuvData = ByteArray(size * 3 / 2)
        val argb = IntArray(size)
        
        // Scale if needed
        val scaledBitmap = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        
        scaledBitmap.getPixels(argb, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = size

        for (j in 0 until height) {
            for (i in 0 until width) {
                val index = j * width + i
                val r = (argb[index] shr 16) and 0xff
                val g = (argb[index] shr 8) and 0xff
                val b = argb[index] and 0xff

                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                yuvData[yIndex++] = (if (y < 0) 0 else if (y > 255) 255 else y).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuvData[uvIndex++] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
                    yuvData[uvIndex++] = (if (u < 0) 0 else if (u > 255) 255 else u).toByte()
                }
            }
        }
        
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        return yuvData
    }

    companion object {
        private val TAG = RtspVideoRecorder::class.java.simpleName
    }
}
