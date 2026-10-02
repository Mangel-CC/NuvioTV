package com.nuvio.tv.core.player.chapters

/**
 * A chapter marker read from the media container itself (Matroska Chapters, MP4 Nero `chpl`
 * or QuickTime chapter track, or mpv's `chapter-list`).
 *
 * @property startMs Chapter start in milliseconds from the start of the media.
 * @property endMs Chapter end in milliseconds, or null when the container does not declare it
 *   (the next chapter's start, or the media duration, is used instead).
 * @property title Display title, if any.
 */
data class EmbeddedChapter(
    val startMs: Long,
    val endMs: Long?,
    val title: String?
)

/** Receives the chapters parsed from the container. Called from a loader thread. */
fun interface EmbeddedChapterListener {
    fun onChapters(chapters: List<EmbeddedChapter>)
}
