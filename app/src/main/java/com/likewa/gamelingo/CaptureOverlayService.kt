package com.likewa.gamelingo

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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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

class CaptureOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: Button? = null
    private var resultView: TextView? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var translator: Translator? = null
    private var source = "en"
    private var target = "th"
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 0
    private var processing = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        source = intent?.getStringExtra("source") ?: "en"
        target = intent?.getStringExtra("target") ?: "th"
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("projectionData", Intent::class.java) else intent?.getParcelableExtra("projectionData")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startForeground(77, buildNotification())
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        startCapture(); createOverlay(); prepareTranslator()
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

    private fun startCapture() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels; screenHeight = metrics.heightPixels; density = metrics.densityDpi
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay("GameLingoCapture", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler)
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        overlay = Button(this).apply { text = "G"; textSize = 18f; setOnClickListener { translateLatestFrame() } }
        val params = WindowManager.LayoutParams((56 * resources.displayMetrics.density).toInt(), (56 * resources.displayMetrics.density).toInt(),
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = 12; y = 0 }
        windowManager.addView(overlay, params)
        resultView = TextView(this).apply {
            text = "GameLingo พร้อมแปล"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xDD211A2D.toInt()); setPadding(24, 18, 24, 18)
            setOnClickListener { try { windowManager.removeView(this) } catch (_: Exception) {}; resultView = null }
        }
        val resultParams = WindowManager.LayoutParams((screenWidth * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 100 }
        handler.post { try { resultView?.let { windowManager.addView(it, resultParams) } } catch (_: Exception) {} }
    }

    private fun prepareTranslator() {
        translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnFailureListener { Toast.makeText(this, "ดาวน์โหลดโมเดลภาษาไม่สำเร็จ ต้องใช้อินเทอร์เน็ตก่อน", Toast.LENGTH_LONG).show() }
            ?.addOnSuccessListener { Toast.makeText(this, "โมเดลภาษาเตรียมพร้อมแล้ว", Toast.LENGTH_SHORT).show() }
    }

    private fun translateLatestFrame() {
        if (processing) return
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) { Toast.makeText(this, "ยังไม่มีภาพหน้าจอ ลองอีกครั้ง", Toast.LENGTH_SHORT).show(); return }
        processing = true
        val plane = image.planes[0]; val buffer = plane.buffer
        val pixelStride = plane.pixelStride; val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val padded = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer); image.close()
        val bitmap = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight); padded.recycle()
        val recognizer = createRecognizer()
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { result ->
            val raw = result.text.trim()
            if (raw.isBlank()) {
                showResult("ไม่พบข้อความ ลองใหม่อีกครั้ง"); processing = false; bitmap.recycle(); recognizer.close()
            } else {
                val activeTranslator = translator
                if (activeTranslator == null) {
                    showResult("กำลังเตรียมโมเดลแปลภาษา กรุณารอสักครู่"); processing = false; bitmap.recycle(); recognizer.close()
                } else activeTranslator.translate(raw)
                    .addOnSuccessListener { translated -> showResult(translated); processing = false; bitmap.recycle(); recognizer.close() }
                    .addOnFailureListener { showResult("แปลไม่สำเร็จ ตรวจสอบภาษาและโมเดล"); processing = false; bitmap.recycle(); recognizer.close() }
            }
        }.addOnFailureListener {
            showResult("อ่านตัวหนังสือไม่สำเร็จ"); processing = false; bitmap.recycle(); recognizer.close()
        }
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

    private fun showResult(text: String) {
        handler.post { resultView?.text = text; Toast.makeText(this, "แปลเสร็จแล้ว", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        try { overlay?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { resultView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        virtualDisplay?.release(); imageReader?.close(); projection?.stop(); translator?.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
