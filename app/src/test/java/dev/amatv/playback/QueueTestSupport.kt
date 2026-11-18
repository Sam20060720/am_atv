package dev.amatv.playback

import dev.amatv.integration.AutoplaySource
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.integration.QueuePreferences
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

internal class TestQueuePreferences(
    override var repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off,
    override var shuffleEnabled: Boolean = false,
    override var autoplayEnabled: Boolean = false,
    override var autoplaySource: AutoplaySource = AutoplaySource.AppleMusic,
    override var lowPerformanceMode: Boolean = false,
) : QueuePreferences

internal fun queueTestSong(
    id: String,
    title: String = id,
    artist: String = "Artist",
    album: String = "Album",
    durationMs: Long = 180_000L,
): Track = Track(id, title, artist, album, durationMs, emptyList(), emptyList(),
    catalogId = id, catalogKind = CatalogKind.Song)
