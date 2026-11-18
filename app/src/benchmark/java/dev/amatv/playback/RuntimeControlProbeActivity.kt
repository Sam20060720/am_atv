package dev.amatv.playback

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Process
import dev.amatv.data.MockCatalog
import dev.amatv.integration.PlaybackMediaSession
import dev.amatv.integration.PlaybackSession
import dev.amatv.integration.PlaybackSessionCodec
import dev.amatv.model.Destination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Emulator-only wiring checks against the real runtime, with no account, native audio or network. */
class RuntimeControlProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 28)
        check(Application.getProcessName().endsWith(":runtime_control_probe"))
        check(intent.getBooleanExtra("allow_runtime_control_fixture", false))
        val run = checkNotNull(intent.getStringExtra("run_id"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val output = File(checkNotNull(getExternalFilesDir(null)), "runtime-control-fixture/$run/result.json")
        check(!output.exists() && checkNotNull(output.parentFile).mkdirs())
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            val checks = JSONObject()
            val report = JSONObject().put("run_id", run).put("hardware", Build.HARDWARE)
                .put("isolated_process", true).put("checks", checks)
            try {
                verifyRuntime(run, checks)
                report.put("status", "passed")
            } catch (failure: Exception) {
                report.put("status", "failed").put("error", failure.toString())
            }
            val temporary = File(output.parentFile, "result.pending")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(output))
            finish()
            // The separate process also isolates process-wide cache and session-store singletons.
            Process.killProcess(Process.myPid())
        }
    }

    private suspend fun verifyRuntime(run: String, checks: JSONObject) {
        val sandbox = ProbeContext(applicationContext, run)
        val tracks = List(32) { MockCatalog.tracks[it % MockCatalog.tracks.size].copy(lyrics = emptyList()) }
        val ids = List(tracks.size) { it.toLong() + 1L }
        val full = PlaybackSession(tracks, 16, 7_300L, false, Destination.NowPlaying, Destination.Home,
            false, null, null, queueEntryIds = ids, playedEntryIds = ids.take(16).toSet(),
            contextQueue = tracks, contextEntryIds = ids, sessionId = 77L, persistenceId = run,
            playbackContext = PlaybackContext(ContextKind.Playlist, "fixture"), containerEntryIds = ids.toSet())
        val sessionFile = File(sandbox.filesDir, "playback-session-v2.json")
        val original = PlaybackSessionCodec.encode(full)
        sessionFile.writeText(original)
        val checkpoint = checkNotNull(PlaybackSessionCodec.decode(PlaybackSessionCodec.encodeCheckpoint(full)))
        sandbox.getSharedPreferences("am_atv_playback_cursor", Context.MODE_PRIVATE).edit()
            .putString("session_cursor_v2", PlaybackSessionCodec.encodeCheckpoint(full)).commit()
        val runtime = PlaybackRuntimeOwner(sandbox) { }
        var lease: Long? = null
        val selected = mutableListOf<Pair<String, Long>>()
        fun verify(name: String, valid: Boolean) {
            check(valid) { name }
            checks.put(name, true)
        }
        fun noNative() {
            check(runtime.preparation.value.phase == PlaybackRuntimeOwner.PreparationPhase.Idle &&
                runtime.playbackGeneration == 0L && runtime.snapshot.value == null) {
                "Demo commands attempted native preparation"
            }
        }
        fun callbacks(owner: String) = PlaybackRuntimeOwner.UiCallbacks(
            onTrack = { track ->
                check(MockCatalog.isDemoTrack(track))
                selected += owner to checkNotNull(runtime.queue.state.value.current).entryId
            }, onSnapshot = { error("Demo commands emitted a native playback snapshot") },
            system = PlaybackMediaSession.Callbacks(
                play = { runtime.play() }, pause = runtime::pause,
                next = { runtime.skip(1) }, previous = { runtime.skip(-1) }, seekTo = runtime::seekTo,
            ),
        )
        try {
            runtime.preferences.autoplayEnabled = false
            lease = runtime.attachUi(null, callbacks("first"))
            runtime.queue.restoreFromSession(checkpoint)
            runtime.restoreMetadataCursor(checkpoint)
            verify("checkpoint_is_bounded", checkpoint.queue.size < full.queue.size)
            check(runtime.play())
            noNative()
            val writes = sandbox.checkpointWrites
            runtime.pause()
            verify("pause_during_hydration_does_not_save", sandbox.checkpointWrites == writes && !runtime.desiredPlay.value)
            delay(100L)
            verify("pause_keeps_full_session", sessionFile.readText() == original)
            val restored = withContext(Dispatchers.IO) { checkNotNull(runtime.preferences.restoreSession()) }
            runtime.queue.restoreFromSession(restored)
            runtime.restoreMetadataCursor(restored)
            verify("full_restore_preserves_queue", runtime.queue.state.value.sessionEntries.size == tracks.size)

            val firstLease = checkNotNull(lease)
            runtime.detachUi(firstLease)
            lease = null
            val detachedNext = checkNotNull(runtime.queue.state.value.upNext.firstOrNull()).entryId
            check(runtime.play())
            noNative()
            runtime.skip(1)
            verify("detached_control_continuity", runtime.desiredPlay.value &&
                runtime.queue.state.value.current?.entryId == detachedNext && selected.isEmpty())
            runtime.pause()
            val secondCallbacks = callbacks("second")
            lease = runtime.attachUi(null, secondCallbacks)
            runtime.detachUi(firstLease) // An obsolete UI lease must not detach the replacement.
            secondCallbacks.system.play()
            noNative()
            secondCallbacks.system.previous()
            verify("attached_control_callbacks", selected.size == 1 && selected.single().first == "second")
            secondCallbacks.system.pause()
            verify("attached_pause_clears_intent", !runtime.desiredPlay.value)

            val duplicate = MockCatalog.tracks.first().copy(lyrics = emptyList())
            runtime.queue.playContext(List(3) { duplicate }, 0, PlaybackContext(ContextKind.Playlist, "duplicates"))
            check(runtime.play())
            noNative()
            val before = runtime.queue.state.value
            val callbacksBefore = selected.size
            runtime.completeSimulatedPlayback()
            verify("duplicate_occurrence_advances_once", runtime.queue.state.value.current?.entryId == before.upNext.first().entryId &&
                runtime.queue.state.value.current?.track?.id == before.current?.track?.id && selected.size == callbacksBefore + 1)
            runtime.pause()
            val pausedEntry = runtime.queue.state.value.current?.entryId
            runtime.completeSimulatedPlayback()
            verify("paused_completion_does_not_advance", runtime.queue.state.value.current?.entryId == pausedEntry)
            check(runtime.play())
            noNative()
            check(runtime.setSleepTimer(SleepTimerOption.EndOfTrack))
            runtime.completeSimulatedPlayback()
            verify("sleep_timer_stops_before_next", !runtime.desiredPlay.value &&
                runtime.queue.state.value.current?.entryId == pausedEntry && runtime.sleepTimer.value.option == SleepTimerOption.Off)
            noNative()
            verify("demo_avoids_native_preparation", true)
        } finally {
            runtime.setDesiredPlayIntent(false)
            runtime.queue.reset()
            lease?.let(runtime::detachUi)
            runtime.resetSession()
            runtime.preferences.clearSession()
            sandbox.clear()
        }
    }

    /** All preferences and files accessed by the real services belong to this single probe run. */
    private class ProbeContext(base: Context, run: String) : ContextWrapper(base) {
        private val prefix = "runtime_probe_${run}_"
        private val preferences = mutableSetOf<String>()
        private val ownFiles = File(base.filesDir, "runtime-control-probe/$run").apply { check(mkdirs()) }
        private val ownCache = File(base.cacheDir, "runtime-control-probe/$run").apply { check(mkdirs()) }
        var checkpointWrites = 0
            private set
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = ownFiles
        override fun getCacheDir(): File = ownCache
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val qualified = prefix + name
            preferences += qualified
            val target = super.getSharedPreferences(qualified, mode)
            if (name != "am_atv_playback_cursor") return target
            return object : SharedPreferences by target {
                override fun edit(): SharedPreferences.Editor {
                    val editor = target.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            if (key == "session_cursor_v2") checkpointWrites++
                            editor.putString(key, value)
                            return this
                        }
                    }
                }
            }
        }
        fun clear() {
            preferences.forEach { baseContext.deleteSharedPreferences(it) }
            ownFiles.deleteRecursively()
            ownCache.deleteRecursively()
        }
    }
}
