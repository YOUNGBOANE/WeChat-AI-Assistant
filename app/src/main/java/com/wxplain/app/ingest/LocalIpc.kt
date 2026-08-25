package com.wxplain.app.ingest

import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object LocalIpc {
    const val HOST = "127.0.0.1"
    const val PORT = 17864
    private const val TOKEN = "wxplain-ipc-v1"
    private const val TAG = "wxplain-ipc"
    private val started = AtomicBoolean(false)

    fun start(appContext: Context) {
        if (!started.compareAndSet(false, true)) return
        val ctx = appContext.applicationContext
        thread(name = "wxplain-ipc", isDaemon = true) {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(InetAddress.getByName(HOST), PORT))
                    Log.i(TAG, "listening $HOST:$PORT")
                    while (true) {
                        val socket = try {
                            server.accept()
                        } catch (_: Throwable) {
                            break
                        }
                        thread(name = "wxplain-ipc-req", isDaemon = true) {
                            handleClient(ctx, socket)
                        }
                    }
                }
            } catch (t: Throwable) {
                started.set(false)
                Log.e(TAG, "server failed: ${t.message}")
            }
        }
    }

    fun call(method: String, extras: Bundle, readTimeoutMs: Int = 90_000): Bundle {
        val req = bundleToJson(extras)
            .put("token", TOKEN)
            .put("method", method)
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(HOST, PORT), 2_000)
                socket.soTimeout = readTimeoutMs
                writeFrame(socket.getOutputStream(), req.toString())
                return jsonToBundle(JSONObject(readFrame(socket.getInputStream())))
            }
        } catch (t: Throwable) {
            error("连不上助手，请先打开 Wechat AI Assistant")
        }
    }

    private fun handleClient(ctx: Context, socket: Socket) {
        socket.use { s ->
            try {
                val remote = s.inetAddress
                if (remote == null || !remote.isLoopbackAddress) return
                s.soTimeout = 90_000
                val req = JSONObject(readFrame(s.getInputStream()))
                if (req.optString("token") != TOKEN) {
                    writeFrame(s.getOutputStream(), JSONObject().put("error", "无权调用").toString())
                    return
                }
                val method = req.optString("method")
                val extras = jsonToBundle(req, skip = setOf("token", "method"))
                val out = AssistantApi.handle(ctx, method, extras)
                writeFrame(s.getOutputStream(), bundleToJson(out).toString())
            } catch (t: Throwable) {
                Log.e(TAG, "handle failed: ${t.message}")
                try {
                    val msg = t.message ?: t.javaClass.simpleName
                    writeFrame(s.getOutputStream(), JSONObject().put("error", msg).toString())
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun writeFrame(out: OutputStream, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.size).array()
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun readFrame(inp: InputStream, max: Int = 2_000_000): String {
        val header = ByteArray(4)
        readFully(inp, header)
        val n = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (n <= 0 || n > max) error("bad frame")
        val body = ByteArray(n)
        readFully(inp, body)
        return String(body, Charsets.UTF_8)
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) error("closed")
            off += n
        }
    }

    @Suppress("DEPRECATION")
    private fun bundleToJson(b: Bundle): JSONObject {
        val o = JSONObject()
        for (key in b.keySet()) {
            when (val v = b.get(key)) {
                null -> {}
                is Boolean, is Int, is Long, is Double -> o.put(key, v)
                else -> o.put(key, v.toString())
            }
        }
        return o
    }

    private fun jsonToBundle(obj: JSONObject, skip: Set<String> = emptySet()): Bundle {
        val b = Bundle()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key in skip) continue
            when (val v = obj.get(key)) {
                JSONObject.NULL -> {}
                is Boolean -> b.putBoolean(key, v)
                is Int -> b.putInt(key, v)
                is Long -> b.putLong(key, v)
                is Double -> b.putDouble(key, v)
                else -> b.putString(key, v.toString())
            }
        }
        return b
    }
}
