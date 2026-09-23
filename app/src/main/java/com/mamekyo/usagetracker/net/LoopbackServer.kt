package com.mamekyo.usagetracker.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal loopback HTTP listener that receives the OAuth redirect
 * (`http://localhost:<port><path>?code=...&state=...`) from the browser on this device.
 * Pass port 0 to let the system pick a free port.
 */
class LoopbackServer(
    private val requestedPort: Int,
    private val path: String,
    private val pages: Pages,
) : Closeable {
    /** Localized text for the small pages shown in the browser after the redirect. */
    data class Pages(
        val successTitle: String,
        val successBody: String,
        val failedTitle: String,
        val mismatchTitle: String,
        val mismatchBody: String,
        val missingCode: String,
        val notFound: String,
        val returnLabel: String,
    )

    private val sockets = mutableListOf<ServerSocket>()

    /** Binds IPv4 loopback (required) and IPv6 loopback on the same port (best effort); returns the port. */
    fun start(): Int {
        val primary = bind("127.0.0.1", requestedPort)
            ?: throw LoginException(LoginException.Reason.PORT_UNAVAILABLE, requestedPort.toString())
        sockets += primary
        bind("::1", primary.localPort)?.let { sockets += it }
        return primary.localPort
    }

    private fun bind(host: String, port: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(host), port))
        }
    } catch (e: IOException) {
        null
    }

    /** Suspends until a redirect carrying [expectedState] arrives; returns the authorization code. */
    suspend fun awaitCode(expectedState: String): String = coroutineScope {
        val result = CompletableDeferred<String>()
        sockets.forEach { server -> launch(Dispatchers.IO) { acceptLoop(server, expectedState, result) } }
        try {
            result.await()
        } finally {
            close()
        }
    }

    private fun acceptLoop(server: ServerSocket, expectedState: String, result: CompletableDeferred<String>) {
        while (!result.isCompleted && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            runCatching { socket.use { handle(it, expectedState, result) } }
        }
    }

    private fun handle(socket: Socket, expectedState: String, result: CompletableDeferred<String>) {
        socket.soTimeout = 10_000
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        val url = "http://localhost$target".toHttpUrlOrNull()
        if (url == null || url.encodedPath != path) {
            respond(socket, 404, page(pages.notFound, ""))
            return
        }
        val error = url.queryParameter("error")
        val code = url.queryParameter("code")
        when {
            error != null -> {
                val detail = url.queryParameter("error_description") ?: error
                respond(socket, 400, page(pages.failedTitle, detail))
                result.completeExceptionally(LoginException(LoginException.Reason.DENIED, detail))
            }
            url.queryParameter("state") != expectedState ->
                respond(socket, 400, page(pages.mismatchTitle, pages.mismatchBody))
            code.isNullOrBlank() -> respond(socket, 400, page(pages.failedTitle, pages.missingCode))
            else -> {
                respond(socket, 200, page(pages.successTitle, pages.successBody, showReturn = true))
                result.complete(code)
            }
        }
    }

    private fun respond(socket: Socket, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Bad Request"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun page(title: String, message: String, showReturn: Boolean = false): String {
        val button = if (showReturn) {
            """<p><a href="usagetracker://login-complete" style="display:inline-block;padding:12px 24px;border-radius:24px;background:#10a37f;color:#fff;text-decoration:none">${pages.returnLabel.escapeHtml()}</a></p>"""
        } else {
            ""
        }
        return """<!doctype html><html><head><meta charset="utf-8">""" +
            """<meta name="viewport" content="width=device-width,initial-scale=1"><title>${title.escapeHtml()}</title></head>""" +
            """<body style="font-family:sans-serif;text-align:center;padding:48px 24px">""" +
            """<h2>${title.escapeHtml()}</h2><p>${message.escapeHtml()}</p>$button</body></html>"""
    }

    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
    }
}
