package dev.amatv.benchmark

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.amatv.AmAtvApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Benchmark only. Recreate the real Activity while its real player keeps running. */
class RuntimeContinuityProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Archived management commands must not fall through to Activity recreation.
        if (intent.hasExtra("native_management_mode") || intent.getBooleanExtra("native_management_routes", false) ||
            intent.getBooleanExtra("library_album_read", false)) return
        if (Build.HARDWARE !in setOf("ranchu", "goldfish") && !intent.getBooleanExtra("allow_physical", false)) return
        val run = intent.getStringExtra("run_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: return
        val app = context.applicationContext as AmAtvApplication
        val out = File(app.getExternalFilesDir(null), "runtime-probe/$run/result.json")
        if (intent.getBooleanExtra("parser_stress", false)) {
            if (out.exists()) return
            out.parentFile?.mkdirs()
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    out.writeText(dev.amatv.integration.LargeHtmlParsingProbe.run(app).toString(2))
                } catch (error: Exception) {
                    out.writeText(JSONObject().put("status", "failed").put("error_type", error.javaClass.simpleName).toString(2))
                } finally { pending.finish() }
            }
            return
        }
        val checkpoint = intent.getStringExtra("playback_checkpoint")
        if (checkpoint in setOf("backup", "restore")) {
            // Fixed app-owned playback files only. Never export account tokens.
            val report = JSONObject().put("operation", checkpoint)
            try {
                check(app.existingPlaybackRuntime == null) { "Stop the target before checkpoint operations" }
                val root = File(app.filesDir, "benchmark-playback-checkpoint/$run")
                val scope = intent.getStringExtra("checkpoint_scope") ?: "playback"
                check(scope in setOf("playback", "experience"))
                val paths = listOf("shared_prefs/am_atv_playback_preferences.xml", "shared_prefs/am_atv_playback_preferences.xml.bak",
                    "shared_prefs/am_atv_playback_cursor.xml", "shared_prefs/am_atv_playback_cursor.xml.bak",
                    "files/playback-session-v2.json", "files/playback-session-v2.json.bak", "files/playback-session-v2.json.new") +
                    if (scope == "experience") listOf("search-history-v1", "artwork-motion", "content-cache").flatMap {
                        listOf("shared_prefs/$it.xml", "shared_prefs/$it.xml.bak")
                    } else emptyList()
                fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                val manifest = File(root, "manifest.json")
                val dataRoot = File(app.applicationInfo.dataDir)
                if (checkpoint == "backup") {
                    check(!root.exists() && root.mkdirs()) { "Never overwrite an existing checkpoint" }
                    val rows = JSONArray()
                    paths.forEachIndexed { index, relative ->
                        val source = File(dataRoot, relative)
                        val row = JSONObject().put("path", relative).put("exists", source.isFile)
                        if (source.isFile) {
                            val copy = File(root, "$index.bin"); source.copyTo(copy)
                            row.put("sha256", digest(copy))
                        }
                        rows.put(row)
                    }
                    manifest.writeText(JSONObject().put("files", rows).toString())
                } else {
                    val rows = JSONObject(manifest.readText()).getJSONArray("files")
                    check(rows.length() == paths.size)
                    // Validate every saved file before changing any destination.
                    paths.forEachIndexed { index, relative ->
                        val row = rows.getJSONObject(index); check(row.getString("path") == relative)
                        if (row.getBoolean("exists")) check(digest(File(root, "$index.bin")) == row.getString("sha256"))
                    }
                    paths.forEachIndexed { index, relative ->
                        val destination = File(dataRoot, relative); val row = rows.getJSONObject(index)
                        if (row.getBoolean("exists")) {
                            destination.parentFile?.mkdirs()
                            File(root, "$index.bin").copyTo(destination, overwrite = true)
                            check(digest(destination) == row.getString("sha256"))
                        } else check(!destination.exists() || destination.delete())
                    }
                }
                report.put("status", "passed").put("files", paths.size)
            } catch (error: Exception) { report.put("status", "failed").put("error", error.message?.take(200)) }
            out.parentFile?.mkdirs(); File(out.parentFile, "$checkpoint.json").writeText(report.toString(2))
            return
        }
        if (out.exists()) return
        out.parentFile?.mkdirs()
        val owner = app.existingPlaybackRuntime
        val before = owner?.snapshot?.value
        if (intent.getBooleanExtra("inspect_only", false)) {
            val duration = intent.getIntExtra("spectrum_samples_ms", 0).coerceIn(0, 5_000)
            if (duration > 0) {
                val pending = goAsync()
                val samples = JSONArray()
                val started = SystemClock.elapsedRealtime()
                val handler = Handler(Looper.getMainLooper())
                val observe = object : Runnable {
                    override fun run() {
                        val live = app.existingPlaybackRuntime
                        val frame = live?.services?.audioSpectrum()
                        samples.put(JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                            .put("playing", live?.snapshot?.value?.isPlaying == true)
                            .put("position_ms", live?.snapshot?.value?.positionMs ?: -1L)
                            .put("state", live?.services?.spectrumCaptureState()?.name)
                            .put("captured", frame?.captured == true).put("available", frame?.available == true)
                            .put("bass", frame?.bass ?: 0f).put("mid", frame?.mid ?: 0f).put("treble", frame?.treble ?: 0f))
                        if (SystemClock.elapsedRealtime() - started < duration) handler.postDelayed(this, 100L)
                        else try {
                            out.writeText(JSONObject().put("status", "observed").put("samples", samples).toString(2))
                        } finally { pending.finish() }
                    }
                }
                handler.post(observe)
                return
            }
            val frame = owner?.services?.audioSpectrum()
            out.writeText(JSONObject().put("status", "observed")
                .put("connection_state", owner?.services?.connectionState?.name ?: "no-runtime")
                .put("retained_title", owner?.queue?.state?.value?.current?.track?.title)
                .put("retained_duration_ms", owner?.queue?.state?.value?.current?.track?.durationMs)
                .put("retained_kind", owner?.queue?.state?.value?.current?.track?.catalogKind?.name)
                .put("desired_play", owner?.desiredPlay?.value)
                .put("playing", before?.isPlaying ?: false)
                .put("prepared", before?.isPrepared ?: false)
                .put("kind", before?.currentTrack?.catalogKind?.name)
                .put("position_ms", before?.positionMs)
                .put("spectrum_state", owner?.services?.spectrumCaptureState()?.name)
                .put("spectrum_captured", frame?.captured ?: false)
                .toString(2))
            return
        }
        if (owner == null || before?.isPlaying != true) {
            out.writeText(JSONObject().put("status", "unavailable").put("reason", "Existing real playback required").toString())
            return
        }
        val started = SystemClock.elapsedRealtime()
        val entry = owner.queue.state.value.current?.entryId
        val ids = owner.queue.state.value.sessionEntries.map { it.entryId }
        val handler = Handler(Looper.getMainLooper())
        var issued = false
        var finished = false
        lateinit var callback: Application.ActivityLifecycleCallbacks
        fun complete(reason: String? = null) {
            if (finished) return
            finished = true
            app.unregisterActivityLifecycleCallbacks(callback)
            val afterOwner = app.existingPlaybackRuntime
            val after = afterOwner?.snapshot?.value
            val sameOwner = afterOwner === owner
            val sameEntry = afterOwner?.queue?.state?.value?.current?.entryId == entry
            val sameQueue = afterOwner?.queue?.state?.value?.sessionEntries?.map { it.entryId } == ids
            val playing = after?.isPlaying == true && afterOwner?.desiredPlay?.value == true
            val advanced = after != null && after.positionMs > before.positionMs
            out.writeText(JSONObject().put("status", if (reason == null && sameOwner && sameEntry && sameQueue && playing && advanced) "passed" else "failed")
                .put("reason", reason).put("same_owner", sameOwner).put("same_current_occurrence", sameEntry)
                .put("same_queue", sameQueue).put("playing_after_recreate", playing).put("position_advanced", advanced)
                .put("before_position_ms", before.positionMs).put("after_position_ms", after?.positionMs)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("queue_entries", JSONArray(ids)).toString(2))
        }
        callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name != "dev.amatv.MainActivity" || finished) return
                if (!issued) {
                    issued = true
                    handler.post { activity.recreate() }
                } else handler.postDelayed({ complete() }, 1_500)
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callback)
        handler.postDelayed({ complete("Activity recreation timed out") }, 15_000)
    }
}
