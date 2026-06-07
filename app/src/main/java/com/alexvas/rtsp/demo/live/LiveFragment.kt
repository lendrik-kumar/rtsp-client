package com.alexvas.rtsp.demo.live

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.alexvas.rtsp.codec.VideoDecodeThread
import com.alexvas.rtsp.codec.RtspVideoRecorder
import com.alexvas.rtsp.demo.R
import com.alexvas.rtsp.demo.data.TelemetryData
import com.alexvas.rtsp.demo.databinding.FragmentLiveBinding
import com.alexvas.rtsp.demo.util.MavlinkTcpClient
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspStatusListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("LogNotTimber")
class LiveFragment : Fragment() {

    private var _binding: FragmentLiveBinding? = null
    private val binding get() = _binding!!
    private lateinit var liveViewModel: LiveViewModel

    private var videoRecorder: RtspVideoRecorder? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private var isThermalMode = false
    private var recordingUri: Uri? = null

    private var currentRotation = 180
    private var isRotating = false

    private var mavClient: MavlinkTcpClient? = null
    private var osdIp = "192.168.1.12"
    private var osdPort = 20001

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1.0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var posX = 0f
    private var posY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val minutes = elapsed / 60
                val seconds = elapsed % 60
                binding.tvRecordingTime.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val rtspStatusListener = object : RtspStatusListener {
        override fun onRtspStatusConnecting() {
            _binding?.let { b ->
                context?.let { ctx ->
                    b.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(ctx, android.R.color.holo_orange_dark)
                }
                b.pbLoadingSurface.visibility = View.VISIBLE
            }
        }

        override fun onRtspStatusConnected() {
            _binding?.let { b ->
                context?.let { ctx ->
                    b.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(ctx, android.R.color.holo_green_dark)
                }
                b.pbLoadingSurface.visibility = View.GONE
                b.vShutterSurface.visibility = View.GONE
            }
            setKeepScreenOn(true)
        }

        override fun onRtspStatusDisconnected() {
            if (isRotating) return
            _binding?.let { b ->
                context?.let { ctx ->
                    b.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(ctx, android.R.color.holo_red_dark)
                }
                b.vShutterSurface.visibility = View.VISIBLE
                b.pbLoadingSurface.visibility = View.GONE
            }
            setKeepScreenOn(false)
            stopRecording()
        }

        override fun onRtspStatusFailed(message: String?) {
            if (isRotating) return
            _binding?.let { b ->
                context?.let { ctx ->
                    b.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(ctx, android.R.color.holo_red_dark)
                }
                b.pbLoadingSurface.visibility = View.GONE
            }
            showTopLeftError("Error: $message")
        }

        override fun onRtspFirstFrameRendered() {
            isRotating = false
            _binding?.let { b ->
                b.vShutterSurface.visibility = View.GONE
                b.bnSnapshot.isEnabled = true
            }
        }

        override fun onRtspFrameSizeChanged(width: Int, height: Int) {
            Log.i(TAG, "Resolution: ${width}x${height}")
        }
    }

