package imayx.opendots

import android.app.Application

class OpenDotsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The backend starts on demand from the UI (NodeManager.ensureRunning),
        // never unconditionally here: "start only when needed".
    }
}
