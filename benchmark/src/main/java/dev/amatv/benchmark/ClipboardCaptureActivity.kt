package dev.amatv.benchmark

import android.app.Activity
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import java.util.concurrent.CountDownLatch

/** Tester process only. Clipboard access happens only after this real window owns focus. */
class ClipboardCaptureActivity : Activity() {
    private var captured = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(intent.getBooleanExtra("allow_readonly_parity", false))
        setContentView(TextView(this).apply { text = "Clipboard verification" })
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || captured) return
        captured = true
        try {
            check(hasWindowFocus())
            checkNotNull(operation)(getSystemService(ClipboardManager::class.java))
        } catch (error: Throwable) { failure = error }
        finally { operation = null; completed.countDown(); finish() }
    }
    companion object {
        @Volatile internal var operation: ((ClipboardManager) -> Unit)? = null
        @Volatile internal var failure: Throwable? = null
        @Volatile internal var completed = CountDownLatch(0)
    }
}
