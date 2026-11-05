package dev.amatv.integration

import dev.amatv.model.CatalogSearchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class AppleAuthorizationScopeTest {
    private fun api(account: () -> String?, reply: (Request) -> Pair<Int, String>): AppleMusicApi {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val token = "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":4102444800}""")}.fixture"
        val calls = catalogHttpClient.newBuilder().addInterceptor { chain ->
            val (status, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        return AppleMusicApi("tw", DeveloperTokenClient("", bundledToken = token), calls, account)
    }

    @Test fun accountChangeBetweenPreparationAndWriteNeverDispatchesUnderNewAccount() = runBlocking {
        val account = AtomicReference("account-a")
        var writes = 0
        val api = api(account::get) { request ->
            assertEquals("account-a", request.header("Music-User-Token"))
            writes++
            201 to """{"data":[{"id":"p.one","type":"library-playlists","attributes":{"name":"One"}}]}"""
        }
        val failure = runCatching { api.withCurrentAuthorization {
            api.actions.createPlaylist("One")
            account.set("account-b")
            api.actions.createPlaylist("Two")
        } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, writes)
    }

    @Test fun unauthorizedWriteIsNotAutomaticallyResubmitted() = runBlocking {
        var writes = 0
        val api = api({ "account-a" }) { writes++; 401 to "{}" }
        assertTrue(runCatching { api.actions.createPlaylist("One") }.exceptionOrNull() is CatalogRequestException)
        assertEquals(1, writes)
    }

    @Test fun catalogReadsUseSupportedAppLanguageAndKeepAccountRegion() = runBlocking {
        val sent = mutableListOf<Request>()
        val api = api({ "account-a" }) { request ->
            sent += request
            if (request.url.encodedPath == "/v1/me/storefront") 200 to """{"data":[{"id":"cn","type":"storefronts",
              "attributes":{"supportedLanguageTags":["zh-Hans-CN","en-GB"],"defaultLanguageTag":"zh-Hans-CN"}}]}"""
            else 200 to "{}"
        }.apply { language = { "en-US" } }
        api.searchPage("Song", CatalogSearchType.Songs)
        assertEquals(2, sent.size)
        assertEquals("/v1/catalog/cn/search", sent.last().url.encodedPath)
        assertEquals("en-GB", sent.last().url.queryParameter("l"))
        assertEquals("account-a", sent.last().header("Music-User-Token"))
    }
}
