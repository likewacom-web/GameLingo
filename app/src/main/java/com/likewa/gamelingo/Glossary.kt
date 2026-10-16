package com.likewa.gamelingo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Fixed translations that take priority over the on-device translator, which translates word by word
// without knowing it's a game (e.g. "auto" -> "รถยนต์"). Two layers: the user's own corrections, then a
// built-in list of common English game terms for English -> Thai.
object Glossary {
    private const val USER = "glossary"
    private const val RECENT = "recent_translations"
    private const val RECENT_LIMIT = 60

    private val builtInEnTh = mapOf(
        "auto" to "อัตโนมัติ", "skip" to "ข้าม", "start" to "เริ่ม", "continue" to "เล่นต่อ", "resume" to "เล่นต่อ",
        "pause" to "หยุดชั่วคราว", "paused" to "หยุดชั่วคราว", "settings" to "ตั้งค่า", "options" to "ตัวเลือก",
        "menu" to "เมนู", "back" to "กลับ", "exit" to "ออก", "quit" to "ออก", "confirm" to "ยืนยัน",
        "cancel" to "ยกเลิก", "ok" to "ตกลง", "yes" to "ใช่", "no" to "ไม่", "close" to "ปิด", "save" to "บันทึก",
        "load" to "โหลด", "new game" to "เกมใหม่", "play" to "เล่น", "retry" to "ลองใหม่", "restart" to "เริ่มใหม่",
        "next" to "ถัดไป", "previous" to "ก่อนหน้า", "loading" to "กำลังโหลด", "tutorial" to "บทแนะนำ",
        "level" to "เลเวล", "level up" to "เลเวลอัป", "upgrade" to "อัปเกรด", "gold" to "ทอง", "coins" to "เหรียญ",
        "gems" to "เพชร", "shop" to "ร้านค้า", "store" to "ร้านค้า", "buy" to "ซื้อ", "sell" to "ขาย",
        "equip" to "สวมใส่", "unequip" to "ถอด", "inventory" to "กระเป๋า", "item" to "ไอเทม", "items" to "ไอเทม",
        "quest" to "ภารกิจ", "quests" to "ภารกิจ", "mission" to "ภารกิจ", "missions" to "ภารกิจ",
        "reward" to "รางวัล", "rewards" to "รางวัล", "claim" to "รับ", "collect" to "เก็บ",
        "attack" to "โจมตี", "defense" to "ป้องกัน", "defence" to "ป้องกัน", "health" to "พลังชีวิต",
        "mana" to "มานา", "skill" to "สกิล", "skills" to "สกิล", "damage" to "ความเสียหาย",
        "critical" to "คริติคอล", "victory" to "ชนะ", "defeat" to "แพ้", "you win" to "คุณชนะ",
        "you lose" to "คุณแพ้", "game over" to "จบเกม", "wave" to "เวฟ", "stage" to "ด่าน", "boss" to "บอส",
        "enemy" to "ศัตรู", "enemies" to "ศัตรู", "hero" to "ฮีโร่", "heroes" to "ฮีโร่", "card" to "การ์ด",
        "cards" to "การ์ด", "deck" to "เด็ค", "wildcard" to "ไวลด์การ์ด", "speed" to "ความเร็ว",
        "fast forward" to "เร่งความเร็ว", "achievements" to "ความสำเร็จ", "leaderboard" to "อันดับ",
        "daily" to "รายวัน", "event" to "อีเวนต์", "events" to "อีเวนต์", "summon" to "อัญเชิญ",
        "energy" to "พลังงาน", "stamina" to "สตามินา", "year" to "ปี", "day" to "วัน", "turn" to "เทิร์น",
        "round" to "รอบ", "score" to "คะแนน", "best" to "ดีที่สุด", "total" to "รวม", "free" to "ฟรี",
        "locked" to "ล็อก", "unlock" to "ปลดล็อก", "unlocked" to "ปลดล็อกแล้ว", "ready" to "พร้อม",
        "select" to "เลือก", "build" to "สร้าง", "map" to "แผนที่", "friends" to "เพื่อน", "mail" to "จดหมาย",
        "chat" to "แชท", "team" to "ทีม", "party" to "ปาร์ตี้", "guild" to "กิลด์", "battle" to "ต่อสู้",
        "fight" to "สู้", "arena" to "อารีนา", "rank" to "แรงก์", "ranking" to "อันดับ", "random" to "สุ่ม",
    )

    fun normalize(text: String) = text.trim().trimEnd(':', '.', '!').replace(Regex("\\s+"), " ").lowercase()

    // User correction first, then the built-in list (English -> Thai only)
    fun lookup(context: Context, text: String, source: String, target: String): String? {
        val key = normalize(text)
        context.getSharedPreferences(USER, Context.MODE_PRIVATE).getString("$source>$target|$key", null)?.let { return it }
        return if (source == "en" && target == "th") builtInEnTh[key] else null
    }

    fun setUserTranslation(context: Context, source: String, target: String, text: String, translation: String) {
        val key = "$source>$target|${normalize(text)}"
        context.getSharedPreferences(USER, Context.MODE_PRIVATE).edit().apply {
            if (translation.isBlank()) remove(key) else putString(key, translation.trim())
        }.apply()
    }

    class Entry(val source: String, val target: String, val text: String, val translation: String)

    fun userEntries(context: Context): List<Entry> =
        context.getSharedPreferences(USER, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            val langs = key.substringBefore('|').split('>')
            if (langs.size != 2 || value !is String) null else Entry(langs[0], langs[1], key.substringAfter('|'), value)
        }.sortedBy { it.text }

    // Recently shown translations, newest first, so wrong ones can be picked and corrected in the app
    fun remember(context: Context, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val prefs = context.getSharedPreferences(RECENT, Context.MODE_PRIVATE)
        val merged = LinkedHashMap<String, Entry>()
        entries.forEach { merged["${it.source}>${it.target}|${normalize(it.text)}"] = it }
        recent(context).forEach { merged.putIfAbsent("${it.source}>${it.target}|${normalize(it.text)}", it) }
        val json = JSONArray()
        merged.values.take(RECENT_LIMIT).forEach {
            json.put(JSONObject().put("s", it.source).put("t", it.target).put("text", it.text).put("tr", it.translation))
        }
        prefs.edit().putString("items", json.toString()).apply()
    }

    fun recent(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(RECENT, Context.MODE_PRIVATE).getString("items", null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            (0 until json.length()).map { json.getJSONObject(it) }
                .map { Entry(it.getString("s"), it.getString("t"), it.getString("text"), it.getString("tr")) }
        } catch (_: Exception) { emptyList() }
    }
}
