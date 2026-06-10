package io.liriliri.aya

import android.net.LocalServerSocket
import android.util.Log
import java.util.concurrent.Executors

class Server {
    companion object {
        private const val TAG = "Aya.Server"

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                Server().start()
            } catch (e: Exception) {
                Log.e(TAG, "Fail to start server", e)
            }
        }
    }

    private val executor = Executors.newCachedThreadPool()
    fun start() {
        val server = LocalServerSocket("aya")

        while (true) {
            val conn = Connection(server.accept())
            executor.submit(conn)
        }
    }
}
