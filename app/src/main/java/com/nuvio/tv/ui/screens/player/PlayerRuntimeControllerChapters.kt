package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import com.nuvio.tv.core.player.chapters.ChapterSkipClassifier
import com.nuvio.tv.core.player.chapters.EmbeddedChapter
import com.nuvio.tv.core.player.chapters.EmbeddedChapterListener
import com.nuvio.tv.core.player.chapters.Mp4ChapterProbe
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Skip intro/outro driven by the chapters embedded in the media file (Matroska Chapters, MP4
 * Nero/QuickTime chapters, or mpv's chapter-list). This replaces the external timestamp
 * databases (IntroDB / AniSkip / AnimeSkip) as the source of [PlayerRuntimeController.skipIntervals].
 */

private const val CHAPTER_PROBE_TIMEOUT_MS = 20_000L

/**
 * Starts a new chapter session for a player (re)initialisation and returns the listener the
 * ExoPlayer Matroska extractor reports to. Chapters from earlier sessions are ignored.
 */
internal fun PlayerRuntimeController.beginEmbeddedChapterSession(): EmbeddedChapterListener {
    val generation = ++embeddedChapterGeneration
    embeddedChapters = emptyList()
    chapterSkipNeedsDuration = false
    skipIntervals = emptyList()
    return EmbeddedChapterListener { chapters ->
        scope.launch {
            if (generation == embeddedChapterGeneration) onEmbeddedChaptersParsed(chapters, "mkv")
        }
    }
}

/**
 * MP4/MOV chapters: the forked Media3 1.8 Mp4Extractor does not parse them, so they are read
 * directly from the container with range requests.
 */
internal fun PlayerRuntimeController.maybeProbeMp4Chapters(url: String, headers: Map<String, String>) {
    if (!skipIntroEnabled) return
    if (!isMp4ChapterProbeCandidate(url)) return
    val generation = embeddedChapterGeneration
    scope.launch {
        val chapters = withContext(Dispatchers.IO) {
            withTimeoutOrNull(CHAPTER_PROBE_TIMEOUT_MS) {
                val uri = Uri.parse(url)
                when (uri.scheme?.lowercase(Locale.ROOT)) {
                    "file" -> uri.path?.let { Mp4ChapterProbe.probeFile(File(it)) }
                    null, "" -> Mp4ChapterProbe.probeFile(File(url))
                    else -> Mp4ChapterProbe.probeHttp(
                        PlayerPlaybackNetworking.createHttpClient(headers),
                        url,
                        headers
                    )
                }
            }
        }.orEmpty()
        if (generation == embeddedChapterGeneration && chapters.isNotEmpty()) {
            onEmbeddedChaptersParsed(chapters, "mp4")
        }
    }
}

/** Reads mpv's chapter-list once the file is loaded (mpv parses MKV and MP4 chapters itself). */
internal fun PlayerRuntimeController.loadMpvChapters() {
    val view = mpvView ?: return
    val generation = embeddedChapterGeneration
    val chapters = runCatching { view.readChapters() }.getOrDefault(emptyList())
    if (generation == embeddedChapterGeneration && chapters.isNotEmpty()) {
        onEmbeddedChaptersParsed(chapters, "mpv")
    }
}

internal fun PlayerRuntimeController.onEmbeddedChaptersParsed(
    chapters: List<EmbeddedChapter>,
    source: String
) {
    if (chapters.isEmpty()) return
    embeddedChapters = chapters
    Log.d(PlayerRuntimeController.TAG, "Embedded chapters ($source): ${chapters.size}")
    refreshChapterSkipIntervals()
}

/** Rebuilds [PlayerRuntimeController.skipIntervals] from the current embedded chapters. */
internal fun PlayerRuntimeController.refreshChapterSkipIntervals() {
    if (!skipIntroEnabled || embeddedChapters.isEmpty()) {
        skipIntervals = emptyList()
        chapterSkipNeedsDuration = false
        return
    }
    val durationMs = currentPlaybackDurationMs().takeIf { it > 0L } ?: 0L
    // The last chapter may have no declared end; it then needs the media duration.
    chapterSkipNeedsDuration = durationMs <= 0L && embeddedChapters.any { it.endMs == null }
    skipIntervals = ChapterSkipClassifier.toSkipIntervals(
        chapters = embeddedChapters,
        durationMs = durationMs,
        isMovie = contentType.equals("movie", ignoreCase = true)
    )
}

/** Called from the position poll: completes chapters that were waiting for the duration. */
internal fun PlayerRuntimeController.maybeRefreshChapterSkipIntervalsForDuration() {
    if (!chapterSkipNeedsDuration) return
    if (currentPlaybackDurationMs() <= 0L) return
    refreshChapterSkipIntervals()
}

private fun isMp4ChapterProbeCandidate(url: String): Boolean {
    val lower = url.lowercase(Locale.ROOT)
    if (lower.startsWith("content:") || lower.startsWith("rtsp:") || lower.startsWith("rtmp:")) return false
    val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase(Locale.ROOT) ?: lower
    val skippedExtensions = listOf(".mkv", ".mka", ".mk3d", ".webm", ".m3u8", ".mpd", ".ism", ".ts", ".m2ts", ".avi")
    return skippedExtensions.none { path.endsWith(it) }
}
