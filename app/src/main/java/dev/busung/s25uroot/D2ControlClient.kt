package dev.busung.s25uroot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import dev.experimental.rmgu.d2.D2Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** One short binding per UI query; never starts a permanent or foreground service. */
internal class D2ControlClient private constructor(private val context: Context) {
    private val connected = CompletableDeferred<Messenger>()
    private var pending: CompletableDeferred<Bundle>? = null
    private var serial = 0
    private var bound = false
    private val handler = Handler(Looper.getMainLooper()) { response ->
        if (response.what == D2Protocol.RESPONSE && response.arg1 == serial && response.sendingUid == 1000) {
            pending?.complete(Bundle(response.data))
        }
        true
    }
    private val replyTo = Messenger(handler)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connected.complete(Messenger(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) = failed("D2 helper disconnected; check status before retrying")
        override fun onBindingDied(name: ComponentName) = failed("D2 helper changed; fully reboot before retrying")
        override fun onNullBinding(name: ComponentName) = failed("D2 helper refused the control connection")
    }
    private fun failed(message: String) {
        val error = IOException(message)
        connected.completeExceptionally(error)
        pending?.completeExceptionally(error)
    }

    suspend fun call(method: String, source: String, version: Int, enabled: Boolean? = null): Bundle {
        check(pending == null) { "A D2 request is already in progress" }
        val response = CompletableDeferred<Bundle>()
        pending = response
        try {
            val request = Message.obtain().apply {
                what = D2Protocol.REQUEST
                arg1 = ++serial
                replyTo = this@D2ControlClient.replyTo
                data = Bundle().apply {
                    putInt("api", D2Protocol.API)
                    putString("method", method)
                    putString("source", source)
                    putInt("version", version)
                    putLong("deadline_ms", SystemClock.elapsedRealtime() + 5000)
                    enabled?.let { putBoolean("enabled", it) }
                }
            }
            connected.await().send(request)
            return withTimeout(5000) { response.await() }
        } catch (error: TimeoutCancellationException) {
            throw IOException("D2 helper did not reply. The switch is unconfirmed; check status before retrying.", error)
        } finally { pending = null }
    }

    companion object {
        suspend fun <T> use(context: Context, block: suspend (D2ControlClient) -> T): T =
            withContext(Dispatchers.Main.immediate) {
                val client = D2ControlClient(context.applicationContext)
                try {
                    client.bound = client.context.bindService(
                        Intent().setComponent(ComponentName(D2Protocol.HELPER, D2Protocol.SERVICE)),
                        client.connection, Context.BIND_AUTO_CREATE,
                    )
                    if (!client.bound) throw IOException("D2 control service is unavailable. Update the embedded helper and fully reboot.")
                    try { withTimeout(5000) { client.connected.await() } }
                    catch (error: TimeoutCancellationException) {
                        throw IOException("Could not connect to the D2 helper. Fully reboot, activate the helper and check status.", error)
                    }
                    block(client)
                } finally {
                    if (client.bound) runCatching { client.context.unbindService(client.connection) }
                    client.handler.removeCallbacksAndMessages(null)
                    client.pending?.cancel()
                    client.connected.cancel()
                }
            }
    }
}
