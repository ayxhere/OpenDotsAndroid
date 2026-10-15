package imayx.opendots.node

/**
 * JNI bridge to the embedded Node.js runtime (digidem nodejs-mobile,
 * libnode.so). Mirrors the AyXWhatsApp embedding pattern.
 *
 * node::Start may run exactly ONCE per process. A second call returns
 * [START_ALREADY_RUNNING] immediately. There is no in-process stop:
 * the runtime lives until the app process dies.
 */
object NodeRuntime {
    const val START_ALREADY_RUNNING = -100

    init {
        System.loadLibrary("node")
        System.loadLibrary("odnode")
    }

    /**
     * Starts Node on the calling thread (blocks until Node exits).
     *
     * @param cwd working directory for the Node process (node-home).
     * @param scriptPath absolute path of the server entry JS file.
     * @param args extra argv entries after the script path.
     * @param env "KEY=VALUE" entries applied with setenv() before start.
     * @param logPath file the native stdout/stderr pump appends to.
     * @return process exit code, or [START_ALREADY_RUNNING].
     */
    @JvmStatic
    external fun nativeStartNode(
        cwd: String,
        scriptPath: String,
        args: Array<String>,
        env: Array<String>,
        logPath: String,
    ): Int
}
