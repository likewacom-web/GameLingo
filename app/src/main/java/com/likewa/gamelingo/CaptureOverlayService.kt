package com.likewa.gamelingo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
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
import androidx.core.graphics.ColorUtils
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
    private class TextBlock(val box: Rect, val text: String, val background: Int, val paragraph: Boolean = false, val lineHeight: Int = box.height())

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
    private var liveMode = false
    private val translationCache = HashMap<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val overlayType get() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
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

    // The notification has a stop button, and tapping it opens the app, so GameLingo can always be turned off
    private fun buildNotification(): Notification {
        val channelId = "gamelingo_capture"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, "GameLingo screen capture", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, CaptureOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channelId)
            .setContentTitle("GameLingo กำลังทำงาน").setContentText("แตะปุ่มลอยเพื่อแปล กด หยุด เพื่อปิด")
            .setSmallIcon(android.R.drawable.ic_menu_search).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "หยุด", stop).build())
            .build()
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

    // Tapping G turns live translation on or off. While on, the screen is re-read about once a second,
    // so the game's text stays replaced with the translation as you play.
    private fun onTranslateTapped() {
        liveMode = !liveMode
        overlay?.text = if (liveMode) "■" else "G"
        if (liveMode) {
            Toast.makeText(this, "เปิดแปลสด แตะ ■ เพื่อปิด", Toast.LENGTH_SHORT).show()
            if (!processing) runCycle()
        } else translationLayer?.removeAllViews()
    }

    private fun runCycle() {
        if (!liveMode) return
        processing = true
        val resized = matchCaptureToScreen()
        if (resized) translationLayer?.let { it.removeAllViews(); windowManager.updateViewLayout(it, fullScreenParams()) }
        // Hide our own labels so the capture sees the game's original text, then wait for a frame
        // drawn after they were hidden; reading an older frame would re-translate our own labels.
        translationLayer?.alpha = 0f
        val hiddenAt = System.nanoTime()
        handler.postDelayed({ waitForCleanFrame(hiddenAt, 0) }, if (resized) 500L else 60L)
    }

    private fun waitForCleanFrame(hiddenAt: Long, attempt: Int) {
        val image = try { imageReader?.acquireLatestImage() } catch (_: Exception) { null }
        // Allow two display refreshes after hiding for the change to reach the captured frames. With no
        // labels on screen any frame is clean, and after ~0.6 s take what we have in case the device's
        // frame timestamps use a different clock.
        val clean = translationLayer?.childCount == 0 || attempt >= 12
        if (image != null && (clean || image.timestamp > hiddenAt + 34_000_000L)) {
            val bitmap = toBitmap(image)
            translationLayer?.alpha = 1f
            if (source == "th") readThaiText(bitmap) else readWithMlKit(bitmap)
            return
        }
        image?.close()
        if (attempt < 12) handler.postDelayed({ waitForCleanFrame(hiddenAt, attempt + 1) }, 50L)
        else { translationLayer?.alpha = 1f; cycleDone() }
    }

    private fun cycleDone() {
        processing = false
        if (liveMode) handler.postDelayed({ if (!processing) runCycle() }, 800L)
    }

    private fun toBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]; val buffer = plane.buffer
        val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val padded = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer); image.close()
        return Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight).also { padded.recycle() }
    }

    // A block of several left-aligned lines in the same font size is a paragraph (e.g. a card's
    // description). Translating it as one piece gives the translator the whole sentence instead of
    // fragments; other blocks are translated line by line so separate UI items stay apart.
    private fun isParagraph(block: com.google.mlkit.vision.text.Text.TextBlock): Boolean {
        val boxes = block.lines.mapNotNull { it.boundingBox }
        if (boxes.size < 2 || boxes.size != block.lines.size) return false
        val heights = boxes.map { it.height() }.sorted()
        val median = heights[heights.size / 2].toFloat()
        if (median <= 0f) return false
        val sameSize = heights.all { kotlin.math.abs(it - median) <= median * 0.3f }
        val aligned = boxes.all { kotlin.math.abs(it.left - boxes[0].left) <= median * 1.5f }
        return sameSize && aligned
    }

    // Uses each line rather than each block, so separate buttons, labels and lines of dialogue
    // keep their own place instead of being merged into one big box.
    private fun readWithMlKit(bitmap: Bitmap) {
        val recognizer = createRecognizer()
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val found = result.textBlocks.flatMap { block ->
                    val box = block.boundingBox
                    if (box != null && isParagraph(block)) {
                        val lineHeight = box.height() / block.lines.size
                        listOf(TextBlock(box, block.lines.joinToString(" ") { it.text.trim() }, backgroundColor(bitmap, box), paragraph = true, lineHeight = lineHeight))
                    } else block.lines.mapNotNull { line ->
                        line.boundingBox?.let { TextBlock(it, line.text.trim(), backgroundColor(bitmap, it)) }
                    }
                }
                translateBlocks(found)
            }
            .addOnFailureListener { cycleDone() }
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
                                val box = iterator.getBoundingRect(level)
                                if (joined.isNotEmpty()) lines += TextBlock(box, joined, backgroundColor(bitmap, box))
                            } while (iterator.next(level))
                            iterator.delete()
                        }
                        lines
                    }
                } finally { tess.recycle() }
            } catch (_: Exception) { null }
            bitmap.recycle()
            handler.post {
                if (blocks != null) translateBlocks(blocks)
                else { liveMode = false; overlay?.text = "G"; finish("อ่านตัวหนังสือไทยไม่สำเร็จ"); cycleDone() }
            }
        }
    }

    // Average colour just outside the text's box, so the translation can be painted over the original
    // in the game's own background colour instead of a generic dark box.
    private fun backgroundColor(bitmap: Bitmap, box: Rect): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        fun sample(x: Int, y: Int) {
            if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) return
            val c = bitmap.getPixel(x, y); r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        val left = box.left - 2; val right = box.right + 1; val top = box.top - 2; val bottom = box.bottom + 1
        val stepX = maxOf(1, (right - left) / 12); val stepY = maxOf(1, (bottom - top) / 4)
        var x = left; while (x <= right) { sample(x, top); sample(x, bottom); x += stepX }
        var y = top; while (y <= bottom) { sample(left, y); sample(right, y); y += stepY }
        return if (n == 0) Color.BLACK else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
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
        // Skip our own floating button and OCR noise (stray symbols, single characters)
        val blocks = found.filter { block -> block.text != "G" && block.text != "■" && block.text.count { it.isLetter() } >= 2 }
        val activeTranslator = translator
        if (activeTranslator == null) { finish("กำลังเตรียมโมเดลแปลภาษา กรุณารอสักครู่"); cycleDone(); return }
        // Fixed translations first (the user's corrections, then built-in game terms). A single word with
        // no fixed translation (usually a name like "Wildcard") is left as it is, since the translator
        // gets those wrong without context. Everything else goes to the translator, cached because game
        // text repeats a lot between frames.
        val spaced = source !in setOf("ja", "zh", "ko", "th")
        val fresh = mutableListOf<Glossary.Entry>()
        val tasks: List<Task<String?>> = blocks.map { block ->
            val fixed = Glossary.lookup(this, block.text, source, target)
            when {
                fixed != null -> Tasks.forResult<String?>(fixed)
                spaced && !block.text.trim().contains(' ') -> Tasks.forResult<String?>(null)
                else -> translationCache[block.text]?.let { Tasks.forResult<String?>(it) } ?: run {
                    // Put the game's own term translations into the sentence first
                    val prepared = Glossary.substituteTerms(this, block.text, source, target)
                    if (spaced && prepared.none { it in 'A'..'Z' || it in 'a'..'z' }) Tasks.forResult<String?>(prepared)
                    else activeTranslator.translate(prepared).continueWith { task ->
                        val result = task.result
                        if (translationCache.size > 500) translationCache.clear()
                        translationCache[block.text] = result
                        fresh += Glossary.Entry(source, target, block.text, result)
                        result
                    }
                }
            }
        }
        Tasks.whenAllComplete(tasks).addOnCompleteListener {
            translationLayer?.removeAllViews()
            if (liveMode) blocks.zip(tasks).forEach { (block, task) ->
                if (task.isSuccessful) task.result?.let { addLabel(block, it) }
            }
            Glossary.remember(this, fresh)
            cycleDone()
        }
    }

    // Paints the translation over the original text in the same box and background colour, with a
    // contrasting text colour. The font starts at the original line's height and shrinks until the
    // translation fits, so labels never spill over neighbouring text. Longer translations may use up to
    // 30% more width when there is room on screen.
    private fun addLabel(block: TextBlock, text: String) {
        val layer = translationLayer ?: return
        val box = block.box
        val pad = (2 * resources.displayMetrics.density).toInt()
        val light = ColorUtils.calculateLuminance(block.background) > 0.5
        // A paragraph keeps its box and wraps; a single line may widen a little
        val width = (if (block.paragraph) box.width() else minOf((box.width() * 1.3f).toInt(), screenWidth - box.left)) + pad * 2
        val height = box.height() + pad
        val maxPx = maxOf(8, (block.lineHeight * 0.95f).toInt())
        val minPx = minOf(maxPx, (7 * resources.displayMetrics.density).toInt())
        val label = TextView(this).apply {
            this.text = text; setTextColor(if (light) Color.BLACK else Color.WHITE)
            setBackgroundColor(block.background); setPadding(pad, 0, pad, 0)
            gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
            if (!block.paragraph) maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(minPx, maxPx, 1, TypedValue.COMPLEX_UNIT_PX)
        }
        layer.addView(label, FrameLayout.LayoutParams(width, height).apply {
            leftMargin = box.left - pad; topMargin = box.top - pad / 2
        })
    }

    private fun finish(message: String?) {
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
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
        liveMode = false; handler.removeCallbacksAndMessages(null)
        try { overlay?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { translationLayer?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        virtualDisplay?.release(); imageReader?.close(); projection?.stop(); translator?.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.likewa.gamelingo.STOP"
    }
}
