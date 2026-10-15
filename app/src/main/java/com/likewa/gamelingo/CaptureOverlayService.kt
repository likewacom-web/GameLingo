package com.likewa.gamelingo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.googlecode.tesseract.android.TessBaseAPI
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.concurrent.thread

class CaptureOverlayService : Service() {
    // A piece of on-screen text and where it was found, in screen pixels
    private class TextBlock(val box: Rect, val text: String)

    private lateinit var windowManager: WindowManager
    private var overlay: Button? = null
    private var translationLayer: FrameLayout? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var translator: Translator? = null
    private var source = "en"
    private var target = "th"
    private var screenWidth = 0
    private var screenHeight = 0
    private var processing = false
    private val handler = Handler(Looper.getMainLooper())
    private val overlayType get() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        source = intent?.getStringExtra("source") ?: "en"
        target = intent?.getStringExtra("target") ?: "th"
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("projectionData", Intent::class.java) else intent?.getParcelableExtra("projectionData")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            startForeground(77, buildNotification())
            projection = manager.getMediaProjection(resultCode, data)
            // Android 14+ refuses to capture until a callback is registered
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
            startCapture(); createOverlay(); prepareTranslator()
        } catch (e: Exception) {
            Toast.makeText(this, "เริ่มจับภาพหน้าจอไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "gamelingo_capture"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(channelId, "GameLingo screen capture", NotificationManager.IMPORTANCE_LOW))
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId)
            .setContentTitle("GameLingo กำลังทำงาน").setContentText("แตะปุ่มลอยเพื่อแปลหน้าจอ").setSmallIcon(android.R.drawable.ic_menu_search).build()
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("GameLingo กำลังทำงาน").setContentText("แตะปุ่มลอยเพื่อแปลหน้าจอ").setSmallIcon(android.R.drawable.ic_menu_search).build()
        }
    }

    // Full physical screen size, including status and navigation bars, in the current orientation
    private fun realScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        else DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }

    private fun startCapture() {
        val (width, height) = realScreenSize()
        screenWidth = width; screenHeight = height
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay("GameLingoCapture", width, height, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler)
    }

    // Games often rotate after capture starts; resize the capture instead of creating a second
    // virtual display, which Android 14+ does not allow for one projection. Returns true if resized.
    private fun matchCaptureToScreen(): Boolean {
        val (width, height) = realScreenSize()
        if (width == screenWidth && height == screenHeight) return false
        val old = imageReader
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.resize(width, height, resources.displayMetrics.densityDpi)
        virtualDisplay?.surface = imageReader?.surface
        old?.close()
        screenWidth = width; screenHeight = height
        return true
    }

    private fun createOverlay() {
        // Translations are drawn on a full-screen layer that ignores touches, so the game stays playable
        translationLayer = FrameLayout(this)
        windowManager.addView(translationLayer, fullScreenParams())
        overlay = Button(this).apply { text = "G"; textSize = 18f; setOnClickListener { onTranslateTapped() } }
        val size = (56 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply {
                gravity = Gravity.TOP or Gravity.START
                // Start at the right edge, vertically centred, or where the user last left it
                x = prefs.getInt("buttonX", screenWidth - size - 12).coerceIn(0, maxOf(0, screenWidth - size))
                y = prefs.getInt("buttonY", (screenHeight - size) / 2).coerceIn(0, maxOf(0, screenHeight - size))
            }
        overlay?.setOnTouchListener(dragListener(params, prefs))
        windowManager.addView(overlay, params)
    }

    // Lets the G button be dragged anywhere; a touch that doesn't move counts as a tap.
    private fun dragListener(params: WindowManager.LayoutParams, prefs: android.content.SharedPreferences): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var dragging = false
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = event.rawX; touchY = event.rawY; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, maxOf(0, screenWidth - view.width))
                        params.y = (startY + dy.toInt()).coerceIn(0, maxOf(0, screenHeight - view.height))
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs.edit().putInt("buttonX", params.x).putInt("buttonY", params.y).apply()
                    else view.performClick()
                }
            }
            true
        }
    }

    private fun fullScreenParams() = WindowManager.LayoutParams(screenWidth, screenHeight, overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private fun prepareTranslator() {
        translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnFailureListener { Toast.makeText(this, "ดาวน์โหลดโมเดลภาษาไม่สำเร็จ ต้องใช้อินเทอร์เน็ตก่อน", Toast.LENGTH_LONG).show() }
            ?.addOnSuccessListener { Toast.makeText(this, "โมเดลภาษาเตรียมพร้อมแล้ว", Toast.LENGTH_SHORT).show() }
    }

    // First tap translates everything on screen; the next tap clears the translations.
    private fun onTranslateTapped() {
        if (processing) return
        val layer = translationLayer ?: return
        if (layer.childCount > 0) { layer.removeAllViews(); return }
        processing = true
        overlay?.text = "…"
        val resized = matchCaptureToScreen()
        if (resized) translationLayer?.let { windowManager.updateViewLayout(it, fullScreenParams()) }
        // Give the capture a moment to deliver a frame of the current screen
        handler.postDelayed({ captureAndTranslate() }, if (resized) 500L else 150L)
    }

    private fun captureAndTranslate() {
        val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) { finish("ยังไม่มีภาพหน้าจอ ลองอีกครั้ง"); return }
        val plane = image.planes[0]; val buffer = plane.buffer
        val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val padded = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer); image.close()
        val bitmap = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight); padded.recycle()
        if (source == "th") readThaiText(bitmap) else readWithMlKit(bitmap)
    }

    private fun readWithMlKit(bitmap: Bitmap) {
        val recognizer = createRecognizer()
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                translateBlocks(result.textBlocks.mapNotNull { block ->
                    block.boundingBox?.let { TextBlock(it, block.text.replace('\n', ' ').trim()) }
                })
            }
            .addOnFailureListener { finish("อ่านตัวหนังสือไม่สำเร็จ") }
            .addOnCompleteListener { bitmap.recycle(); recognizer.close() }
    }

    // ML Kit has no Thai recognizer, so Thai goes through Tesseract, line by line, on a background thread.
    private fun readThaiText(bitmap: Bitmap) {
        thread {
            val blocks = try {
                val tess = TessBaseAPI()
                try {
                    if (!tess.init(tessDataPath(), "tha")) null else {
                        tess.setImage(bitmap)
                        tess.getUTF8Text()
                        val lines = mutableListOf<TextBlock>()
                        val level = 2 // TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                        tess.getResultIterator()?.let { iterator ->
                            iterator.begin()
                            do {
                                val text = iterator.getUTF8Text(level) ?: continue
                                // Tesseract puts spaces between Thai words; Thai is written without them.
                                val joined = text.replace(Regex("(?<=[\\u0E00-\\u0E7F]) +(?=[\\u0E00-\\u0E7F])"), "").trim()
                                if (joined.isNotEmpty()) lines += TextBlock(iterator.getBoundingRect(level), joined)
                            } while (iterator.next(level))
                            iterator.delete()
                        }
                        lines
                    }
                } finally { tess.recycle() }
            } catch (_: Exception) { null }
            bitmap.recycle()
            handler.post { if (blocks == null) finish("อ่านตัวหนังสือไทยไม่สำเร็จ") else translateBlocks(blocks) }
        }
    }

    // Tesseract reads models from <path>/tessdata, so copy the bundled model out of assets once.
    private fun tessDataPath(): String {
        val model = File(filesDir, "tessdata/tha.traineddata")
        if (!model.exists()) {
            model.parentFile?.mkdirs()
            assets.open("tessdata/tha.traineddata").use { input -> model.outputStream().use { input.copyTo(it) } }
        }
        return filesDir.absolutePath
    }

    private fun translateBlocks(found: List<TextBlock>) {
        // Skip our own floating button, which is part of the captured screen
        val blocks = found.filter { it.text.isNotBlank() && it.text != "G" }
        if (blocks.isEmpty()) { finish("ไม่พบข้อความ ลองใหม่อีกครั้ง"); return }
        val activeTranslator = translator
        if (activeTranslator == null) { finish("กำลังเตรียมโมเดลแปลภาษา กรุณารอสักครู่"); return }
        val tasks: List<Task<String>> = blocks.map { activeTranslator.translate(it.text) }
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            val translated = blocks.zip(tasks).filter { (_, task) -> task.isSuccessful }
            if (translated.isEmpty()) { finish("แปลไม่สำเร็จ ตรวจสอบภาษาและโมเดล"); return@addOnCompleteListener }
            translated.forEach { (block, task) -> addLabel(block.box, task.result) }
            finish(null)
        }
    }

    // Covers the original text with its translation, shrinking the font so it fits the same box
    private fun addLabel(box: Rect, text: String) {
        val layer = translationLayer ?: return
        val pad = (2 * resources.displayMetrics.density).toInt()
        val label = TextView(this).apply {
            this.text = text; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xE6211A2D.toInt())
            setPadding(pad, 0, pad, 0); gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(6, 40, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val minHeight = (14 * resources.displayMetrics.density).toInt()
        layer.addView(label, FrameLayout.LayoutParams(box.width() + pad * 2, maxOf(box.height(), minHeight)).apply {
            leftMargin = box.left - pad; topMargin = box.top
        })
    }

    private fun finish(message: String?) {
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        overlay?.text = "G"
        processing = false
    }

    // Each script needs its own ML Kit recognizer; Latin is the default.
    private fun createRecognizer(): TextRecognizer = TextRecognition.getClient(
        when (source) {
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            "ko" -> KoreanTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
    )

    override fun onDestroy() {
        try { overlay?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { translationLayer?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        virtualDisplay?.release(); imageReader?.close(); projection?.stop(); translator?.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
