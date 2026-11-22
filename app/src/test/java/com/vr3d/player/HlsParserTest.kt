package com.vr3d.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {

    private val master = """
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="es",DEFAULT=YES,URI="audio/es.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
v360/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
https://cdn.example.com/v1080/index.m3u8
"""

    @Test
    fun masterVariantsSortedBestFirst() {
        val m = HlsParser.parseMaster(master, "https://example.com/video/master.m3u8")
        assertEquals(2, m.variants.size)
        assertEquals(5_000_000L, m.variants[0].bandwidth)
        assertEquals(1080, m.variants[0].height)
        assertEquals("https://cdn.example.com/v1080/index.m3u8", m.variants[0].url)
        assertEquals("https://example.com/video/v360/index.m3u8", m.variants[1].url)
        assertEquals("aud", m.variants[1].audioGroup)
        assertNull(m.variants[0].audioGroup)
        assertEquals("https://example.com/video/audio/es.m3u8", m.audioGroups["aud"])
    }

    @Test
    fun mediaPlaylist() {
        val t = """
#EXTM3U
#EXT-X-MAP:URI="init.mp4"
#EXTINF:6.0,
seg1.m4s
#EXTINF:4.5,
seg2.m4s
#EXT-X-ENDLIST
"""
        val m = HlsParser.parseMedia(t, "https://example.com/a/b.m3u8")
        assertEquals(2, m.segments.size)
        assertEquals("https://example.com/a/seg1.m4s", m.segments[0])
        assertEquals("https://example.com/a/init.mp4", m.initUrl)
        assertFalse(m.live)
        assertFalse(m.encrypted)
        assertEquals(10.5, m.durationSec, 0.001)
    }

    @Test
    fun liveAndEncryptedAreDetected() {
        val t = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n#EXTINF:6,\ns1.ts\n"
        val m = HlsParser.parseMedia(t, "https://e.com/x.m3u8")
        assertTrue(m.live)
        assertTrue(m.encrypted)
    }

    @Test
    fun detectsMaster() {
        assertTrue(HlsParser.isMaster(master))
        assertFalse(HlsParser.isMaster("#EXTM3U\n#EXTINF:6,\ns.ts\n"))
    }
}
