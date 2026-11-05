package dev.amatv.integration

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * Gecko has no loadDataWithBaseURL API. A loopback document supplies a real web
 * origin for MusicKit's window.opener/postMessage protocol without weakening TLS.
 * No account input or token is posted here; Apple login remains on HTTPS pages.
 */
internal class AuthLoopbackServer(page: (GeckoAuthOrigin) -> String) : Closeable {
    private val socket = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
    }
    val origin = GeckoAuthOrigin(
        socket.localPort,
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(24).also { SecureRandom().nextBytes(it) },
        ),
    )
    private val document = page(origin).toByteArray(Charsets.UTF_8)
    @Volatile private var closed = false
    private val worker = thread(name = "AMATV-auth-loopback", isDaemon = true) {
        while (!closed) {
            try {
                socket.accept().use(::serve)
            } catch (_: SocketException) {
                if (!closed) close()
            } catch (_: Exception) {
                // A malformed/closed local connection must not terminate the listener.
            }
        }
    }

    private fun serve(client: Socket) {
        if (!client.inetAddress.isLoopbackAddress) return
        // Limit an individual HTTP request, not the time a user can spend logging in.
        client.soTimeout = 5_000
        val input = client.getInputStream()
        val request = boundedHttpLine(input, 4_096) ?: return
        val parts = request.split(' ')
        if (parts.size != 3) return
        var host: String? = null
        var headerBytes = request.length
        while (true) {
            val line = boundedHttpLine(input, (16_384 - headerBytes).coerceAtMost(4_096)) ?: return
            headerBytes += line.length + 2
            if (headerBytes > 16_384) return
            if (line.isEmpty()) break
            if (line.startsWith("Host:", ignoreCase = true)) host = line.substringAfter(':').trim()
        }
        val accepted = parts[0] == "GET" && parts[1] == origin.path && host == origin.authority
        val body = if (accepted) document else "Not found".toByteArray(Charsets.UTF_8)
        val status = if (accepted) "200 OK" else "404 Not Found"
        val headers = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            // Apple's authorization page needs the opener origin to establish
            // its result channel. Cross-origin requests must omit our nonce path.
            "Referrer-Policy: strict-origin-when-cross-origin\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }
}
