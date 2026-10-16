package com.likewa.gamelingo

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
        layout.addView(Button(this).apply { text = "แก้คำแปล"; setOnClickListener { showCorrections() } })
        layout.addView(Button(this).apply {
            text = "นำเข้าพจนานุกรมเกม (.json)"
            setOnClickListener { pickGlossary.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        })
        layout.addView(TextView(this).apply {
            text = "พจนานุกรมที่ใช้อยู่: " + Glossary.packs(this@MainActivity).joinToString { "${it.name} (${it.entries.size} คำ)" }
                .ifEmpty { "ไม่มี" }
            textSize = 12f
        }.also { packsLabel = it })
        layout.addView(Button(this).apply {
            text = "หยุดใช้งาน (ปิดปุ่มลอย)"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureOverlayService::class.java))
                Toast.makeText(this@MainActivity, "ปิด GameLingo แล้ว", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(TextView(this).apply {
            text = "\nวิธีใช้\n1. เลือกภาษา\n2. กดเริ่มและอนุญาตแสดงทับแอปอื่น\n3. อนุญาตจับภาพหน้าจอ\n4. กลับเข้าเกมแล้วแตะปุ่ม G เพื่อเปิดแปลสด (แตะ ■ เพื่อปิด)\n5. กดค้างแล้วลากเพื่อย้ายปุ่ม\n6. ปิดใช้งาน: กด หยุด ในแถบแจ้งเตือน หรือกดปุ่ม หยุดใช้งาน ด้านบน\n7. คำไหนแปลผิด: กด แก้คำแปล แล้วเลือกคำนั้น ครั้งต่อไปจะแปลตามที่แก้\n8. มีไฟล์ศัพท์ของเกม: กด นำเข้าพจนานุกรมเกม"
            textSize = 14f
        })
        showLastCrash(layout, pad)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // Shows why the app closed last time, so it can be copied and reported: the Java stack trace saved by
    // GameLingoApp, or, for native crashes and system kills that bypass it, Android's exit record (API 30+).
    private fun showLastCrash(layout: LinearLayout, pad: Int) {
        val parts = mutableListOf<String>()
        val file = GameLingoApp.crashFile(application)
        if (file.exists()) { parts += file.readText(); file.delete() }
        lastAbnormalExit()?.let { parts += it }
        if (parts.isEmpty()) return
        val report = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
            parts.joinToString("\n\n")
        layout.addView(TextView(this).apply {
            text = "\nครั้งก่อนแอปปิดตัวเพราะข้อผิดพลาดนี้:"; textSize = 15f; setTextColor(0xFFD32F2F.toInt())
        })
        layout.addView(TextView(this).apply {
            text = report; textSize = 11f; setTextIsSelectable(true); setPadding(0, pad / 2, 0, pad / 2)
        })
        layout.addView(Button(this).apply {
            text = "คัดลอกข้อผิดพลาด"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("GameLingo crash", report))
                Toast.makeText(this@MainActivity, "คัดลอกแล้ว", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun lastAbnormalExit(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val bad = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED)
        // Look past later normal exits (e.g. reinstalling the app) to the most recent crash
        val info = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getHistoricalProcessExitReasons(packageName, 0, 10).firstOrNull { it.reason in bad } ?: return null
        // Each exit is reported once
        val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
        if (prefs.getLong("reportedExit", 0L) == info.timestamp) return null
        prefs.edit().putLong("reportedExit", info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            else -> "OTHER (${info.reason})"
        }
        val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(info.timestamp))
        return "Exit: $reason, status ${info.status}, $time\n${info.description ?: ""}"
    }

    // Lists the user's own corrections and recently shown translations; picking one lets the user
    // type the right translation, which the overlay then always uses for that text.
    private fun showCorrections() {
        val saved = Glossary.userEntries(this)
        val savedKeys = saved.map { "${it.source}>${it.target}|${it.text}" }.toSet()
        val recent = Glossary.recent(this).filter { "${it.source}>${it.target}|${Glossary.normalize(it.text)}" !in savedKeys }
        val entries = saved + recent
        val labels = listOf("+ เพิ่มคำแปลเอง") +
            saved.map { "✓ ${it.text} → ${it.translation}" } + recent.map { "${it.text} → ${it.translation}" }
        AlertDialog.Builder(this).setTitle("เลือกคำที่จะแก้")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) editCorrection(null) else editCorrection(entries[which - 1])
            }
            .setNegativeButton("ปิด", null).show()
    }

    private fun editCorrection(entry: Glossary.Entry?) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val original = EditText(this).apply { hint = "ข้อความในเกม"; setText(entry?.text ?: ""); isEnabled = entry == null }
        val translation = EditText(this).apply { hint = "คำแปลที่ถูกต้อง (เว้นว่าง = ลบ)"; setText(entry?.translation ?: "") }
        form.addView(original); form.addView(translation)
        val source = entry?.source ?: languages[sourceSpinner.selectedItemPosition].second
        val target = entry?.target ?: targets[targetSpinner.selectedItemPosition].second
        AlertDialog.Builder(this).setTitle(if (entry == null) "เพิ่มคำแปลเอง" else "แก้คำแปล").setView(form)
            .setPositiveButton("บันทึก") { _, _ ->
                val text = original.text.toString()
                if (text.isBlank()) return@setPositiveButton
                Glossary.setUserTranslation(this, source, target, text, translation.text.toString())
                Toast.makeText(this, "บันทึกแล้ว", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ยกเลิก", null).show()
    }

    private var packsLabel: TextView? = null

    // Imports a glossary file (same format as the bundled 9 Kings one) so a game's own terms are used
    private val pickGlossary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val json = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val pack = Glossary.importPack(this, json)
            packsLabel?.text = "พจนานุกรมที่ใช้อยู่: " + Glossary.packs(this).joinToString { "${it.name} (${it.entries.size} คำ)" }
            Toast.makeText(this, "นำเข้า ${pack.name} แล้ว ${pack.entries.size} คำ", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ไฟล์นี้ใช้ไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
