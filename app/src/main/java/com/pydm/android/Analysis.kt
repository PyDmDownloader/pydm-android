package com.pydm.android

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Title Analysis (analysis.py ka port). */
data class AVideo(val id: String, val title: String, val views: Long?, val date: String?, val channel: String)

class ARow(val v: AVideo) {
    var score: Double? = null
    var viewsPerDay: Double? = null
    var tokens: List<String> = emptyList()
}

data class ChannelSummary(val channel: String, val videos: Int, val totalViews: Long, val avgViews: Double,
                          val medianViews: Double, val topTitle: String, val topViews: Long)
data class WordStat(val term: String, val videos: Int, val avgViews: Double, val medianViews: Double, val lift: Double)
data class PatternStat(val pattern: String, val videos: Int, val avgViews: Double, val lift: Double,
                       val withoutVideos: Int, val withoutLift: Double?, val reliable: Boolean)
class AnalysisResult(
    val summary: List<ChannelSummary>, val videos: List<ARow>, val words: List<WordStat>,
    val patterns: List<PatternStat>, val minCount: Int, val totalVideos: Int, val scoredVideos: Int,
    val insights: List<String>
)

object Analysis {
    private val STOPWORDS = ("a an the and or but if so of to in on at by for with from as into onto over under " +
        "about after before between through during up down out off than then too very just " +
        "is are was were be been being am do does did done have has had having " +
        "i me my we our you your he him his she her it its they them their this that these those " +
        "who whom whose which what when where why how not no nor can could will would shall should may might must " +
        "there here also more most some any each other such own same only s t d ll re ve m").split(" ").toSet()

    private val QUESTION_WORDS = setOf("how", "why", "what", "who", "when", "where", "which", "did", "does", "do",
        "is", "are", "was", "were", "can", "could", "would", "should", "will", "has", "have")

    private val CURIOSITY = setOf("secret", "secrets", "hidden", "forbidden", "lost", "erased", "banned", "truth",
        "untold", "dark", "mystery", "mysterious", "buried", "suppressed", "ancient", "never", "shocking", "real",
        "unknown", "disappeared", "vanished", "forgotten", "sealed", "cover", "covered", "classified", "terrifying",
        "strange", "unexplained", "legend", "cursed", "curse")

