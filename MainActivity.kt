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
        "Chinese (Simplified)" to "zh", "Chinese (Traditional)" to "zh",
        "French" to "fr", "German" to "de", "Spanish" to "es",
        "Italian" to "it", "Portuguese" to "pt", "Russian" to "ru",
        "Thai" to "th", "Indonesian" to "id", "Vietnamese" to "vi"
    )
    private val captureRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            text = "GameLingo — แปลภาษาในเกม"
            textSize = 24f
        })
        layout.addView(TextView(this).apply {
            text = "เลือกภาษาต้นทางและภาษาปลายทาง จากนั้นอนุญาตการจับภาพหน้าจอและปุ่มลอย"
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })
        sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            languages.map { "${it.first} (${it.second})" })
        sourceSpinner.setSelection(0)
        layout.addView(TextView(this).apply { text = "ภาษาต้นทาง" })
        layout.addView(sourceSpinner)
        targetSpinner = Spinner(this)
        targetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("ไทย (th)", "English (en)", "日本語 (ja)", "한국어 (ko)", "简体中文 (zh)", "Français (fr)", "Español (es)"))
        layout.addView(TextView(this).apply { text = "แปลเป็น" })
        layout.addView(targetSpinner)
        layout.addView(Button(this).apply {
            text = "เริ่มใช้งานปุ่มลอย"
            setOnClickListener { beginCaptureFlow() }
        })
        layout.addView(TextView(this).apply {
            text = "\nวิธีใช้\n1. เปิดเกมที่ต้องการแปล\n2. กลับมาเปิด GameLingo แล้วกดเริ่ม\n3. อนุญาตจับภาพหน้าจอและแสดงทับแอปอื่น\n4. กลับเข้าเกมแล้วแตะปุ่ม G เพื่อแปล\n\nMVP นี้ประมวลผลข้อความบนเครื่องด้วย ML Kit หลังดาวน์โหลดโมเดลภาษาแล้ว"
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
        val sourceCode = languages[sourceSpinner.selectedItemPosition].second
        val targets = listOf("th", "en", "ja", "ko", "zh", "fr", "es")
        val targetCode = targets[targetSpinner.selectedItemPosition.coerceIn(targets.indices)]
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingSource = sourceCode
        pendingTarget = targetCode
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    private var pendingSource = "en"
    private var pendingTarget = "th"

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, CaptureOverlayService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("source", pendingSource)
                putExtra("target", pendingTarget)
                putExtra("projectionData", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "เปิดปุ่มลอยแล้ว กลับเข้าเกมได้เลย", Toast.LENGTH_LONG).show()
        }
    }
}
