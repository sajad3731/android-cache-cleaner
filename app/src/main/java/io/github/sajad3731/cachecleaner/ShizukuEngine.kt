package io.github.sajad3731.cachecleaner

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Talks to Shizuku, which runs [ShellService] as shell or root. As shell, Android only lets it
 * trim every app's cache at once; as root, it can also clear one app at a time.
 */
object ShizukuEngine {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION_REQUEST = 7
    private const val SERVICE_VERSION = 1
    private const val ROOT_UID = 0

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun state(context: Context): State = when {
        // Sui (Shizuku inside a root manager) has a binder but no Shizuku app, so ask the binder first.
        !Shizuku.pingBinder() -> if (isInstalled(context)) State.NOT_RUNNING else State.NOT_INSTALLED
        Shizuku.isPreV11() -> State.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NO_PERMISSION
        else -> State.READY
    }

    /**
     * Shizuku hands its binder to an app when the app process starts. If Shizuku started later,
     * ask it again; the answer arrives through the binder-received listeners.
     */
    fun requestBinderIfMissing(context: Context) {
        if (Shizuku.pingBinder() || !isInstalled(context)) return
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(context)
        } catch (ignored: RuntimeException) {
        }
    }

    fun isReady(context: Context): Boolean = state(context) == State.READY

    fun isRoot(): Boolean = try {
        Shizuku.getUid() == ROOT_UID
    } catch (ignored: RuntimeException) {
        false
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (ignored: PackageManager.NameNotFoundException) {
        false
    }

    /** A process started in the background (a scheduled clean) gets Shizuku's binder a moment later. */
    fun awaitBinder(timeoutMs: Long): Boolean {
        if (Shizuku.pingBinder()) return true
        val latch = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { latch.countDown() }
        Shizuku.addBinderReceivedListenerSticky(listener, Handler(Looper.getMainLooper()))
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            Shizuku.removeBinderReceivedListener(listener)
        }
        return Shizuku.pingBinder()
    }

    /** Frees every app's cache. Blocks; call it off the main thread. */
    fun trimAll(context: Context): Boolean = withService(context) { it.trimCaches() == 0 } == true

    /** Clears each package's cache, root only. Returns how many succeeded. Blocks. */
    fun clearEach(context: Context, packages: List<String>, onProgress: (Int) -> Unit = {}): Int? {
        val userId = Process.myUserHandle().hashCode()
        return withService(context) { service ->
            var cleaned = 0
            packages.forEachIndexed { index, packageName ->
                if (Thread.currentThread().isInterrupted) return@forEachIndexed
                if (service.clearCache(packageName, userId) == 0) cleaned++
                onProgress(index + 1)
            }
            cleaned
        }
    }

    private fun <T> withService(context: Context, block: (IShellService) -> T): T? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Shizuku calls block; run them off the main thread" }
        val latch = CountDownLatch(1)
        var service: IShellService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) service = IShellService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(debuggable)
            .version(SERVICE_VERSION)
        return try {
            Shizuku.bindUserService(args, connection)
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null
            service?.let(block)
        } catch (ignored: Exception) {
            // RemoteException or InterruptedException: the binder died or Shizuku stopped between the check and the call.
            null
        } finally {
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (ignored: RuntimeException) {
            }
        }
    }

    private const val BIND_TIMEOUT_MS = 10_000L
}
