package io.github.sajad3731.cachecleaner

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs inside the process Shizuku starts for this app, as shell (adb mode) or root. Commands
 * started here inherit that identity, which is what lets them reach other apps' caches.
 */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    // Asking for far more space than any phone has makes Android delete every app's cache.
    override fun trimCaches(): Int = run(TRIM_TIMEOUT_SECONDS, "pm", "trim-caches", "4096G")

    // As shell, Android ignores this request and pm waits forever, so callers use it only as root.
    override fun clearCache(packageName: String, userId: Int): Int = run(
        CLEAR_TIMEOUT_SECONDS, "pm", "clear", "--user", userId.toString(), "--cache-only", packageName
    )

    private fun run(timeoutSeconds: Long, vararg command: String): Int {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .redirectOutput(File("/dev/null"))
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return EXIT_TIMEOUT
        }
        return process.exitValue()
    }

    private companion object {
        const val TRIM_TIMEOUT_SECONDS = 120L
        const val CLEAR_TIMEOUT_SECONDS = 20L
        const val EXIT_TIMEOUT = 124
    }
}
