package dev.amatv.integration

import java.util.Locale
import androidx.core.os.ConfigurationCompat
import android.content.Context
import android.text.TextUtils
import dev.amatv.BuildConfig
import dev.amatv.R
import org.json.JSONObject

internal const val MUSIC_AUTH_JS_BRIDGE = "AndroidMusicAuth"

/** MusicKit sign-in page for the system WebView. */
internal fun musicKitAuthorizationPage(
    context: Context,
    developerToken: String,
): String {
    val tokenLiteral = JSONObject.quote(developerToken)
    val buildLiteral = JSONObject.quote(BuildConfig.VERSION_NAME)
    val labels = JSONObject()
        .put("continue", context.getString(R.string.services_auth_continue))
        .put("componentFailed", context.getString(R.string.services_auth_component_failed))
        .put("waiting", context.getString(R.string.services_auth_waiting))
        .put("invalidAuthorization", context.getString(R.string.services_auth_invalid_user_token))
        .put("retry", context.getString(R.string.services_auth_retry))
    val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
    return """
        <!doctype html>
        <html lang="${locale.toLanguageTag()}">
          <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              * { box-sizing: border-box; }
              body { margin: 0; min-height: 100vh; display: grid; place-items: center;
                background: radial-gradient(circle at 50% 0%, #40152d, #09090b 60%);
                color: #fff; font: 24px -apple-system, BlinkMacSystemFont, sans-serif; }
              main { width: 84vw; max-width: 760px; text-align: center; }
              h1 { font-size: 46px; margin: 0 0 18px; }
              p { color: rgba(255,255,255,.68); line-height: 1.5; }
              button { margin-top: 28px; min-width: 360px; padding: 20px 34px; border: 0;
                border-radius: 38px; background: #fff; color: #111; font-size: 24px;
                font-weight: 700; outline: 0; cursor: pointer; }
              button:focus { transform: scale(1.04); box-shadow: 0 0 0 5px #ff375f; }
              button:disabled { opacity: .45; }
              #error { color: #ff9bab; min-height: 36px; margin-top: 18px; font-size: 18px; }
            </style>
          </head>
          <body>
            <main>
              <h1>${TextUtils.htmlEncode(context.getString(R.string.services_auth_title))}</h1>
              <button id="signin" disabled onclick="authorizeMusic()">${TextUtils.htmlEncode(context.getString(R.string.services_auth_preparing))}</button>
              <p id="error"></p>
            </main>
            <script>
              // This synthetic origin only holds our MusicKit bootstrap
              // state. Never let its old token bypass a fresh local sign-in.
              try {
                window.localStorage.clear();
                window.sessionStorage.clear();
              } catch (storageUnavailable) { }
              var button = document.getElementById('signin');
              var error = document.getElementById('error');
              var labels = $labels;
              function readableError(value) {
                return value && value.message ? value.message : String(value);
              }
              function showAuthorizeError(value, reload) {
                console.error('MusicKit authorization failed: ' + readableError(value));
                error.textContent = readableError(value);
                button.disabled = false;
                button.textContent = labels.retry;
                button.onclick = reload ? function() { window.location.reload(); } : authorizeMusic;
                button.focus();
              }
              document.addEventListener('musickitloaded', function() {
                console.info('MusicKit script loaded');
                try {
                  var configuration = MusicKit.configure({
                    developerToken: $tokenLiteral,
                    app: { name: 'AM for TV', build: $buildLiteral }
                  });
                  Promise.resolve(configuration).then(function() {
                    console.info('MusicKit ready');
                    button.disabled = false;
                    button.textContent = labels.continue;
                    button.onclick = authorizeMusic;
                    button.focus();
                  }, function(value) {
                    showAuthorizeError(value, true);
                  });
                } catch (e) {
                  showAuthorizeError(e, true);
                }
              });
              function scriptFailed() {
                console.error('MusicKit script could not be loaded');
                var message = labels.componentFailed;
                error.textContent = message;
                button.disabled = false;
                button.textContent = labels.retry;
                button.onclick = function() { window.location.reload(); };
                button.focus();
              }
              function authorizeMusic() {
                console.info('MusicKit authorization requested');
                button.disabled = true;
                button.textContent = labels.waiting;
                error.textContent = '';
                try {
                  var instance = MusicKit.getInstance();
                  Promise.resolve(instance.authorize()).then(function(token) {
                    var resolved = (typeof token === 'string' && token.length > 20)
                      ? token
                      : (typeof instance.musicUserToken === 'string' ? instance.musicUserToken : '');
                    if (resolved && resolved.length > 20) {
                      console.info('MusicKit authorization completed');
                      $MUSIC_AUTH_JS_BRIDGE.authorized(resolved);
                    } else {
                      showAuthorizeError(new Error(labels.invalidAuthorization));
                    }
                  }, showAuthorizeError);
                } catch (e) {
                  showAuthorizeError(e);
                }
              }
            </script>
            <script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js"
              onerror="scriptFailed()"></script>
          </body>
        </html>
    """.trimIndent()
}
