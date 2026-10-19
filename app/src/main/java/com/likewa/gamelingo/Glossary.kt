package com.likewa.gamelingo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Fixed translations that take priority over the on-device translator, which translates word by word
// without knowing it's a game (e.g. "auto" -> "รถยนต์"). Three layers, in priority order: the user's own
// corrections, glossary packs (JSON files bundled in assets/glossaries or imported by the user, e.g. one
// per game), then a built-in list of common English game terms for English -> Thai.
object Glossary {
    // A glossary pack file: {"name", "sourceLanguage", "targetLanguage", "entries": {"text": "translation"}}
    class Pack(val name: String, val source: String, val target: String, val entries: Map<String, String>)

    private var packs: List<Pack>? = null
    private val termPatterns = HashMap<String, Regex?>()

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

    // Whole-text match: user correction, then glossary packs, then the built-in list (English -> Thai only)
    fun lookup(context: Context, text: String, source: String, target: String): String? {
        val key = normalize(text)
        context.getSharedPreferences(USER, Context.MODE_PRIVATE).getString("$source>$target|$key", null)?.let { return it }
        packs(context).forEach { pack -> if (pack.source == source && pack.target == target) pack.entries[key]?.let { return it } }
        if (source == "en" && target == "th") builtInEnTh[key]?.let { return it }
        return fuzzyLookup(context, text, source, target)
    }

    private val fuzzyCache = HashMap<String, String?>()

    // Letters and digits only, lower case, with the usual pixel-font OCR confusions undone
    private fun compact(text: String) = text.lowercase().replace('0', 'o').replace('|', 'l').filter { it.isLetterOrDigit() }

    // OCR on pixel fonts often gets a letter wrong or drops a space ("CHOOSE A KINC", "CHOOSEA BING",
    // "BING OF NATURE"). With no exact match, take the closest fixed translation that is at most about one
    // wrong letter in five away. Short texts are left out, where one letter changes the meaning.
    private fun fuzzyLookup(context: Context, text: String, source: String, target: String): String? {
        val wanted = compact(text)
        if (wanted.length < 6) return null
        val cacheKey = "$source>$target|$wanted"
        if (fuzzyCache.containsKey(cacheKey)) return fuzzyCache[cacheKey]
        val maxDistance = wanted.length / 5
        val candidates = ArrayList<Pair<String, String>>()
        context.getSharedPreferences(USER, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (key.startsWith("$source>$target|") && value is String) candidates += key.substringAfter('|') to value
        }
        packs(context).filter { it.source == source && it.target == target }.forEach { candidates += it.entries.toList() }
        if (source == "en" && target == "th") candidates += builtInEnTh.toList()
        var best: String? = null; var bestDistance = maxDistance + 1
        for ((key, translation) in candidates) {
            val candidate = compact(key)
            if (kotlin.math.abs(candidate.length - wanted.length) >= bestDistance) continue
            val distance = editDistance(wanted, candidate, bestDistance)
            if (distance < bestDistance) { best = translation; bestDistance = distance }
        }
        if (fuzzyCache.size > 1000) fuzzyCache.clear()
        fuzzyCache[cacheKey] = best
        return best
    }

    // Levenshtein distance, giving up (returning limit) once every path is already at the limit
    private fun editDistance(a: String, b: String, limit: Int): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin >= limit) return limit
            previous = current
        }
        return previous[b.length]
    }

    // Swaps glossary-pack terms found inside a longer sentence for their translations before the sentence
    // goes to the translator, which passes text already in the target script through unchanged. Longest
    // terms win ("Adjacent plots" before "Plot"). The built-in list is left out: its short everyday words
    // ("no", "free", "day") would wrongly replace parts of ordinary sentences.
    fun substituteTerms(context: Context, text: String, source: String, target: String): String {
        val terms = packs(context).filter { it.source == source && it.target == target }
            .flatMap { it.entries.entries }.associate { it.key to it.value }
        if (terms.isEmpty()) return text
        val pattern = termPatterns.getOrPut("$source>$target") {
            val alternatives = terms.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            Regex("(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        } ?: return text
        return pattern.replace(text) { match -> terms[normalize(match.value)] ?: match.value }
    }

    fun packs(context: Context): List<Pack> = packs ?: loadPacks(context).also { packs = it }

    private fun loadPacks(context: Context): List<Pack> {
        val bundled = (context.assets.list("glossaries") ?: emptyArray()).mapNotNull { file ->
            try { context.assets.open("glossaries/$file").bufferedReader().use { parsePack(it.readText()) } } catch (_: Exception) { null }
        }
        val imported = (importDir(context).listFiles() ?: emptyArray()).mapNotNull { file ->
            try { parsePack(file.readText()) } catch (_: Exception) { null }
        }
        return imported + bundled
    }

    private fun parsePack(json: String): Pack {
        val root = JSONObject(json)
        val entries = root.getJSONObject("entries")
        val map = HashMap<String, String>()
        entries.keys().forEach { key -> entries.optString(key).takeIf { it.isNotBlank() }?.let { map[normalize(key)] = it } }
        require(map.isNotEmpty()) { "no entries" }
        return Pack(root.optString("name", "Glossary"), root.optString("sourceLanguage", "en"), root.optString("targetLanguage", "th"), map)
    }

    private fun importDir(context: Context) = java.io.File(context.filesDir, "glossaries").apply { mkdirs() }

    // Saves a glossary pack chosen by the user; it then applies to every game session. Returns the pack.
    fun importPack(context: Context, json: String): Pack {
        val pack = parsePack(json)
        val fileName = pack.name.replace(Regex("[^A-Za-z0-9ก-๙_-]+"), "_").take(60) + ".json"
        java.io.File(importDir(context), fileName).writeText(json)
        packs = null; termPatterns.clear(); fuzzyCache.clear()
        return pack
    }

    fun setUserTranslation(context: Context, source: String, target: String, text: String, translation: String) {
        val key = "$source>$target|${normalize(text)}"
        context.getSharedPreferences(USER, Context.MODE_PRIVATE).edit().apply {
            if (translation.isBlank()) remove(key) else putString(key, translation.trim())
        }.apply()
        fuzzyCache.clear()
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
