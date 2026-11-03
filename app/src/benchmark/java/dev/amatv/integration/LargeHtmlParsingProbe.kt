package dev.amatv.integration

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Benchmark-only synthetic input. No network, account, playback or provider access. */
internal object LargeHtmlParsingProbe {
    suspend fun run(context: Context): JSONObject = withContext(Dispatchers.Default) {
        val fixture = JSONObject(context.assets.open("public-html-stress-cases.json").bufferedReader().use { it.readText() })
        val cases = fixture.getJSONArray("cases")
        val rows = JSONArray()
        val outputDirectory = File(context.getExternalFilesDir(null) ?: context.filesDir, "large-html")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs())
        val output = File(outputDirectory, "probe-${SystemClock.elapsedRealtimeNanos()}.json")
        val report = JSONObject().put("status", "running").put("fixture", "deterministic_synthetic_public_html")
            .put("production_parsers", true).put("network_or_account_access", false)
            .put("process_64_bit", Process.is64Bit()).put("target_html_bytes", fixture.getInt("target_html_bytes"))
            .put("noise_count", fixture.getInt("noise_count")).put("cases", rows).put("report_file", output.absolutePath)
        val capabilities = CatalogPresentationCapabilities(
            NativeMediaCapabilities(songItemType = 1, albumContainerType = 2, playlistContainerType = 3),
            stationWebPlaybackAvailable = true,
        )
        fun save() { output.writeText(report.toString(2)) }
        fun parse(kind: String, html: String): List<*> = when (kind) {
            "new" -> PublicMusicBrowsePage.parse(html, "tw", capabilities)
            "radio" -> PublicMusicBrowsePage.parseRadio(html, "tw", capabilities)
            "editorial" -> PublicEditorialMotionPage.parse(html, "tw", "album", "1553944254")
            else -> error("Unknown synthetic fixture")
        }
        save()
        try {
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val kind = case.getString("kind")
                val row = JSONObject().put("kind", kind).put("phase", "base")
                rows.put(row); save()
                val base = case.getString("html")
                val expected = parse(kind, base)
                check(expected.isNotEmpty()) { "Synthetic baseline is not valid" }
                row.put("phase", "building_large_input"); save()
                val expanded = large(base, fixture.getInt("target_html_bytes"), fixture.getInt("noise_count"))
                val bytes = expanded.toByteArray(Charsets.UTF_8).size
                check(bytes == fixture.getInt("target_html_bytes"))
                row.put("input_bytes", bytes).put("phase", "parsing_large_input")
                    .put("memory_before", memory())
                save() // A native abort still leaves the exact in-flight case/phase.
                val start = System.nanoTime()
                val actual = parse(kind, expanded)
                row.put("parse_elapsed_ms", (System.nanoTime() - start) / 1_000_000.0)
                    .put("memory_after", memory())
                check(expected == actual) { "Large $kind changed production parser output" }
                row.put("output_count", actual.size).put("output_equal_to_base", true).put("phase", "passed")
                save()
            }
            report.put("status", "passed")
        } catch (failure: Exception) {
            report.put("status", "failed").put("error_type", failure.javaClass.simpleName)
        } finally { save() }
        report
    }

    private fun large(html: String, target: Int, count: Int): String {
        val noise = buildString {
            repeat(count) { index ->
                append("<script data-noise='").append(index).append("' src='ignored.js'></script>")
                append("<img src='https://is1-ssl.mzstatic.com/image/thumb/noise/")
                    .append(index).append("/632x632cc.webp'>")
            }
        }
        val padding = target - html.toByteArray(Charsets.UTF_8).size - noise.length - 7
        require(padding > 0)
        val at = html.lastIndexOf("</body>")
        return html.substring(0, at) + noise + "<!--" + "x".repeat(padding) + "-->" + html.substring(at)
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject().put("java_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            .put("java_max_bytes", runtime.maxMemory()).put("native_allocated_bytes", Debug.getNativeHeapAllocatedSize())
    }
}
