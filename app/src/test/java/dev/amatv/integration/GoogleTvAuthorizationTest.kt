package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoogleTvAuthorizationTest {
    private class Saved(var session: String? = """{"access_token":"old","refresh_token":"account-a","expires_at_ms":0}""") {
        val persistence = GoogleAuthorizationPersistence({ session }, { session = it }, { session = null })
    }
    private fun success(access: String = "new", refresh: String = "account-b") = GoogleOAuthResponse(200,
        JSONObject().put("access_token", access).put("refresh_token", refresh).put("expires_in", 3600))
    private fun pairing() = GoogleOAuthResponse(200, JSONObject("""{"user_code":"CODE","verification_url":"https://example.test/verify","device_code":"device","expires_in":600}"""))

    @Test fun lateRefreshCannotRestoreSignedOutCredentials() = runBlocking {
        val saved = Saved()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = GoogleTvAuthorization(saved.persistence, post = { _, _ ->
            entered.complete(Unit); release.await(); success()
        }, oauthConfigured = { true })
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.accessToken() } }
        entered.await()
        owner.signOut()
        release.complete(Unit)
        assertTrue(refresh.await().exceptionOrNull() is CancellationException)
        assertNull(saved.session)
        assertEquals(GoogleTvAuthorizationState(false, "guest"), owner.state.value)
    }

    @Test fun oldInvalidGrantCannotClearNewPairing() = runBlocking {
        val saved = Saved()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = GoogleTvAuthorization(saved.persistence, post = { url, form ->
            when {
                url.endsWith("device/code") -> pairing()
                form["grant_type"] == "refresh_token" -> {
                    entered.complete(Unit); release.await()
                    GoogleOAuthResponse(400, JSONObject("""{"error":"invalid_grant"}"""))
                }
                else -> success()
            }
        }, oauthConfigured = { true }, pollDelay = {})
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.accessToken() } }
        entered.await()
        owner.awaitPairing(owner.startPairing())
        val paired = owner.state.value
        release.complete(Unit)
        assertTrue(refresh.await().exceptionOrNull() is CancellationException)
        assertTrue(owner.state.value.signedIn)
        assertEquals(paired, owner.state.value)
        assertEquals("account-b", JSONObject(saved.session!!).getString("refresh_token"))
    }

    @Test fun lateDeviceCodeCannotReplaceTheCurrentAttempt() = runBlocking {
        val saved = Saved(null)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val owner = GoogleTvAuthorization(saved.persistence, post = { _, _ ->
            if (++calls == 1) { entered.complete(Unit); release.await() }
            pairing()
        }, oauthConfigured = { true })
        val old = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.startPairing() } }
        entered.await()
        owner.startPairing()
        release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertFalse(owner.state.value.signedIn)
    }

    @Test fun refreshRotationKeepsThePersistedOpaqueIdentity() = runBlocking {
        val saved = Saved()
        val owner = GoogleTvAuthorization(saved.persistence, post = { _, _ -> success("access-two", "refresh-two") },
            oauthConfigured = { true })
        val identity = owner.state.value.scope
        assertEquals("access-two", owner.accessToken())
        assertEquals(identity, owner.state.value.scope)
        assertEquals(identity, sessionState(saved.session).scope)
        assertFalse(identity.contains("account-a"))
    }

    @Test fun currentInvalidGrantPublishesDisconnectedState() = runBlocking {
        val saved = Saved()
        val owner = GoogleTvAuthorization(saved.persistence, post = { _, _ ->
            GoogleOAuthResponse(400, JSONObject("""{"error":"invalid_grant"}"""))
        }, oauthConfigured = { true })
        assertNull(owner.accessToken())
        assertFalse(owner.state.value.signedIn)
        assertNull(saved.session)
    }
}