    private val rtspDataListener = object : RtspDataListener {
        override fun onRtspDataVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestampUs: Long, isKeyframe: Boolean) {
            // Raw packet handling removed as we are recording rendered bitmaps now
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        liveViewModel = ViewModelProvider(this)[LiveViewModel::class.java]
        _binding = FragmentLiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMavlink()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startRecording()
            } else {
                showTopLeftError("Storage permission required")
            }
        }
        if (requestCode == REQUEST_WRITE_STORAGE_SNAPSHOT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                takeSnapshot()
            } else {
                showTopLeftError("Storage permission required")
            }
        }
        if (requestCode == REQUEST_WRITE_STORAGE_INIT) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(context, "Storage permission granted", Toast.LENGTH_SHORT).show()
            } else {
                showTopLeftError("Storage permission required")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hideSystemUI()

        requestStoragePermissionIfNeeded()

        binding.svVideoSurface.setStatusListener(rtspStatusListener)
        binding.svVideoSurface.setDataListener(rtspDataListener)
        binding.ivVideoImage.setStatusListener(rtspStatusListener)
        binding.ivVideoImage.setDataListener(rtspDataListener)
        
        binding.ivVideoImage.onRtspImageBitmapListener = object : com.alexvas.rtsp.widget.RtspImageView.RtspImageBitmapListener {
            override fun onRtspImageBitmapObtained(bitmap: Bitmap) {
                videoRecorder?.let { recorder ->
                    if (!recorder.isRecording()) {
                        recorder.start(bitmap.width, bitmap.height)
                    }
                    recorder.recordBitmap(bitmap)
                }
            }
        }

        // Ensure OSD is on top
        binding.svVideoSurface.setZOrderMediaOverlay(true)

        // Force Software Decoding
        binding.svVideoSurface.videoDecoderType = VideoDecodeThread.DecoderType.SOFTWARE
        binding.ivVideoImage.videoDecoderType = VideoDecodeThread.DecoderType.SOFTWARE

        binding.bnReload.setOnClickListener { reloadStream() }
        binding.bnSnapshot.setOnClickListener { takeSnapshot() }
        binding.bnRecord.setOnClickListener { if (isRecording) stopRecording() else startRecording() }

        binding.bnThermalToggle.setOnClickListener { toggleThermal() }

        // Filter buttons
        binding.bnFilterWhite.setOnClickListener { applyFilter("W") }
        binding.bnFilterBlack.setOnClickListener { applyFilter("B") }
        binding.bnFilterIron.setOnClickListener { applyFilter("I") }
        binding.bnFilterRainbow.setOnClickListener { applyFilter("R") }

        setupInteractions()

        liveViewModel.telemetryData.observe(viewLifecycleOwner) { data ->
            updateOsd(data)
        }

        liveViewModel.loadParams(requireContext())
        startPlayback()
    }

    private fun updateOsd(data: TelemetryData) {
        binding.osdOverlayView.updateTelemetry(data)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractions() {
        scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)
                
                // If we are back to 1.0, snap back to center
                if (scaleFactor <= 1.0f) {
                    scaleFactor = 1.0f
                    posX = 0f
                    posY = 0f
                }
                
                applyTransformations()
                return true
            }
        })

        binding.flVideoContainer.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    // Only pan if we are zoomed in
                    if (scaleFactor > 1.0f && !scaleGestureDetector.isInProgress) {
                        val dx = (event.x - lastTouchX)
                        val dy = (event.y - lastTouchY)
                        posX += dx
                        posY += dy
                        
                        applyTransformations()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    // If we zoomed out, reset position
                    if (scaleFactor <= 1.0f) {
                        resetTransformations()
                    }
                }
            }
            true
        }
    }

    private fun applyTransformations() {
        binding.flVideoContainer.scaleX = scaleFactor
        binding.flVideoContainer.scaleY = scaleFactor
        binding.flVideoContainer.translationX = posX
        binding.flVideoContainer.translationY = posY
    }

    private fun resetTransformations() {
        scaleFactor = 1.0f
        posX = 0f
        posY = 0f
        applyTransformations()
    }

    private fun applyRotationToViews() {
        binding.svVideoSurface.videoRotation = currentRotation
        binding.ivVideoImage.videoRotation = currentRotation
    }

    private fun showTopLeftError(message: String) {
        if (isRotating) return
        val toast = Toast.makeText(context, message, Toast.LENGTH_LONG)
        toast.setGravity(Gravity.TOP or Gravity.START, 48, 48)
        toast.show()
    }

    private fun startPlayback() {
        val baseUri = liveViewModel.rtspRequest.value ?: "rtsp://192.168.1.20:8554/cam"
        val targetUri = if (isThermalMode) {
            if (baseUri.endsWith("/cam")) baseUri.replace("/cam", "/thermal") else baseUri + "/thermal"
        } else {
            baseUri
        }

        Log.i(TAG, "Starting playback: $targetUri")
        
        applyRotationToViews()

        // Start Mavlink client
        try {
            stopMavlink()
            mavClient = MavlinkTcpClient(object : MavlinkTcpClient.TelemetryListener {
                override fun onTelemetryUpdate(data: TelemetryData) {
                    handler.post {
                        liveViewModel.telemetryData.value = data
                    }
                }
            })
            mavClient?.start(osdIp, osdPort)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Mavlink client", e)
        }

        // Always use ivVideoImage for screen-based capture and consistency
        binding.svVideoSurface.visibility = View.GONE
        binding.ivVideoImage.visibility = View.VISIBLE
        binding.llLeftSidebar.visibility = if (isThermalMode) View.VISIBLE else View.GONE
        binding.ivVideoImage.init(
            targetUri.toUri(),
            username = liveViewModel.rtspUsername.value,
            password = liveViewModel.rtspPassword.value,
            userAgent = "rtsp-client-android"
        )
        binding.ivVideoImage.start(requestVideo = true, requestAudio = true, requestApplication = false)
    }

    private fun toggleThermal() {
        isThermalMode = !isThermalMode
        reloadStream()
    }

    private fun reloadStream() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.svVideoSurface.stop()
            binding.ivVideoImage.stop()
            binding.vShutterSurface.visibility = View.VISIBLE
            binding.pbLoadingSurface.visibility = View.VISIBLE
            delay(500) // Give it time to release resources
            startPlayback()
        }
    }

    private fun applyFilter(type: String) {
        val matrix = when (type) {
            "B" -> ColorMatrix(floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f, -1f, 0f, 255f,
                 0f,  0f,  0f, 1f,   0f
            ))
            "I" -> ColorMatrix(floatArrayOf(
                1.5f, 0f, 0f, 0f, 0f,
                0f, 0.6f, 0f, 0f, 0f,
                0f, 0f, 0.2f, 0f, 0f,
                0f, 0f, 0.3f, 1f, 0f
            ))
            "R" -> ColorMatrix(floatArrayOf(
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                1f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            else -> ColorMatrix() // White Hot / Identity
        }
        binding.ivVideoImage.colorFilter = ColorMatrixColorFilter(matrix)
    }

    private fun startRecording() {
        if (!ensureWriteStoragePermission(REQUEST_WRITE_STORAGE)) return

        val activeStarted = if (isThermalMode) binding.ivVideoImage.isStarted() else binding.svVideoSurface.isStarted()
        if (!activeStarted) {
            context?.let { ctx ->
                Toast.makeText(ctx, "Start stream first", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "RTSP_REC_$timestamp.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = context?.contentResolver
            val uri = resolver?.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                try {
                    val pfd = resolver.openFileDescriptor(uri, "w")
                    if (pfd != null) {
                        recordingUri = uri
                        videoRecorder = RtspVideoRecorder(pfd.fileDescriptor)
                        startRecordingTimer()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start recording (FD)", e)
                    showTopLeftError("Recording failed")
                }
            }
        } else {
            // Legacy path for API < 26
            val movieDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!movieDir.exists()) movieDir.mkdirs()
            val file = java.io.File(movieDir, fileName)
            try {
                videoRecorder = RtspVideoRecorder(file.absolutePath)
                recordingUri = Uri.fromFile(file)
                startRecordingTimer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording (Path)", e)
                showTopLeftError("Recording failed")
            }
        }
    }

    private fun startRecordingTimer() {
        isRecording = true
        recordingStartTime = System.currentTimeMillis()
        binding.llRecordingIndicator.visibility = View.VISIBLE
        context?.let { ctx ->
            binding.bnRecord.backgroundTintList = ContextCompat.getColorStateList(ctx, android.R.color.white)
            binding.bnRecord.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.holo_red_dark)
        }
        handler.post(recordingTimerRunnable)
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false
        videoRecorder?.stop()
        videoRecorder = null
        binding.llRecordingIndicator.visibility = View.GONE
        context?.let { ctx ->
            binding.bnRecord.backgroundTintList = ContextCompat.getColorStateList(ctx, android.R.color.holo_red_dark)
            binding.bnRecord.imageTintList = ContextCompat.getColorStateList(ctx, android.R.color.white)
        }

        recordingUri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context?.contentResolver?.update(uri, values, null, null)
            }
            context?.let { ctx ->
                Toast.makeText(ctx, "Video saved to Movies", Toast.LENGTH_LONG).show()
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val filePath = uri.path
                if (filePath != null) {
                    MediaScannerConnection.scanFile(context, arrayOf(filePath), arrayOf("video/mp4"), null)
                }
            }
        }
        recordingUri = null
    }

    private fun takeSnapshot() {
        if (!ensureWriteStoragePermission(REQUEST_WRITE_STORAGE_SNAPSHOT)) return
        val bitmap = getSnapshot()
        if (bitmap != null) {
            saveBitmapToGallery(bitmap)
        } else {
            showTopLeftError("Snapshot failed")
        }
    }

    private fun getSnapshot(): Bitmap? {
        val activeView = if (isThermalMode) binding.ivVideoImage else binding.svVideoSurface
        val width = activeView.width
        val height = activeView.height
        if (width <= 0 || height <= 0) return null
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        if (isThermalMode) {
            val canvas = Canvas(bitmap)
            binding.ivVideoImage.draw(canvas)
            return bitmap
        } else {
            val lock = Object()
            val success = AtomicBoolean(false)
            val thread = HandlerThread("PixelCopyHelper")
            thread.start()
            val sHandler = Handler(thread.looper)
            
            try {
                PixelCopy.request(binding.svVideoSurface.holder.surface, bitmap, { copyResult ->
                    success.set(copyResult == PixelCopy.SUCCESS)
                    synchronized(lock) { lock.notify() }
                }, sHandler)

                synchronized(lock) { lock.wait(2000) }
            } catch (e: Exception) {
                Log.e(TAG, "PixelCopy failed", e)
            } finally {
                thread.quitSafely()
            }
            return if (success.get()) bitmap else null
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "RTSP_IMG_$timestamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context?.contentResolver
        val uri = resolver?.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    val filePath = uri.path
                    if (filePath != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(filePath), arrayOf("image/jpeg"), null)
                    }
                }
                context?.let { ctx ->
                    Toast.makeText(ctx, "Snapshot saved to Pictures", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save snapshot", e)
                showTopLeftError("Failed to save image")
            }
        }
    }

    private fun ensureWriteStoragePermission(requestCode: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val ctx = context ?: return false
        val writePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        val readPermission = android.Manifest.permission.READ_EXTERNAL_STORAGE
        val hasWrite = ContextCompat.checkSelfPermission(ctx, writePermission) == PackageManager.PERMISSION_GRANTED
        val hasRead = ContextCompat.checkSelfPermission(ctx, readPermission) == PackageManager.PERMISSION_GRANTED
        return if (hasWrite && hasRead) {
            true
        } else {
            requestPermissions(arrayOf(writePermission, readPermission), requestCode)
            false
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val ctx = context ?: return
        val writePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        val readPermission = android.Manifest.permission.READ_EXTERNAL_STORAGE
        val needsWrite = ContextCompat.checkSelfPermission(ctx, writePermission) != PackageManager.PERMISSION_GRANTED
        val needsRead = ContextCompat.checkSelfPermission(ctx, readPermission) != PackageManager.PERMISSION_GRANTED
        if (needsWrite || needsRead) {
            requestPermissions(arrayOf(writePermission, readPermission), REQUEST_WRITE_STORAGE_INIT)
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.window?.setDecorFitsSystemWindows(false)
            activity?.window?.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            activity?.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onResume() {
        super.onResume()
        liveViewModel.loadParams(requireContext())
        startPlayback()
    }

    private fun stopMavlink() {
        mavClient?.stop()
        mavClient = null
        liveViewModel.telemetryData.value = TelemetryData()
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
        stopMavlink()
        binding.svVideoSurface.stop()
        binding.ivVideoImage.stop()
    }

    private fun setKeepScreenOn(enable: Boolean) {
        if (enable) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        private val TAG: String = LiveFragment::class.java.simpleName
        private const val REQUEST_WRITE_STORAGE = 1001
        private const val REQUEST_WRITE_STORAGE_SNAPSHOT = 1002
        private const val REQUEST_WRITE_STORAGE_INIT = 1003
    }
}
