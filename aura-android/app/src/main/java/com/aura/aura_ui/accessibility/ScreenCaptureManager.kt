package com.aura.aura_ui.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.annotation.RequiresApi
import com.aura.aura_ui.data.preferences.ThemeManager
import com.aura.aura_ui.utils.AgentLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureManager(
    private val service: AccessibilityService,
    private val uiTreeExtractor: UITreeExtractor,
) {
    // Timeout configuration (must be < backend timeout)
    companion object {
        private const val CAPTURE_TIMEOUT_MS = 6000L  // 6 seconds (backend waits 8s)
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val isCapturing = AtomicBoolean(false)
    private val isCaptureRequested = AtomicBoolean(false) // NEW: Flag to control when to capture
    private var pendingCaptureCallback: ((ScreenshotData) -> Unit)? = null // NEW: Store callback

    private var mediaProjectionResultCode: Int? = null
    private var mediaProjectionData: Intent? = null

    var screenWidth = 0
    var screenHeight = 0

    init {
        // MUST match the coordinate space gestures are dispatched in. Sizing the
        // VirtualDisplay from resources.displayMetrics produced a *scaled* copy of
        // the screen on any device where that excludes decor insets — so every
        // omniparser/YOLO box was offset from where a tap would actually land.
        val (w, h) = ScreenGeometry.realSizePx(service)
        screenWidth = w
        screenHeight = h
        AgentLogger.UI.i(
            "📐 Screen dimensions initialized: ${screenWidth}x$screenHeight " +
                "@ ${ScreenGeometry.densityDpi(service)}dpi",
        )
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun initializeMediaProjection(
        resultCode: Int,
        data: Intent,
    ): Boolean {
        if (!AccessibilityCapturePolicy.MIRROR_ENABLED) {
            // THE GATE. Perception moved to AccessibilityService.takeScreenshot() on 2026-08-12;
            // the mirror is disabled at the source so no caller — present or future — can leave
            // a VirtualDisplay compositing while AURA is idle. See AccessibilityCapturePolicy.
            AgentLogger.Screen.i("🚫 MediaProjection is disabled (perception uses takeScreenshot) — refusing to start the mirror")
            return false
        }
        return try {
            AgentLogger.Screen.i("🔐 Initializing MediaProjection with resultCode=$resultCode")

            this.mediaProjectionResultCode = resultCode
            this.mediaProjectionData = data

            val mediaProjectionManager = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                AgentLogger.Screen.e("❌ CRITICAL: getMediaProjection() returned NULL!")
                AgentLogger.Screen.e("   This usually means permission was denied or resultCode is invalid")
                return false
            }

            AgentLogger.Screen.i("✅ MediaProjection initialized successfully!")
            AgentLogger.Screen.i("   Screen dimensions: ${screenWidth}x$screenHeight")
            
            // IMPORTANT: Immediately set up capture resources so isMediaProjectionAvailable() returns true
            setupCaptureResources()
            
            true
        } catch (e: Exception) {
            AgentLogger.Screen.e("❌ CRITICAL: Failed to initialize MediaProjection", e)
            false
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setupCaptureResources(): Boolean {
        if (mediaProjection == null) {
            AgentLogger.Screen.e("❌ Cannot setup capture resources: MediaProjection is null")
            return false
        }
        
        if (imageReader != null && virtualDisplay != null) {
            AgentLogger.Screen.d("📷 Capture resources already set up")
            return true
        }
        
        try {
            // Validate screen dimensions
            if (screenWidth <= 0 || screenHeight <= 0) {
                val (w, h) = ScreenGeometry.realSizePx(service)
                screenWidth = w
                screenHeight = h
                AgentLogger.Screen.i("📐 Screen dimensions recovered: ${screenWidth}x$screenHeight")
            }
            
            // Create ImageReader
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            AgentLogger.Screen.i("📷 ImageReader created: ${screenWidth}x$screenHeight")
            
            if (imageReader?.surface == null) {
                AgentLogger.Screen.e("❌ ImageReader.surface is NULL!")
                return false
            }
            
            // Register MediaProjection callback for Android 14+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjection?.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            AgentLogger.Screen.i("📺 MediaProjection stopped via callback")
                            cleanup()
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
            
            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AURA_Screenshot",
                screenWidth,
                screenHeight,
                ScreenGeometry.densityDpi(service),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                Handler(Looper.getMainLooper()),
            )
            
            if (virtualDisplay == null) {
                AgentLogger.Screen.e("❌ VirtualDisplay creation FAILED!")
                return false
            }
            
            // Set up the image listener
            setupImageReaderListener()
            
            AgentLogger.Screen.i("✅ Capture resources ready: ${screenWidth}x$screenHeight")
            return true
            
        } catch (e: SecurityException) {
            AgentLogger.Screen.e("❌ SecurityException during setup - token may be exhausted", e)
            cleanup()
            return false
        } catch (e: Exception) {
            AgentLogger.Screen.e("❌ Failed to setup capture resources", e)
            return false
        }
    }

    fun isMediaProjectionAvailable(): Boolean {
        // Check if we have permission data OR an active projection
        val hasPermissionData = mediaProjectionResultCode != null && mediaProjectionData != null
        val hasActiveProjection = mediaProjection != null
        val hasResources = virtualDisplay != null && imageReader != null
        
        val available = hasActiveProjection && hasResources
        
        AgentLogger.Screen.d(
            "📷 MediaProjection status: available=$available " +
            "(projection=${hasActiveProjection}, resources=${hasResources}, permData=${hasPermissionData})",
        )
        
        return available
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun captureScreenWithAnalysis(
        force: Boolean = false,
        onComplete: (ScreenshotData) -> Unit,
    ) {
        if (!AccessibilityCapturePolicy.MIRROR_ENABLED) {
            // Second half of the gate: even with a projection somehow in hand, this path must
            // not be the one that serves a frame. Callers get the UI tree, which is what the
            // old code already did whenever capture was unavailable — so no caller breaks.
            AgentLogger.Screen.d("MediaProjection capture disabled — returning UI tree only")
            sendUITreeOnly(onComplete)
            return
        }
        val isScreenCaptureEnabled =
            runBlocking {
                ThemeManager.enableScreenCapture.first()
            }

        if (!isScreenCaptureEnabled && !force) {
            AgentLogger.Screen.i("Screen capture disabled by user preference - sending UI tree only")
            sendUITreeOnly(onComplete)
            return
        }

        if (isCapturing.get() && !force) {
            AgentLogger.Screen.w("Screenshot capture already in progress")
            return
        }

        try {
            isCapturing.set(true)
            isCaptureRequested.set(true)
            pendingCaptureCallback = onComplete

            // If we don't have resources, try to set them up
            if (!isMediaProjectionAvailable()) {
                // Try to recreate from stored permission data
                if (mediaProjectionData != null && mediaProjectionResultCode != null && mediaProjection == null) {
                    AgentLogger.Screen.i("Attempting to recreate MediaProjection from stored data")
                    try {
                        val mediaProjectionManager = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjection = mediaProjectionManager.getMediaProjection(mediaProjectionResultCode!!, mediaProjectionData!!)
                        
                        if (mediaProjection != null) {
                            setupCaptureResources()
                        }
                    } catch (e: SecurityException) {
                        AgentLogger.Screen.e("❌ MediaProjection token exhausted", e)
                        invalidatePermission()
                    } catch (e: Exception) {
                        AgentLogger.Screen.e("Failed to recreate MediaProjection", e)
                    }
                }
                
                // If still not available, send UI-only data with error
                if (!isMediaProjectionAvailable()) {
                    AgentLogger.Screen.w("MediaProjection not available - sending UI tree only")
                    val errorData = ScreenshotData(
                        screenshot = "",
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        timestamp = System.currentTimeMillis(),
                        uiElements = uiTreeExtractor.getUIElements(),
                        error = "MediaProjection permission required"
                    )
                    onComplete(errorData)
                    resetCaptureState()
                    return
                }
            }

            // Try to capture immediately from buffer
            try {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    AgentLogger.Screen.d("⚡ Captured image immediately")
                    processImage(image)
                    return
                } else {
                    AgentLogger.Screen.d("No image in buffer - triggering frame refresh")
                    // Force a frame refresh so the VirtualDisplay produces a new frame
                    forceScreenRefresh()
                }
            } catch (e: Exception) {
                AgentLogger.Screen.w("Could not acquire image immediately: ${e.message}")
            }
            
            // Set timeout in case no frame arrives
            Handler(Looper.getMainLooper()).postDelayed({
                if (isCaptureRequested.get() && pendingCaptureCallback != null) {
                    AgentLogger.Screen.w("⏰ Capture timeout - sending UI tree only")
                    sendUITreeOnly(pendingCaptureCallback!!)
                    resetCaptureState()
                }
            }, CAPTURE_TIMEOUT_MS)
            
        } catch (e: Exception) {
            AgentLogger.Screen.e("Error during capture", e)
            resetCaptureState()
        }
    }
    
    private fun sendUITreeOnly(onComplete: (ScreenshotData) -> Unit) {
        try {
            val uiElements = uiTreeExtractor.getUIElements()
            val data = ScreenshotData(
                screenshot = "",
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                timestamp = System.currentTimeMillis(),
                uiElements = uiElements,
            )
            onComplete(data)
            AgentLogger.Auto.i("Sent ${uiElements.size} UI elements without screenshot")
        } catch (e: Exception) {
            AgentLogger.Screen.e("Error sending UI-only data", e)
        }
        resetCaptureState()
    }
    
    private fun resetCaptureState() {
        isCaptureRequested.set(false)
        isCapturing.set(false)
        pendingCaptureCallback = null
    }
    
    private fun invalidatePermission() {
        mediaProjectionData = null
        mediaProjectionResultCode = null
        mediaProjection = null
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        AgentLogger.Screen.i("🔒 Permission invalidated - will need fresh grant")
    }

    private fun setupImageReaderListener() {
        imageReader?.setOnImageAvailableListener({ reader ->
                // NEW: Only capture if explicitly requested
                if (!isCaptureRequested.get()) {
                    AgentLogger.Screen.d("🚫 Ignoring image available - no capture requested")
                    return@setOnImageAvailableListener
                }

                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        processImage(image)
                    }
                } catch (e: Exception) {
                    AgentLogger.Screen.e("❌ Error processing screenshot from listener", e)
                    isCaptureRequested.set(false)
                    isCapturing.set(false)
                    pendingCaptureCallback = null
                }
            }, Handler(Looper.getMainLooper()))
    }

    private fun processImage(image: Image) {
        try {
            val bitmap = imageToBitmap(image)
            if (bitmap == null) {
                AgentLogger.Screen.e("⚠️ Failed to convert image to bitmap")
                return
            }

            val base64Screenshot = bitmapToBase64(bitmap)
            if (base64Screenshot.isEmpty()) {
                AgentLogger.Screen.e("⚠️ Base64 encoding failed - empty result")
                return
            }

            AgentLogger.Screen.i("📸 Screenshot captured: ${base64Screenshot.length} chars (~${base64Screenshot.length / 1024} KB)")

            val uiElements = uiTreeExtractor.getUIElements()
            val screenshotData = ScreenshotData(
                screenshot = base64Screenshot,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                timestamp = System.currentTimeMillis(),
                uiElements = uiElements,
            )
            pendingCaptureCallback?.invoke(screenshotData)
        } finally {
            image.close()
            isCaptureRequested.set(false)
            isCapturing.set(false)
            pendingCaptureCallback = null
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            if (planes.isEmpty()) {
                AgentLogger.Screen.e("⚠️ Image has no planes!")
                return null
            }

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            if (screenWidth <= 0 || screenHeight <= 0) {
                AgentLogger.Screen.e("⚠️ Invalid screen dimensions: ${screenWidth}x$screenHeight")
                return null
            }

            AgentLogger.Screen.d("📊 Buffer: ${buffer.remaining()} bytes, rowStride=$rowStride, pixelStride=$pixelStride")

            val expectedBytes = screenHeight * rowStride
            if (buffer.remaining() < expectedBytes) {
                AgentLogger.Screen.e("⚠️ Buffer too small: ${buffer.remaining()} < $expectedBytes")
                return null
            }

            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)

            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            }
        } catch (e: Exception) {
            AgentLogger.Screen.e("⚠️ imageToBitmap FAILED", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun forceScreenRefresh() {
        try {
            AgentLogger.Screen.d("🔄 Screen refresh needed - no frame in buffer")
            
            // DON'T try to recreate VirtualDisplay - it will throw SecurityException
            // Instead, if there's an existing VirtualDisplay, try to trigger a frame via resize
            if (virtualDisplay != null) {
                try {
                    // Explicit frame trigger: resize by 1px then restore to force compositor update
                    AgentLogger.Screen.d("⚡ Triggering frame via resize...")
                    virtualDisplay?.resize(screenWidth - 1, screenHeight, ScreenGeometry.densityDpi(service))
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            virtualDisplay?.resize(screenWidth, screenHeight, ScreenGeometry.densityDpi(service))
                            AgentLogger.Screen.d("✅ Frame trigger applied")
                        } catch (e: Exception) {
                            AgentLogger.Screen.w("Resize restore failed: ${e.message}")
                        }
                    }, 50)
                    return
                } catch (e: Exception) {
                    AgentLogger.Screen.w("Frame trigger via resize failed: ${e.message}")
                }
            }
            
            // If we can't refresh, the MediaProjection is likely invalid
            // Clean up and mark for re-permission
            AgentLogger.Screen.w("⚠️ MediaProjection token exhausted - need new permission")
            cleanup()
            
        } catch (e: Exception) {
            AgentLogger.Screen.e("❌ Error during screen refresh attempt", e)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        if (bitmap.isRecycled) {
            AgentLogger.Screen.e("⚠️ Bitmap is recycled, cannot compress!")
            return ""
        }
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            AgentLogger.Screen.e("⚠️ Bitmap has invalid dimensions: ${bitmap.width}x${bitmap.height}")
            return ""
        }

        val outputStream = ByteArrayOutputStream()
        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)

        if (!success) {
            AgentLogger.Screen.e("⚠️ Bitmap.compress() returned false!")
            return ""
        }

        val byteArray = outputStream.toByteArray()
        val sizeKB = byteArray.size / 1024
        AgentLogger.Screen.i("📸 Compressed bitmap: ${byteArray.size} bytes ($sizeKB KB)")

        if (byteArray.isEmpty()) {
            AgentLogger.Screen.e("⚠️ Compression produced 0 bytes!")
            return ""
        }

        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    @SuppressLint("NewApi")
    fun cleanup() {
        isCaptureRequested.set(false) // Reset capture flag
        isCapturing.set(false) // Reset capturing flag
        pendingCaptureCallback = null // Clear pending callback
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        // IMPORTANT: Clear stale permission data to force fresh permission request
        mediaProjectionData = null
        mediaProjectionResultCode = null
        AgentLogger.Screen.i("Screen capture resources cleaned up (permission data cleared)")
    }

    @SuppressLint("NewApi")
    fun disableScreenCapture() {
        cleanup()
        AgentLogger.Screen.i("Screen capture fully disabled")
    }
}
