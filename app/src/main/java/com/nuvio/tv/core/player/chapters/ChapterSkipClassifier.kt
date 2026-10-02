package com.nuvio.tv.core.player.chapters

import com.nuvio.tv.data.repository.SkipInterval
import java.text.Normalizer
import java.util.Locale

/**
 * Turns embedded container chapters into [SkipInterval]s by recognising the usual chapter names
 * for intros, recaps, endings/credits and next-episode previews (English and romanised Japanese,
 * as used by anime fansub/BD releases).
 *
 * Only the start of a title is matched ("Opening", "OP - Song name", "Ending Credits"), so story
 * chapters such as "The Ending" or "Opening Night" are not mistaken for skippable segments.
 */
object ChapterSkipClassifier {

    const val PROVIDER = "chapters"

    const val TYPE_INTRO = "intro"
    const val TYPE_RECAP = "recap"
    const val TYPE_OUTRO = "outro"
    const val TYPE_MOVIE_CREDITS = "movie-credits"
    const val TYPE_PREVIEW = "preview"

    private const val MAX_INTRO_MS = 5 * 60_000L
    private const val MAX_RECAP_MS = 6 * 60_000L
    private const val MAX_PREVIEW_MS = 3 * 60_000L
    private const val MAX_OUTRO_MS = 15 * 60_000L
    private const val MIN_SEGMENT_MS = 3_000L

    private enum class Kind { INTRO, RECAP, OUTRO, PREVIEW }

    // Leading numbering such as "01 ", "1. ", "chapter 3 - ", "ch 2: ".
    private val leadingNumbering = Regex("^(?:(?:chapter|chap|ch)\\s*)?\\d{1,3}\\s*[-.:)]?\\s+")

    private val introPattern = Regex(
        "^(?:op\\d*|opening(?:\\s+(?:theme|song|credits|titles?|sequence))?|" +
            "intro(?:\\s+(?:song|theme|credits|sequence))?|" +
            "title\\s+sequence|main\\s+titles?|theme\\s+song|" +
            "o{1,2}p[uū]ningu|opuningu|oupuningu)\\b"
    )
    private val recapPattern = Regex(
        "^(?:recap|previously(?:\\s+on)?|last\\s+time|" +
            "zenkai(?:\\s+no\\s+arasuji)?|arasuji|matome)\\b"
    )
    private val outroPattern = Regex(
        "^(?:ed\\d*|ending(?:\\s+(?:theme|song|credits|titles?|sequence))?|" +
            "outro(?:\\s+(?:song|theme|credits))?|" +
            "(?:end|closing|ending)\\s+credits|credits|staff\\s+roll|" +
            "e{1,2}ndingu|endingu)\\b"
    )
    private val previewPattern = Regex(
        "^(?:preview|(?:episode|ep)\\s+preview|next\\s+(?:episode|ep|time)(?:\\s+preview)?|next\\s+on|" +
            "jikai(?:\\s+yokoku)?|yokoku)\\b"
    )

    /**
     * @param chapters Chapters in any order.
     * @param durationMs Media duration, used as the end of a final chapter with no declared end.
     *   Pass 0 or a negative value when unknown.
     * @param isMovie Whether the content is a movie; ending/credits chapters then map to
     *   [TYPE_MOVIE_CREDITS] instead of [TYPE_OUTRO].
     */
    fun toSkipIntervals(
        chapters: List<EmbeddedChapter>,
        durationMs: Long,
        isMovie: Boolean
    ): List<SkipInterval> {
        if (chapters.isEmpty()) return emptyList()
        val sorted = chapters
            .filter { it.startMs >= 0L }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
        val result = ArrayList<SkipInterval>()
        sorted.forEachIndexed { index, chapter ->
            val kind = classify(chapter.title) ?: return@forEachIndexed
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            val endMs = chapter.endMs?.takeIf { it > chapter.startMs }
                ?: nextStart
                ?: durationMs.takeIf { it > chapter.startMs }
                ?: return@forEachIndexed
            val lengthMs = endMs - chapter.startMs
            val maxMs = when (kind) {
                Kind.INTRO -> MAX_INTRO_MS
                Kind.RECAP -> MAX_RECAP_MS
                Kind.PREVIEW -> MAX_PREVIEW_MS
                Kind.OUTRO -> MAX_OUTRO_MS
            }
            if (lengthMs < MIN_SEGMENT_MS || lengthMs > maxMs) return@forEachIndexed
            val type = when (kind) {
                Kind.INTRO -> TYPE_INTRO
                Kind.RECAP -> TYPE_RECAP
                Kind.PREVIEW -> TYPE_PREVIEW
                Kind.OUTRO -> if (isMovie) TYPE_MOVIE_CREDITS else TYPE_OUTRO
            }
            result += SkipInterval(
                startTime = chapter.startMs / 1000.0,
                endTime = endMs / 1000.0,
                type = type,
                provider = PROVIDER
            )
        }
        return mergeAdjacent(result)
    }

    /** Joins back-to-back segments of the same type (e.g. "Ending" followed by "Credits"). */
    private fun mergeAdjacent(intervals: List<SkipInterval>): List<SkipInterval> {
        if (intervals.size < 2) return intervals
        val merged = ArrayList<SkipInterval>(intervals.size)
        for (interval in intervals) {
            val last = merged.lastOrNull()
            if (last != null && last.type == interval.type &&
                interval.startTime - last.endTime <= 1.0
            ) {
                merged[merged.lastIndex] = last.copy(endTime = maxOf(last.endTime, interval.endTime))
            } else {
                merged += interval
            }
        }
        return merged
    }

    // Japanese titles in kana/kanji, matched on the raw title (NFKD would split the dakuten).
    private val kanaIntro = Regex("^\\s*(?:オープニング|ＯＰ|OP)(?![A-Za-z])")
    private val kanaRecap = Regex("^\\s*(?:前回のあらすじ|あらすじ|前回)")
    private val kanaOutro = Regex("^\\s*(?:エンディング|ＥＤ|スタッフロール)")
    private val kanaPreview = Regex("^\\s*(?:次回予告|予告)")

    private fun classify(rawTitle: String?): Kind? {
        if (rawTitle == null) return null
        when {
            kanaIntro.containsMatchIn(rawTitle) -> return Kind.INTRO
            kanaRecap.containsMatchIn(rawTitle) -> return Kind.RECAP
            kanaOutro.containsMatchIn(rawTitle) -> return Kind.OUTRO
            kanaPreview.containsMatchIn(rawTitle) -> return Kind.PREVIEW
        }
        val title = normalize(rawTitle)
        if (title.isEmpty()) return null
        return when {
            introPattern.containsMatchIn(title) -> Kind.INTRO
            recapPattern.containsMatchIn(title) -> Kind.RECAP
            outroPattern.containsMatchIn(title) -> Kind.OUTRO
            previewPattern.containsMatchIn(title) -> Kind.PREVIEW
            else -> null
        }
    }

    internal fun normalize(title: String): String {
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
        return decomposed
            .replace(Regex("[\\[\\](){}「」『』【】\"'“”‘’_~|/\\\\]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(leadingNumbering, "")
            .trim()
    }
}
