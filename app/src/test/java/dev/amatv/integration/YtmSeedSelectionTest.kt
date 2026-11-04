package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YtmSeedSelectionTest {
    private val client = YtmInnertubeClient(country = { "tw" })

    @Test fun skipsFirstSearchHitWhenItIsWrongArtistOrRecording() {
        val songs = listOf(
            YtmSong("wrongartist", "Song", "Cover Artist", 200_000L),
            YtmSong("liveversion", "Song (Live)", "Artist", 200_000L),
            YtmSong("rightrecord", "Song", "Artist", 201_000L),
        )
        assertEquals("rightrecord", client.selectSeedSong("Song", "Artist", 200_000L, songs)?.videoId)
    }

    @Test fun selectsNearestDurationAmongEquivalentMetadata() {
        val songs = listOf(
            YtmSong("longversion", "Café Song", "Artist", 209_000L),
            YtmSong("closesttake", "Cafe Song", "Artist", 200_000L),
        )
        assertEquals("closesttake", client.selectSeedSong("Café Song", "Artist", 200_000L, songs)?.videoId)
    }

    @Test fun doesNotSubstituteUnmatchedSeed() {
        assertNull(client.selectSeedSong("Song", "Artist", 200_000L,
            listOf(YtmSong("wronglength", "Song", "Artist", 240_000L))))
        assertNull(client.selectSeedSong("Song", "Artist", 200_000L,
            listOf(YtmSong("wrongtitle1", "Other", "Artist", 200_000L))))
    }
}
