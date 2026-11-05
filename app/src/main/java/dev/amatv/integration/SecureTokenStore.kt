package dev.amatv.integration

import dev.amatv.R
import dev.amatv.i18n.AppText

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores MusicKit tokens encrypted with a non-exportable Android Keystore key. */
class SecureTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("music_session", Context.MODE_PRIVATE)
    private val sdkAuthPreferences = context.getSharedPreferences(
        "com.apple.android.music.auth_preferences",
        Context.MODE_PRIVATE,
    )

    fun readUserToken(): String? = synchronized(tokenLock) { readRecoverableToken(
        readEncrypted = { readEncrypted(KEY_USER_TOKEN_CIPHERTEXT) },
        readSdkFallback = { sdkAuthPreferences.getString("user-token", null) },
        normalize = ::normalizeMusicUserToken,
        isValid = { it.length > 20 && it.none(Char::isWhitespace) },
        repairEncrypted = ::writeUserToken,
    ) }

    fun writeUserToken(token: String) = synchronized(tokenLock) {
        val normalized = normalizeMusicUserToken(token)
        writeEncrypted(KEY_USER_TOKEN_CIPHERTEXT, normalized)
        sdkAuthPreferences.edit().putString("user-token", normalized).apply()
    }

    fun readDeveloperToken(): String? = synchronized(tokenLock) { readRecoverableToken(
        readEncrypted = { readEncrypted(KEY_DEV_TOKEN_CIPHERTEXT) },
        readSdkFallback = { sdkAuthPreferences.getString("developer-token", null) },
        normalize = String::trim,
        isValid = ::isValidNonExpiredJwt,
        repairEncrypted = ::writeDeveloperToken,
    ) }

    fun writeDeveloperToken(token: String) = synchronized(tokenLock) {
        val trimmed = token.trim()
        require(trimmed.count { it == '.' } == 2) { AppText.text(R.string.services_developer_token_invalid) }
        writeEncrypted(KEY_DEV_TOKEN_CIPHERTEXT, trimmed)
        sdkAuthPreferences.edit().putString("developer-token", trimmed).apply()
    }

    fun clearDeveloperToken() = synchronized(tokenLock) {
        preferences.edit().remove(KEY_DEV_TOKEN_CIPHERTEXT).apply()
        sdkAuthPreferences.edit().remove("developer-token").apply()
    }

    fun clear() = synchronized(tokenLock) {
        preferences.edit().remove(KEY_USER_TOKEN_CIPHERTEXT).apply()
        sdkAuthPreferences.edit().remove("user-token").apply()
    }

    fun readYtmSession(): String? = synchronized(tokenLock) { runCatching {
        readEncrypted(KEY_YTM_SESSION_CIPHERTEXT)
    }.getOrNull() }

    fun writeYtmSession(json: String) = synchronized(tokenLock) {
        writeEncrypted(KEY_YTM_SESSION_CIPHERTEXT, json)
    }

    fun clearYtmSession() = synchronized(tokenLock) {
        preferences.edit().remove(KEY_YTM_SESSION_CIPHERTEXT).apply()
    }

    /** Compare and commit under the same process-wide read/repair/clear boundary. */
    internal fun replaceUserToken(expected: String?, token: String): Boolean = synchronized(tokenLock) {
        if (readUserToken() != expected) return@synchronized false
        writeUserToken(token)
        true
    }

    private fun readEncrypted(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = bytes.first().toInt() and 0xff
        require(ivSize in 12..32 && bytes.size > ivSize + 1)
        val iv = bytes.copyOfRange(1, ivSize + 1)
        val ciphertext = bytes.copyOfRange(ivSize + 1, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun writeEncrypted(key: String, plainText: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext
        preferences.edit()
            .putString(key, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    private fun isValidNonExpiredJwt(token: String): Boolean {
        val parts = token.split('.')
        if (parts.size != 3) return false
        return runCatching {
            val payloadBytes = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            val exp = JSONObject(String(payloadBytes, Charsets.UTF_8)).optLong("exp", 0L)
            val now = Instant.now().epochSecond
            exp > now + EXPIRY_SAFETY_SECONDS
        }.getOrDefault(false)
    }

    /**
     * A same-window MusicKit Web callback wraps the real Music User Token in a
     * base64 JSON envelope. Decode it locally and store only the inner token.
     */
    private fun normalizeMusicUserToken(value: String): String = runCatching {
        if (value.count { it == '.' } >= 2) return@runCatching value
        val decoded = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        JSONObject(decoded).optString("musicUserToken")
            .takeIf { it.length > 20 && it.none(Char::isWhitespace) }
            ?: value
    }.getOrDefault(value)

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        // All store instances address the same preferences and Keystore alias.
        val tokenLock = Any()
        const val KEY_ALIAS = "dev.amatv.music-user-token"
        const val KEY_USER_TOKEN_CIPHERTEXT = "music_user_token_encrypted"
        const val KEY_DEV_TOKEN_CIPHERTEXT = "music_developer_token_encrypted"
        const val KEY_YTM_SESSION_CIPHERTEXT = "ytm_oauth_session_encrypted"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val EXPIRY_SAFETY_SECONDS = 300L
    }
}
