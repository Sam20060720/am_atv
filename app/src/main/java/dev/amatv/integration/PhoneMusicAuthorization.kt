package dev.amatv.integration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class PhoneMusicPairing(val url: String, val port: Int, val expiresAtMillis: Long)
internal enum class PhoneMusicAuthStatus { Waiting, PhoneConnected, Authorized, Expired, Closed }

/** A temporary phone-to-TV authorization result channel; never proxies Apple credentials. */
internal class PhoneMusicAuthorization(
    developerToken: String,
    appBuild: String,
    languageTag: String = "zh-TW",
    advertisedAuthority: String? = null,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    port: Int = 0,
    lifetimeMs: Long = 15 * 60_000L,
) : Closeable {
    private val token = CompletableDeferred<String>()
    private val mutableStatus = MutableStateFlow(PhoneMusicAuthStatus.Waiting)
    val status = mutableStatus.asStateFlow()
    private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also { SecureRandom().nextBytes(it) })
    private val socket = ServerSocket()
    private val connections = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
        { action -> Thread(action, "AMATV-phone-auth-client").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val timer = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "AMATV-phone-auth-expiry").apply { isDaemon = true } }
    @Volatile private var closed = false
    private var accepted = false
    private val authority: String
    private val origin: String
    private val path = "/pair/$nonce/"
    private val document: ByteArray
    val pairing: PhoneMusicPairing

    init {
        require(lifetimeMs in 1_000L..30 * 60_000L)
        require(developerToken.isNotBlank() && developerToken.length <= 16_384)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(bindAddress, port), 4)
            authority = advertisedAuthority ?: "${phoneAuthorizationLanAddress() ?: error("No local network address")}:${socket.localPort}"
            require(authority.matches(Regex("[A-Za-z0-9.-]+:[0-9]{1,5}")))
            require(authority.substringAfterLast(':').toInt() in 1..65535)
            origin = "http://$authority"
            pairing = PhoneMusicPairing(origin + path, socket.localPort, System.currentTimeMillis() + lifetimeMs)
            document = phoneMusicAuthorizationPage(developerToken, appBuild, languageTag, path).toByteArray(Charsets.UTF_8)
            timer.schedule({ expire() }, lifetimeMs, TimeUnit.MILLISECONDS)
            thread(name = "AMATV-phone-auth", isDaemon = true) {
                while (!closed) {
                    try {
                        val client = socket.accept()
                        try { connections.execute { client.use { serve(it) } } }
                        catch (_: Exception) { runCatching { client.close() } }
                    } catch (_: SocketException) { if (!closed) close() }
                    catch (_: Exception) { if (!closed) close() }
                }
            }
        } catch (failure: Exception) {
            runCatching { socket.close() }
            connections.shutdownNow()
            timer.shutdownNow()
            throw failure
        }
    }

    suspend fun awaitToken(): String = token.await()

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 5_000
            val input = client.getInputStream()
            val first = boundedHttpLine(input, 2_048) ?: return
            val request = first.split(' ')
            if (request.size != 3 || request[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return
            var remaining = 8_192 - first.length
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = boundedHttpLine(input, remaining.coerceAtMost(2_048)) ?: return
                remaining -= line.length + 2
                if (remaining < 0) return
                if (line.isEmpty()) break
                val key = line.substringBefore(':').lowercase()
                if (':' !in line || headers.put(key, line.substringAfter(':').trim()) != null) return
            }
            if (headers["host"] != authority || request[1].substringBefore('?') !in setOf(path, "${path}token")) {
                respond(client, 404, "Not found")
                return
            }
            if (closed || System.currentTimeMillis() >= pairing.expiresAtMillis) {
                respond(client, 410, "This sign-in link has expired.")
                return
            }
            when {
                request[0] == "GET" && request[1] == path -> {
                    if (accepted) { respond(client, 410, "Sign-in has already completed."); return }
                    mutableStatus.value = PhoneMusicAuthStatus.PhoneConnected
                    respond(client, 200, document, "text/html; charset=utf-8")
                }
                request[0] == "POST" && request[1] == "${path}token" -> {
                    if (headers["origin"] != origin || headers["x-amatv-pairing"] != nonce || headers["transfer-encoding"] != null ||
                        headers["content-type"]?.substringBefore(';')?.trim() != "text/plain") {
                        respond(client, 403, "Request rejected")
                        return
                    }
                    val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..8_192 }
                    if (length == null) { respond(client, 413, "Request too large"); return }
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(bytes, read, length - read)
                        if (count < 0) return
                        read += count
                    }
                    val value = bytes.toString(Charsets.UTF_8)
                    if (!isPhoneMusicUserToken(value)) { respond(client, 400, "Invalid authorization result"); return }
                    synchronized(this) {
                        if (accepted || closed) { respond(client, 410, "Sign-in has already completed."); return }
                        accepted = true
                        // Flush success before waking the app, which may close this server.
                        // A phone disconnect after a valid POST cannot strand the accepted result.
                        try { respond(client, 200, "OK") }
                        finally {
                            mutableStatus.value = PhoneMusicAuthStatus.Authorized
                            token.complete(value)
                        }
                    }
                }
                else -> respond(client, 405, "Method not allowed")
            }
        } catch (_: Exception) {
            // Network/request errors never include the body or credential in logs.
        }
    }

    private fun respond(client: Socket, code: Int, body: String) = respond(client, code, body.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
    private fun respond(client: Socket, code: Int, body: ByteArray, type: String) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 410 -> "Gone"; else -> "Payload Too Large" }
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: strict-origin-when-cross-origin\r\n" +
            "Content-Security-Policy: frame-ancestors 'none'\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        // Publish a complete HTTP response/FIN before closing a rejected upload.
        // Closing with unread socket data can otherwise reset the connection and
        // hide the 403/413 from a phone. Discard only bytes already available;
        // never wait for an untrusted/oversized declared Content-Length.
        runCatching { client.shutdownOutput() }
        if (code >= 400) {
            val input = client.getInputStream()
            val discard = ByteArray(1_024)
            var budget = 8_192
            while (budget > 0) {
                val available = input.available().coerceAtMost(discard.size).coerceAtMost(budget)
                if (available == 0) break
                val read = input.read(discard, 0, available)
                if (read <= 0) break
                budget -= read
            }
        }
    }

    private fun expire() {
        synchronized(this) {
            if (closed || accepted) return
            mutableStatus.value = PhoneMusicAuthStatus.Expired
            token.completeExceptionally(PhoneMusicAuthorizationExpired())
            shutdown()
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            if (!accepted && mutableStatus.value != PhoneMusicAuthStatus.Expired) mutableStatus.value = PhoneMusicAuthStatus.Closed
            if (!token.isCompleted) token.cancel()
            shutdown()
        }
    }

    private fun shutdown() {
        closed = true
        runCatching { socket.close() }
        connections.shutdownNow()
        timer.shutdownNow()
    }
}

