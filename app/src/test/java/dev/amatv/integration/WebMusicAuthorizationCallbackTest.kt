package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WebMusicAuthorizationCallbackTest {
    private val token = "fixture-user-token-" + "T".repeat(64)

    @Test fun bothFieldNamesSelectTheValueWithoutResettingIt() {
        assertEquals(token, webMusicAuthorizationCallback("music-user-token=$token"))
        assertEquals(token, webMusicAuthorizationCallback("other=ignored&music-user-token=$token&state=unused"))
        assertEquals(token, webMusicAuthorizationCallback("musicUserToken=$token&state=unused"))
    }

    @Test fun rawAndNamedCallbackEnvelopesDecodeToInnerToken() {
        val envelope = Base64.getEncoder().encodeToString("""{"musicUserToken":"$token"}""".toByteArray())
        assertEquals(token, webMusicAuthorizationCallback(envelope))
        assertEquals(token, webMusicAuthorizationCallback("music-user-token=$envelope"))
        assertNull(webMusicAuthorizationCallback("music-user-token=short"))
        assertNull(webMusicAuthorizationCallback("state=ignored&other=ignored"))
    }
}