    private val TOKEN_RE = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)?")

    fun tokenize(title: String?): List<String> =
        TOKEN_RE.findAll(title ?: "").map { it.value.lowercase() }.toList()

    private fun isNumber(t: String) = t.isNotEmpty() && t.all { it.isDigit() }
    private fun termOk(t: String) = t.length >= 3 && t !in STOPWORDS && !isNumber(t)

    private fun grams(tokens: List<String>): Set<String> {
        val out = HashSet<String>()
        val n = tokens.size
        for (i in 0 until n) if (termOk(tokens[i])) out.add(tokens[i])
        for (size in 2..3) {
            for (i in 0..(n - size)) {
                if (i < 0) break
                val g = tokens.subList(i, i + size)
                if (g.first() in STOPWORDS || g.last() in STOPWORDS) continue
                if (g.any { isNumber(it) }) continue
                out.add(g.joinToString(" "))
            }
        }
        return out
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }
    private fun mean(v: List<Double>) = if (v.isEmpty()) 0.0 else v.sum() / v.size

    private fun ageDays(date: String?, today: LocalDate): Long? {
        if (date.isNullOrBlank()) return null
        return try { maxOf(1L, ChronoUnit.DAYS.between(LocalDate.parse(date), today)) } catch (_: Exception) { null }
    }

    private fun words(t: String) = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun pat(name: String, f: (String, List<String>) -> Boolean): Pair<String, (String, List<String>) -> Boolean> = name to f

    private val PATTERNS: List<Pair<String, (String, List<String>) -> Boolean>> = listOf(
        pat("Has a number") { t, _ -> Regex("\\d").containsMatchIn(t) },
        pat("Starts with a number") { t, _ -> Regex("^\\s*\\d").containsMatchIn(t) },
        pat("Has a year (1000-2099)") { t, _ -> Regex("\\b(1\\d{3}|20\\d{2})\\b").containsMatchIn(t) },
        pat("Question title") { t, tk -> "?" in t || (tk.isNotEmpty() && tk[0] in QUESTION_WORDS) },
        pat("Has colon / dash / pipe") { t, _ -> Regex(":|\\s[-–—|]\\s").containsMatchIn(t) },
        pat("Has an ALL-CAPS word") { t, _ ->
            Regex("\\p{L}+").findAll(t).any {
                it.value.length >= 2 && it.value == it.value.uppercase() && it.value != it.value.lowercase()
            }
        },
        pat("Has brackets ( ) [ ]") { t, _ -> Regex("[()\\[\\]]").containsMatchIn(t) },
        pat("Has quotes \" \"") { t, _ -> Regex("[\"“”]").containsMatchIn(t) },
        pat("Has exclamation !") { t, _ -> "!" in t },
        pat("Has ellipsis ...") { t, _ -> "..." in t || "…" in t },
        pat("Has a curiosity word (secret, hidden, erased...)") { _, tk -> tk.any { it in CURIOSITY } },
        pat("Short title (1-6 words)") { t, _ -> words(t).size <= 6 },
        pat("Medium title (7-10 words)") { t, _ -> words(t).size in 7..10 },
        pat("Long title (11-14 words)") { t, _ -> words(t).size in 11..14 },
        pat("Very long title (15+ words)") { t, _ -> words(t).size >= 15 },
    )

    fun analyze(videos: List<AVideo>, minCount: Int = 5, today: LocalDate = LocalDate.now()): AnalysisResult {
        val rows = videos.map { ARow(it) }
        val byChannel = LinkedHashMap<String, MutableList<Double>>()
        for (r in rows) r.v.views?.let { byChannel.getOrPut(r.v.channel) { ArrayList() }.add(it.toDouble()) }
        val chMedian = byChannel.mapValues { median(it.value) }
        for (r in rows) {
            val med = chMedian[r.v.channel]
            r.score = if (r.v.views != null && med != null && med > 0) r.v.views.toDouble() / med else null
            val age = ageDays(r.v.date, today)
            r.viewsPerDay = if (r.v.views != null && age != null) r.v.views.toDouble() / age else null
            r.tokens = tokenize(r.v.title)
        }
        val scored = rows.filter { it.score != null }

        val summary = byChannel.map { (c, vs) ->
            val top = rows.filter { it.v.channel == c && it.v.views != null }.maxByOrNull { it.v.views!! }!!
            ChannelSummary(c, vs.size, vs.sum().toLong(), mean(vs), median(vs), top.v.title, top.v.views!!)
        }.sortedByDescending { it.totalViews }

        // words / phrases
        val agg = HashMap<String, Pair<MutableList<Double>, MutableList<Double>>>()
        for (r in scored) for (g in grams(r.tokens)) {
            val a = agg.getOrPut(g) { ArrayList<Double>() to ArrayList<Double>() }
            a.first.add(r.v.views!!.toDouble()); a.second.add(r.score!!)
        }
        val qualified = agg.filter { it.value.first.size >= minCount }
        val subsumed = HashSet<String>()
        for ((g, a) in qualified) {
            val parts = g.split(" ")
            if (parts.size < 2) continue
            val subs = HashSet<String>()
            subs.add(parts.dropLast(1).joinToString(" ")); subs.add(parts.drop(1).joinToString(" ")); subs.addAll(parts)
            for (s in subs) if (s != g && qualified[s]?.first?.size == a.first.size) subsumed.add(s)
        }
        val wordStats = qualified.filter { it.key !in subsumed }.map { (g, a) ->
            WordStat(g, a.first.size, mean(a.first), median(a.first), median(a.second))
        }.sortedWith(compareBy<WordStat> { -it.lift }.thenBy { -it.videos }.thenBy { it.term })

        // patterns
        val patterns = ArrayList<PatternStat>()
        for ((name, pred) in PATTERNS) {
            val wS = ArrayList<Double>(); val wV = ArrayList<Double>(); val oS = ArrayList<Double>()
            for (r in scored) {
                if (pred(r.v.title, r.tokens)) { wS.add(r.score!!); wV.add(r.v.views!!.toDouble()) } else oS.add(r.score!!)
            }
            if (wS.isEmpty()) continue
            patterns.add(PatternStat(name, wS.size, mean(wV), median(wS), oS.size,
                if (oS.isEmpty()) null else median(oS), wS.size >= minCount && oS.size >= minCount))
        }
        patterns.sortByDescending { it.lift }

        val outliers = scored.sortedByDescending { it.score }
        val res = AnalysisResult(summary, outliers, wordStats, patterns, minCount, rows.size, scored.size, emptyList())
        return AnalysisResult(summary, outliers, wordStats, patterns, minCount, rows.size, scored.size, insights(res))
    }

    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    private fun insights(res: AnalysisResult): List<String> {
        if (res.scoredVideos == 0) return listOf("Views ka data nahi mila, isliye analysis nahi ho saka.")
        val out = ArrayList<String>()
        val good = res.words.filter { it.lift > 1 }.take(3)
        if (good.isNotEmpty()) out.add("Best words/phrases: " +
            good.joinToString(", ") { "\"${it.term}\" (${f1(it.lift)}x, ${it.videos} videos)" })
        val bad = res.words.reversed().filter { it.lift < 1 }.take(3)
        if (bad.isNotEmpty()) out.add("Weak words/phrases: " +
            bad.joinToString(", ") { "\"${it.term}\" (${f1(it.lift)}x, ${it.videos} videos)" })
        val rel = res.patterns.filter { it.reliable }
        if (rel.isNotEmpty()) {
            val b = rel.first()
            out.add("Best title pattern: \"${b.pattern}\" (${f1(b.lift)}x, ${b.videos} videos)")
            if (rel.size > 1 && rel.last().lift < 1) {
                val w = rel.last()
                out.add("Weakest title pattern: \"${w.pattern}\" (${f1(w.lift)}x, ${w.videos} videos)")
            }
        } else if (res.patterns.isNotEmpty()) {
            out.add("Patterns ke liye videos kam hain (har group mein kam az kam ${res.minCount} chahiye) - nateeje sirf ishara hain.")
        }
        if (good.isEmpty() && res.words.isEmpty())
            out.add("Koi lafz ${res.minCount} videos se zyada mein repeat nahi hua - min videos kam kar ke dekhein.")
        return out
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    fun toXlsx(res: AnalysisResult): ByteArray {
        val videos = Xlsx.Sheet("Videos",
            listOf("Rank", "Channel", "Title", "Views", "Upload date", "Views/day", "x channel median"),
            res.videos.mapIndexed { i, r ->
                listOf(i + 1, r.v.channel, r.v.title, r.v.views, r.v.date ?: "",
                    r.viewsPerDay?.let { Math.round(it * 10) / 10.0 }, r.score?.let { r2(it) })
            })
        val words = Xlsx.Sheet("Words",
            listOf("Word / phrase", "Videos", "Avg views", "Median views", "Lift (x channel median)"),
            res.words.map { listOf(it.term, it.videos, Math.round(it.avgViews), Math.round(it.medianViews), r2(it.lift)) })
        val pats = Xlsx.Sheet("Patterns",
            listOf("Pattern", "Videos with", "Avg views", "Lift with", "Videos without", "Lift without", "Reliable"),
            res.patterns.map {
                listOf(it.pattern, it.videos, Math.round(it.avgViews), r2(it.lift), it.withoutVideos,
                    it.withoutLift?.let { x -> r2(x) }, if (it.reliable) "yes" else "few videos")
            })
        return Xlsx.build(listOf(videos, words, pats))
    }
}
