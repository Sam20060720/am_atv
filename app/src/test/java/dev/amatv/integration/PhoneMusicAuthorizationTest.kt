package dev.amatv.integration

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URI

class PhoneMusicAuthorizationTest {
    private fun server() = PhoneMusicAuthorization("test-developer-token", "test", advertisedAuthority = "127.0.0.1:43210",
        bindAddress = InetAddress.getByName("127.0.0.1"))

    private fun exchange(server: PhoneMusicAuthorization, method: String, suffix: String = "", body: String? = null,
        origin: String = "http://127.0.0.1:43210", nonce: String? = null, host: String = "127.0.0.1:43210", length: Int? = null): String {
        val path = URI(server.pairing.url).path
        val bytes = body?.toByteArray(Charsets.UTF_8)
        return Socket("127.0.0.1", server.pairing.port).use { client ->
            client.soTimeout = 2_000
            val headers = buildString {
                append("$method $path$suffix HTTP/1.1\r\nHost: $host\r\n")
                if (body != null) {
                    append("Origin: $origin\r\nContent-Type: text/plain\r\n")
                    append("X-AMATV-Pairing: ${nonce ?: path.trimEnd('/').substringAfterLast('/')}\r\n")
                    append("Content-Length: ${length ?: bytes!!.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); if (bytes != null) write(bytes); flush() }
            val input = client.getInputStream()
            val responseHeaders = StringBuilder()
            var tail = ""
            while (!tail.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0) { "Response ended before HTTP headers" }
                responseHeaders.append(byte.toChar())
                check(responseHeaders.length <= 8_192)
                tail = (tail + byte.toChar()).takeLast(4)
            }
            val contentLength = Regex("(?im)^Content-Length: ([0-9]+)\r?$", RegexOption.MULTILINE)
                .find(responseHeaders)?.groupValues?.get(1)?.toInt() ?: error("Missing response length")
            val responseBody = ByteArray(contentLength)
            var received = 0
            while (received < contentLength) {
                val count = input.read(responseBody, received, contentLength - received)
                check(count > 0) { "Response body was incomplete" }
                received += count
            }
            responseHeaders.toString() + responseBody.toString(Charsets.UTF_8)
        }
    }

    @Test fun servesPhonePageAndMarksConnected() {
        server().use { server ->
            val result = exchange(server, "GET")
            assertTrue(result.startsWith("HTTP/1.1 200"))
            assertTrue(result.contains("MusicKit.configure"))
            assertTrue(result.contains("music.authorize()"))
            assertFalse(result.contains("AndroidMusicAuth"))
            assertEquals(PhoneMusicAuthStatus.PhoneConnected, server.status.value)
        }
    }

    @Test fun acceptsBoundOneTimeResultOnly() = runBlocking {
        server().use { server ->
            val fakeToken = "T".repeat(80)
            assertTrue(exchange(server, "POST", "token", fakeToken).startsWith("HTTP/1.1 200"))
            assertEquals(fakeToken, withTimeout(1_000L) { server.awaitToken() })
            assertEquals(PhoneMusicAuthStatus.Authorized, server.status.value)
            assertTrue(exchange(server, "POST", "token", "X".repeat(80)).startsWith("HTTP/1.1 410"))
        }
    }

    @Test fun rejectsForeignOriginAndWrongNonceWithoutCompleting() {
        server().use { server ->
            assertTrue(exchange(server, "POST", "token", "T".repeat(80), origin = "https://example.com").startsWith("HTTP/1.1 403"))
            assertTrue(exchange(server, "POST", "token", "T".repeat(80), nonce = "other-session").startsWith("HTTP/1.1 403"))
            assertEquals(PhoneMusicAuthStatus.Waiting, server.status.value)
        }
    }

    @Test fun rejectsOversizedDeclaredBodyWithoutWaitingForIt() {
        server().use { server ->
            assertTrue(exchange(server, "POST", "token", "T".repeat(80), length = 8_193).startsWith("HTTP/1.1 413"))
            assertEquals(PhoneMusicAuthStatus.Waiting, server.status.value)
        }
    }

    @Test fun rejectsUnknownHostAndTerminatesExplicitly() {
        val server = server()
        assertTrue(exchange(server, "GET", host = "attacker.invalid:43210").startsWith("HTTP/1.1 404"))
        server.close()
        assertEquals(PhoneMusicAuthStatus.Closed, server.status.value)
        server.close()
    }

    @Test fun tokenValidationRejectsControlsAndUnboundedPayloads() {
        assertTrue(isPhoneMusicUserToken("T".repeat(80)))
        assertFalse(isPhoneMusicUserToken("T".repeat(31)))
        assertFalse(isPhoneMusicUserToken("T".repeat(8_193)))
        assertFalse(isPhoneMusicUserToken("T".repeat(40) + "\n"))
        assertFalse(isPhoneMusicUserToken("T".repeat(40) + " "))
    }
    @Test fun closingServerClosesAcceptedAndQueuedClientsImmediately() {
        val server = server()
        val clients = (1..6).map { Socket("127.0.0.1", server.pairing.port).apply {
            soTimeout = 1_000
            getOutputStream().apply { write('G'.code); flush() }
        } }
        try {
            server.close()
            clients.forEach { client ->
                // Closing a socket with unread data may emit FIN or RST. Both
                // terminate the request; an idle-timeout exception is a failure.
                try { assertEquals(-1, client.getInputStream().read()) }
                catch (_: java.net.SocketException) { }
            }
        } finally { clients.forEach { it.close() }; server.close() }
    }

    @Test fun requestLineStopsReadingAtItsBound() {
        val input = java.io.ByteArrayInputStream("A".repeat(100_000).toByteArray())
        assertNull(boundedHttpLine(input, 128))
        assertTrue(input.available() >= 100_000 - 129)
    }

    @Test fun phonePageUsesSimplifiedAndTraditionalChineseIndependently() {
        val simplified = phoneMusicAuthorizationPage("developer", "test", "zh-CN", "/pair/nonce/")
        assertTrue(simplified.contains("lang=\"zh-Hans\""))
        assertTrue(simplified.contains("在手机登录 Apple Music"))
        val traditional = phoneMusicAuthorizationPage("developer", "test", "zh-Hant-TW", "/pair/nonce/")
        assertTrue(traditional.contains("lang=\"zh-Hant\""))
        assertTrue(traditional.contains("在手機登入 Apple Music"))
    }

}
