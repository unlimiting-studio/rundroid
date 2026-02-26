package ing.unlimit.rundroid.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import ing.unlimit.rundroid.util.ScreenshotUtil
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "screen_capture_channel"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var mediaProjection: MediaProjection? = null
            private set

        var instance: ScreenCaptureService? = null
            private set

        fun captureScreenshot(callback: (ByteArray?) -> Unit) {
            takeScreenshot(callback)
        }

        fun takeScreenshot(callback: (ByteArray?) -> Unit) {
            instance?.captureScreenshot(callback) ?: callback(null)
        }
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val isCapturing = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.let { extractResultData(it) }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            mediaProjection?.stop()
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
            Log.i(TAG, "MediaProjection initialized")
        } else {
            Log.w(TAG, "MediaProjection permission data is missing or denied")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        instance = null
        Log.i(TAG, "MediaProjection released")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun captureScreenshot(callback: (ByteArray?) -> Unit) {
        val reader = imageReader
        if (reader == null) {
            Log.w(TAG, "ImageReader is null")
            callback(null)
            return
        }

        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "Already capturing")
            callback(null)
            return
        }

        // Use a handler to retry acquireLatestImage with short delays
        // VirtualDisplay continuously renders frames, so an image should be available quickly
        val handler = Handler(Looper.getMainLooper())
        val maxRetries = 10
        var retryCount = 0

        fun tryCapture() {
            var image: android.media.Image? = null
            var bitmap: Bitmap? = null
            var croppedBitmap: Bitmap? = null

            try {
                image = reader.acquireLatestImage()
                if (image == null) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        handler.postDelayed(::tryCapture, 100)
                        return
                    }
                    Log.w(TAG, "No image available after $maxRetries retries")
                    isCapturing.set(false)
                    callback(null)
                    return
                }

                val width = image.width
                val height = image.height
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val rawBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap = rawBitmap
                rawBitmap.copyPixelsFromBuffer(buffer)

                croppedBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                } else {
                    rawBitmap
                }
                val pngBytes = ScreenshotUtil.bitmapToPng(croppedBitmap)

                isCapturing.set(false)
                callback(pngBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture screenshot", e)
                isCapturing.set(false)
                callback(null)
            } finally {
                if (croppedBitmap != null && croppedBitmap !== bitmap) {
                    croppedBitmap.recycle()
                }
                bitmap?.recycle()
                image?.close()
            }
        }

        tryCapture()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen Capture")
            .setContentText("Screen capture is active")
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun setupVirtualDisplay() {
        val projection = mediaProjection
        if (projection == null) {
            Log.w(TAG, "MediaProjection is null. Skipping virtual display setup")
            return
        }

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun extractResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }
}
