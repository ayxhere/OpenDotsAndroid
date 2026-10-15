package imayx.opendots.node

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import imayx.opendots.MainActivity
import imayx.opendots.auth.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the embedded Node.js backend lifecycle.
 *
 * Hard platform facts this design respects:
 * - node::Start runs exactly once per process. A second start is refused
 *   (native returns START_ALREADY_RUNNING).
 * - There is no in-process stop: Node lives until the app process dies.
 *   "Stopping" the backend therefore means letting the process end; the
 *   foreground-service setting only controls whether we *keep* the process
 *   alive for scheduled tasks.
 * - If Node crashes, the only recovery is an app restart (offered in UI).
 *
 * Single-instance guarantee: an OS file lock (node-home/.node.lock) plus an
 * in-memory atomic flag. Health is polled on 127.0.0.1:4310 only.
 */
class NodeManager(private val appContext: Context) {

    sealed interface BackendState {
        data object Stopped : BackendState
        data object Extracting : BackendState
        data object Starting : BackendState
        data object WaitingForHealth : BackendState
        data object Ready : BackendState
        data class Failed(val reason: String) : BackendState
        data class Crashed(val exitCode: Int) : BackendState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<BackendState>(BackendState.Stopped)
    val state: StateFlow<BackendState> = _state.asStateFlow()

    private val _logTail = MutableStateFlow<List<String>>(emptyList())
    val logTail: StateFlow<List<String>> = _logTail.asStateFlow()

    private val startRequested = AtomicBoolean(false)
    private var lockFile: RandomAccessFile? = null
    private var fileLock: FileLock? = null

    val nodeHome: File get() = File(appContext.filesDir, "node-home")
    val backendDir: File get() = File(nodeHome, "backend")
    val databaseFile: File get() = File(nodeHome, "opendots.sqlite")
    val logFile: File get() = File(nodeHome, "logs/node.log")

    /**
     * Idempotent. Safe to call from every screen: the first call boots Node,
     * later calls just observe. Never throws.
     */
    fun ensureRunning() {
        if (!startRequested.compareAndSet(false, true)) return
        scope.launch {
            try {
                boot()
            } catch (t: Throwable) {
                _state.value = BackendState.Failed(t.message ?: "Unknown boot failure")
            }
        }
    }

    private suspend fun boot() = withContext(Dispatchers.IO) {
        nodeHome.mkdirs()
        File(nodeHome, "logs").mkdirs()

        _state.value = BackendState.Extracting
        val entry = ensureBackendExtracted()
            ?: run {
                _state.value = BackendState.Failed(
                    "Backend bundle is missing from the APK (assets/opendots-backend.zip). " +
                        "Reinstall the app or report this build as broken.",
                )
                return@withContext
            }

        // Cross-process single-instance guard. If the lock is held, another
        // process already runs Node: skip starting, just watch health.
        val lock = tryAcquireLock()
        val tokenStore = TokenStore(appContext)
        val ownerToken = tokenStore.getOrCreateOwnerToken()

        if (lock == null) {
            _state.value = BackendState.WaitingForHealth
            if (waitForHealthy(ownerToken)) {
                _state.value = BackendState.Ready
            } else {
                _state.value = BackendState.Failed(
                    "Another process holds the backend lock but the server is not responding.",
                )
            }
            return@withContext
        }

        _state.value = BackendState.Starting
        val env = buildEnv(ownerToken, tokenStore)
        val script = File(backendDir, entry)
        if (!script.exists()) {
            _state.value = BackendState.Failed("Backend entry not found: ${script.absolutePath}")
            return@withContext
        }

        val nodeThread = Thread({
            val code = NodeRuntime.nativeStartNode(
                nodeHome.absolutePath,
                script.absolutePath,
                emptyArray(),
                env,
                logFile.absolutePath,
            )
            onNodeExit(code)
        }, "opendots-node")
        nodeThread.isDaemon = true
        nodeThread.start()

        _state.value = BackendState.WaitingForHealth
        if (waitForHealthy(ownerToken)) {
            _state.value = BackendState.Ready
        } else {
            _state.value = BackendState.Failed(
                "Backend did not answer health checks within 30s. See runtime logs.",
            )
        }
        refreshLogTail()
    }

    private fun onNodeExit(code: Int) {
        if (code == NodeRuntime.START_ALREADY_RUNNING) {
            // We never start twice ourselves; treat as a bug signal.
            _state.value = BackendState.Failed("Duplicate Node start refused (already running).")
            return
        }
        // node::Start returned: the runtime died. It cannot be restarted
        // in this process; the user must restart the app.
        _state.value = BackendState.Crashed(code)
        releaseLock()
    }

    /** Schedules an app relaunch ~1s from now, then kills this process. */
    fun restartApp() {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExact(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pi,
        )
        Process.killProcess(Process.myPid())
    }

    fun refreshLogTail(lines: Int = 200) {
        scope.launch(Dispatchers.IO) {
            _logTail.value = try {
                if (!logFile.exists()) return@launch
                logFile.readLines().takeLast(lines)
            } catch (_: Throwable) {
                listOf("(could not read log file)")
            }
        }
    }

    // ---- internals ----

