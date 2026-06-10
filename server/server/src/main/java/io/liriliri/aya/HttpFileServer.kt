package io.liriliri.aya

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.URLDecoder

class HttpFileServer(port: Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val decoded = URLDecoder.decode(session.uri, "UTF-8")
        val target = File(decoded)

        if (!target.exists() || target.isDirectory) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not Found")
        }

        val fis = FileInputStream(target)
        val mime = getMimeTypeForFile(target.name)
        return newFixedLengthResponse(Status.OK, mime, fis, target.length())
    }
}

object HttpFileServerManager {
    @Volatile
    private var server: HttpFileServer? = null

    @Synchronized
    fun start(): Int {
        val current = server
        if (current != null && isServerHealthy(current)) {
            return current.listeningPort
        }

        stop()
        var port = 9001
        repeat(100) {
            if (isPortAvailable(port)) {
                val srv = HttpFileServer(port)
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                val listeningPort = srv.listeningPort
                if (listeningPort > 0) {
                    server = srv
                    return listeningPort
                }
                srv.stop()
            }
            port++
        }
        throw IllegalStateException("No available port found")
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    private fun isServerHealthy(srv: HttpFileServer): Boolean {
        return srv.wasStarted() && srv.listeningPort > 0
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