internal class PhoneMusicAuthorizationExpired : IllegalStateException("Phone sign-in expired")

internal fun isPhoneMusicUserToken(value: String): Boolean = value.length in 32..8_192 && value.all { it.code in 33..126 }

internal fun phoneAuthorizationLanAddress(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    .filter { it.isUp && !it.isLoopback && !it.isVirtual }
    .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("en") || it.name.startsWith("eth")) 0 else 1 }
    .flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>()
    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }?.hostAddress

private fun boundedHttpLine(input: InputStream, limit: Int): String? {
    if (limit <= 0) return null
    val out = StringBuilder()
    while (out.length <= limit) {
        val byte = input.read()
        if (byte < 0) return null
        if (byte == 10) return out.toString().removeSuffix("\r")
        if (byte !in 9..126) return null
        out.append(byte.toChar())
    }
    return null
}

internal fun phoneMusicAuthorizationPage(developerToken: String, appBuild: String, languageTag: String, path: String): String {
    val chinese = languageTag.startsWith("zh", ignoreCase = true)
    val title = if (chinese) "在手機登入 Apple Music" else "Sign in to Apple Music"
    val explanation = if (chinese) "使用 Apple 登入，完成後會自動連接電視。請保持此頁開啟。" else "Sign in with Apple to connect your TV. Keep this page open until it finishes."
    val button = if (chinese) "繼續登入" else "Continue"
    val preparing = if (chinese) "準備中⋯" else "Preparing…"
    val waiting = if (chinese) "請在 Apple 登入頁完成授權" else "Complete sign-in in the Apple window"
    val success = if (chinese) "登入結果已送到電視，請查看電視上的完成狀態。" else "Your sign-in result was sent. Check your TV for confirmation."
    val failed = if (chinese) "登入未完成，請重試。若未開啟 Apple 頁面，請允許彈出式視窗。" else "Sign-in did not finish. Try again and allow the Apple sign-in window."
    val expired = if (chinese) "連結已失效，請回到電視重新掃碼。" else "This link has expired. Scan the new code on your TV."
    val nonce = path.trimEnd('/').substringAfterLast('/')
    return """
        <!doctype html><html lang="${if (chinese) "zh-Hant" else "en"}"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
        <style>html{color-scheme:dark}*{box-sizing:border-box}body{margin:0;min-height:100svh;display:grid;place-items:center;background:radial-gradient(at 40% 0%,#391326,#0b0b0e 65%);color:#f5f5f7;font:17px -apple-system,BlinkMacSystemFont,sans-serif}main{width:min(88vw,420px);padding:40px 0}h1{font-size:32px;letter-spacing:-.7px;line-height:1.2}p{line-height:1.6;color:#aaa}button{width:100%;padding:17px;border:0;border-radius:18px;background:#fa355a;color:white;font:600 17px -apple-system,BlinkMacSystemFont,sans-serif;margin:20px 0}button:disabled{opacity:.45}#status{min-height:80px}a{color:#ff6c85}</style></head>
        <body><main><h1>$title</h1><p>$explanation</p><button id="signin" disabled>$preparing</button><p id="status" role="status"></p></main>
        <script>
        var button=document.getElementById('signin'), status=document.getElementById('status'), music=null, sending=false;
        var returnedFromApple=new RegExp('^#[A-Za-z0-9+/]{200,}={0,2}${'$'}').test(window.location.hash);
        function error(){button.disabled=false;button.textContent=${JSONObject.quote(button)};status.textContent=${JSONObject.quote(failed)};}
        function deliver(value){
          if(sending)return; if(typeof value!=='string'||value.length<32){error();return;}
          sending=true;button.disabled=true;
          fetch(${JSONObject.quote(path + "token")},{method:'POST',headers:{'Content-Type':'text/plain','X-AMATV-Pairing':${JSONObject.quote(nonce)}},body:value,cache:'no-store'})
          .then(function(response){if(!response.ok){if(response.status===410){status.textContent=${JSONObject.quote(expired)};return;}throw new Error('delivery');}status.textContent=${JSONObject.quote(success)};button.remove();})
          .catch(function(){sending=false;error();});
        }
        document.addEventListener('musickitloaded',function(){
          try { Promise.resolve(MusicKit.configure({developerToken:${JSONObject.quote(developerToken)},app:{name:'AM for TV',build:${JSONObject.quote(appBuild)}}}))
          .then(function(){music=MusicKit.getInstance();button.disabled=false;button.textContent=${JSONObject.quote(button)};
            // The official library can consume an Apple redirect hash itself.
            // Never read/forward the fragment to logs, analytics or another origin.
            if(returnedFromApple&&music.musicUserToken)deliver(music.musicUserToken);
          }).catch(error); } catch(e) {error();}
        });
        button.onclick=function(){if(!music){window.location.reload();return;}if(sending)return;button.disabled=true;status.textContent=${JSONObject.quote(waiting)};
          try{Promise.resolve(music.authorize()).then(function(value){deliver(typeof value==='string'?value:music.musicUserToken);},error);}catch(e){error();}
        };
        </script><script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js" onerror="error()"></script></body></html>
    """.trimIndent()
}