    private fun tryAcquireLock(): FileLock? {
        return try {
            val raf = RandomAccessFile(File(nodeHome, ".node.lock"), "rw")
            val lock = raf.channel.tryLock()
            if (lock == null) {
                raf.close()
                null
            } else {
                lockFile = raf
                fileLock = lock
                lock
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun releaseLock() {
        try { fileLock?.release() } catch (_: Throwable) {}
        try { lockFile?.close() } catch (_: Throwable) {}
        fileLock = null
        lockFile = null
    }

    /**
     * Extracts assets/opendots-backend.zip into node-home/backend when the
     * bundled version differs from what's on disk. Returns the server entry
     * path relative to backendDir, or null when the bundle is absent.
     */
    private fun ensureBackendExtracted(): String? {
        val bundledVersion = try {
            appContext.assets.open("opendots-backend-version.txt").bufferedReader().readText().trim()
        } catch (_: Throwable) {
            return null // bundle not packaged
        }
        val marker = File(backendDir, ".version")
        val current = try { marker.readText().trim() } catch (_: Throwable) { "" }
        if (current == bundledVersion && File(backendDir, BACKEND_ENTRY).exists()) {
            return BACKEND_ENTRY
        }
        if (backendDir.exists()) backendDir.deleteRecursively()
        backendDir.mkdirs()
        appContext.assets.open("opendots-backend.zip").use { input ->
            unzip(input, backendDir)
        }
        marker.writeText(bundledVersion)
        return if (File(backendDir, BACKEND_ENTRY).exists()) BACKEND_ENTRY else null
    }

    private fun unzip(input: java.io.InputStream, dest: File) {
        java.util.zip.ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                // Zip-slip protection: entries must stay inside dest.
                if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                    throw SecurityException("Blocked zip entry outside backend dir: ${entry.name}")
                }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun buildEnv(ownerToken: String, secrets: TokenStore): Array<String> {
        val env = mutableListOf(
            "HOST=127.0.0.1",
            "PORT=$BACKEND_PORT",
            "OWNER_TOKEN=$ownerToken",
            "DATABASE_PATH=${databaseFile.absolutePath}",
            "NODE_ENV=production",
            // Telemetry stays off unless the user opts in (Settings).
            "DO_NOT_TRACK=1",
            "COPILOTKIT_TELEMETRY_DISABLED=true",
        )
        // Provider credentials live backend-side only; absent = setup screen.
        val passthrough = mapOf(
            TokenStore.KEY_INTELLIGENCE_API_KEY to "INTELLIGENCE_API_KEY",
            TokenStore.KEY_OPENAI_API_KEY to "OPENAI_API_KEY",
            TokenStore.KEY_OPENAI_MODEL to "OPENAI_MODEL",
            TokenStore.KEY_OPENAI_BASE_URL to "OPENAI_BASE_URL",
            TokenStore.KEY_PARALLEL_API_KEY to "PARALLEL_API_KEY",
            TokenStore.KEY_VOICE_API_KEY to "VOICE_API_KEY",
            TokenStore.KEY_VOICE_MODEL to "VOICE_MODEL",
            TokenStore.KEY_BROWSER_SECRET to "BROWSER_SECRET",
            TokenStore.KEY_COMPUTER_SUPERVISOR_TOKEN to "COMPUTER_SUPERVISOR_TOKEN",
            TokenStore.KEY_COMPUTER_TOKEN to "COMPUTER_TOKEN",
        )
        for ((secretKey, envName) in passthrough) {
            secrets.getSecret(secretKey)?.takeIf { it.isNotBlank() }?.let {
                env += "$envName=$it"
            }
        }
        // Non-secret service URLs from plain prefs (Phase 8 settings).
        val prefs = appContext.getSharedPreferences("opendots_prefs", Context.MODE_PRIVATE)
        prefs.getString("browser_url", null)?.takeIf { it.isNotBlank() }?.let { env += "BROWSER_URL=$it" }
        prefs.getString("computer_supervisor_url", null)?.takeIf { it.isNotBlank() }?.let { env += "COMPUTER_SUPERVISOR_URL=$it" }
        prefs.getString("computer_namespace", null)?.takeIf { it.isNotBlank() }?.let { env += "COMPUTER_NAMESPACE=$it" }
        return env.toTypedArray()
    }

    /**
     * Polls the health endpoint until the server answers. Falls back to
     * /api/state: with OWNER_TOKEN set, a 401 there still proves the server
     * (and its auth middleware) is alive.
     */
    private fun waitForHealthy(ownerToken: String, timeoutMs: Long = 30_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (_state.value is BackendState.Crashed) return false
            if (probeHealth() || probeState(ownerToken)) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun probeHealth(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$BACKEND_PORT/api/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            conn.responseCode == 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun probeState(ownerToken: String): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$BACKEND_PORT/api/state").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $ownerToken")
            conn.responseCode == 200 || conn.responseCode == 401
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val BACKEND_PORT = 4310
        /** Server entry inside the backend bundle; verified at bundle build time. */
        const val BACKEND_ENTRY = "dist/server/server/index.js"

        @Volatile
        private var instance: NodeManager? = null

        fun get(context: Context): NodeManager =
            instance ?: synchronized(this) {
                instance ?: NodeManager(context.applicationContext).also { instance = it }
            }
    }
}
