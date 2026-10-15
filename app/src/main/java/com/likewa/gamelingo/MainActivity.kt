package com.likewa.gamelingo

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private val languages = listOf(
        "English" to "en", "Japanese" to "ja", "Korean" to "ko",
        "Chinese (Simplified)" to "zh", "French" to "fr", "German" to "de",
        "Spanish" to "es", "Italian" to "it", "Portuguese" to "pt",
        "Russian" to "ru", "Thai" to "th", "Indonesian" to "id", "Vietnamese" to "vi"
    )
    private val targets = listOf("ไทย (th)" to "th", "English (en)" to "en", "日本語 (ja)" to "ja",
        "한국어 (ko)" to "ko", "简体中文 (zh)" to "zh", "Français (fr)" to "fr", "Español (es)" to "es")
    private val captureRequest = 4102
    private var pendingSource = "en"
    private var pendingTarget = "th"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        layout.addView(TextView(this).apply { text = "GameLingo — แปลภาษาในเกม"; textSize = 24f })
        layout.addView(TextView(this).apply {
            text = "เลือกภาษา แล้วอนุญาตการจับภาพหน้าจอและปุ่มลอย"
            textSize = 15f; setPadding(0, pad / 2, 0, pad)
        })
        sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { "${it.first} (${it.second})" })
        layout.addView(TextView(this).apply { text = "ภาษาต้นทาง" })
        layout.addView(sourceSpinner)
        targetSpinner = Spinner(this)
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targets.map { it.first })
        layout.addView(TextView(this).apply { text = "แปลเป็น" })
        layout.addView(targetSpinner)
        layout.addView(Button(this).apply { text = "เริ่มใช้งานปุ่มลอย"; setOnClickListener { beginCaptureFlow() } })
        layout.addView(TextView(this).apply {
            text = "\nวิธีใช้\n1. เลือกภาษา\n2. กดเริ่มและอนุญาตแสดงทับแอปอื่น\n3. อนุญาตจับภาพหน้าจอ\n4. กลับเข้าเกมแล้วแตะปุ่ม G เพื่อแปล"
            textSize = 14f
        })
        setContentView(layout)
    }

    private fun beginCaptureFlow() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "อนุญาตให้ GameLingo แสดงทับแอปอื่น แล้วกลับมากดเริ่มอีกครั้ง", Toast.LENGTH_LONG).show()
            return
        }
        pendingSource = languages[sourceSpinner.selectedItemPosition].second
        pendingTarget = targets[targetSpinner.selectedItemPosition].second
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, CaptureOverlayService::class.java).apply {
                putExtra("resultCode", resultCode); putExtra("source", pendingSource)
                putExtra("target", pendingTarget); putExtra("projectionData", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "เปิดปุ่มลอยแล้ว กลับเข้าเกมได้เลย", Toast.LENGTH_LONG).show()
        }
    }
}
