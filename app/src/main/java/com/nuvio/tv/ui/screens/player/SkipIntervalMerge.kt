package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.AutoSkipSegmentType
import com.nuvio.tv.data.repository.SkipInterval

internal object SkipIntervalMerge {

    /**
     * Chapter intervals win; a remote interval (IntroDB, AniSkip, AnimeSkip) is kept only when the
     * chapters have nothing of its kind (intro, recap, outro, movie credits). Types the auto-skip
     * does not know (post-credits scenes, previews) never suppress anything.
     */
    fun merge(chapters: List<SkipInterval>, remote: List<SkipInterval>): List<SkipInterval> {
        if (chapters.isEmpty()) return remote
        val covered = chapters.mapNotNull { AutoSkipSegmentType.fromSkipIntervalType(it.type) }.toSet()
        val kept = remote.filter { interval ->
            val kind = AutoSkipSegmentType.fromSkipIntervalType(interval.type)
            kind == null || kind !in covered
        }
        return (chapters + kept).sortedBy { it.startTime }
    }
}
