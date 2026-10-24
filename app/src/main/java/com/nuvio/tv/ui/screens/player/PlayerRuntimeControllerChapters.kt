package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** mpv lists the chapters itself; they come with the file, so once read for a stream they are kept. */
internal fun PlayerRuntimeController.refreshMpvChapters(view: NuvioMpvSurfaceView) {
    if (mpvChaptersStreamUrl == currentStreamUrl && _uiState.value.chapters.isNotEmpty()) return
    val chapters = PlayerChapters.normalize(view.readChapters())
    if (chapters.isEmpty()) return
    mpvChaptersStreamUrl = currentStreamUrl
    _uiState.update { it.copy(chapters = chapters) }
    updateChapterSkipIntervals(chapters)
}

/**
 * Skip intervals read from the chapter titles; empty when the setting is off or the file names none.
 * [chapters] are remembered so toggling the setting mid-playback can recompute them.
 */
internal fun PlayerRuntimeController.updateChapterSkipIntervals(chapters: List<PlayerChapter> = fileChapters) {
    fileChapters = chapters
    chapterSkipIntervals = if (chaptersForSkipEnabled && skipIntroEnabled) {
        ChapterSkipClassifier.toSkipIntervals(
            chapters = chapters,
            durationMs = currentPlaybackDurationMs(),
            isMovie = contentType.equals("movie", ignoreCase = true)
        )
    } else {
        emptyList()
    }
}

/**
 * Exact on both engines: a keyframe seek can land before the chapter start, and "next" would then
 * keep returning to the same chapter. On mpv, setting the position seeks to a keyframe when the
 * file was loaded with `hr-seek=no` (the Hi10P software fallback), so chapters ask for an exact seek.
 */
internal fun PlayerRuntimeController.seekToChapter(startMs: Long) {
    if (_playbackTimeline.value.isLive) return
    pendingPreviewSeekPosition = null
    seekPlaybackTo(startMs, SeekParameters.EXACT, exact = true)
    updatePlaybackTimeline(currentPosition = startMs)
    scheduleProgressSyncAfterSeek()
    if (_uiState.value.showControls) {
        showControlsTemporarily()
    } else {
        showSeekOverlayTemporarily()
    }
}

internal fun PlayerRuntimeController.showChaptersPanel() {
    if (_uiState.value.chapters.isEmpty()) return
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showChaptersPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }
}

internal fun PlayerRuntimeController.dismissChaptersPanel() {
    _uiState.update { it.copy(showChaptersPanel = false) }
    scheduleHideControls()
}

/**
 * ExoPlayer does not read Matroska chapters, so they are read from the file once its first frame
 * is shown (on the first READY when tunneled, which never reports a rendered frame), when the
 * extra requests no longer compete with starting playback.
 */
internal fun PlayerRuntimeController.loadExoChapters() {
    exoChapterLoadJob?.cancel()
    if (isUsingMpvEngine() || _playbackTimeline.value.isLive) return
    val url = currentStreamUrl
    val headers = currentHeaders
    exoChapterLoadJob = scope.launch {
        val settings = playerSettingsDataStore.playerSettings.first()
        val usedForSkip = settings.chaptersForSkipEnabled && settings.skipIntroEnabled
        if (!settings.exoChaptersEnabled && !usedForSkip) return@launch
        val chapters = MatroskaChapterLoader.load(url, headers)
        if (chapters.isEmpty() || isUsingMpvEngine() || currentStreamUrl != url) return@launch
        // The Chapters button and progress-bar marks keep following their own toggle; the skip
        // buttons only need the list.
        if (settings.exoChaptersEnabled) _uiState.update { it.copy(chapters = chapters) }
        updateChapterSkipIntervals(chapters)
    }
}
