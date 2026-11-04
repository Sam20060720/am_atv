package dev.amatv.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AppleMusicStorefrontTest {
    private fun api(account: () -> String?, reply: (Request) -> Pair<Int, String>): AppleMusicApi {
        fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val token = "${encode("""{"alg":"ES256"}""")}.${encode("""{"exp":4102444800}""")}.fixture"
        val calls = catalogHttpClient.newBuilder().addInterceptor { chain ->
            assertEquals("/v1/me/storefront", chain.request().url.encodedPath)
            val (status, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        return AppleMusicApi("tw", DeveloperTokenClient("", bundledToken = token), calls, account)
    }

    @Test fun signedInLookupFailureCannotBecomeTheConfiguredCountry() = runBlocking {
        val api = api({ "fixture-account" }) { request ->
            assertEquals("fixture-account", request.header("Music-User-Token"))
            503 to "{}"
        }
        val failure = runCatching { api.effectiveStorefront() }.exceptionOrNull()
        assertTrue(failure is CatalogRequestException)
        assertEquals(CatalogFailureReason.Transient, (failure as CatalogRequestException).reason)
        assertNull(api.cachedEffectiveStorefront())
    }

    @Test fun missingAccountRegionCannotBecomeTheConfiguredCountry() = runBlocking {
        val api = api({ "fixture-account" }) { 200 to """{"data":[]}""" }
        assertNotNull(runCatching { api.effectiveStorefront() }.exceptionOrNull())
        assertNull(api.cachedEffectiveStorefront())
    }

    @Test fun accountChangeDiscardsTheOldStorefrontResponse() = runBlocking {
        val account = AtomicReference("fixture-account-a")
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val api = api(account::get) { request ->
            assertEquals("fixture-account-a", request.header("Music-User-Token"))
            started.complete(Unit)
            check(release.await(3, TimeUnit.SECONDS))
            200 to """{"data":[{"id":"JP","type":"storefronts"}]}"""
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.effectiveStorefront() } }
        try {
            withTimeout(1_000) { started.await() }
            account.set("fixture-account-b")
            release.countDown()
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            assertNull(api.cachedEffectiveStorefront())
        } finally { release.countDown() }
    }

    @Test fun cancellationCannotPublishTheConfiguredCountry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val published = AtomicReference<String?>(null)
        val api = api({ "fixture-account" }) {
            started.complete(Unit)
            check(release.await(3, TimeUnit.SECONDS))
            200 to """{"data":[{"id":"JP","type":"storefronts"}]}"""
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { published.set(api.effectiveStorefront()) }
        try {
            withTimeout(1_000) { started.await() }
            pending.cancelAndJoin()
            assertNull(published.get())
        } finally { release.countDown() }
    }

    @Test
    fun readsAuthenticatedChinaStorefront() {
        val response = JSONObject(
            """{
              "data": [{
                "id": "CN",
                "type": "storefronts",
                "attributes": {"defaultLanguageTag": "zh-Hans-CN"}
              }]
            }""",
        )

        assertEquals("cn", parseUserStorefrontId(response))
    }

    @Test
    fun rejectsMissingOrMalformedStorefronts() {
        assertNull(parseUserStorefrontId(JSONObject("""{"data": []}""")))
        assertNull(parseUserStorefrontId(JSONObject("""{"data": [{"id": "china"}]}""")))
    }

    @Test
    fun effectiveStorefrontFallsBackToDefaultWhenUserTokenNull() = kotlinx.coroutines.runBlocking {
        val api = AppleMusicApi(
            storefront = "tw",
            developerTokens = DeveloperTokenClient(""),
            userToken = { null },
        )
        assertEquals("tw", api.effectiveStorefront())
    }
}
