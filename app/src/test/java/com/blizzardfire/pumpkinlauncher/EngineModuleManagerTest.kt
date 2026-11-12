package com.blizzardfire.pumpkinlauncher

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class EngineModuleManagerTest {
    @Test
    fun parallelRangeDownloadReconstructsExactAsset() {
        val payload = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val server = startServer(payload, supportsRanges = true)
        val target = Files.createTempFile("pumpkin-engine", ".so").toFile()
        try {
            EngineModuleManager.downloadAsset(
                "http://127.0.0.1:${server.address.port}/engine",
                target,
                AtomicBoolean(false)
            )
            assertArrayEquals(payload, target.readBytes())
        } finally {
            server.stop(0)
            target.delete()
        }
    }

    @Test
    fun fallsBackToSingleConnectionWhenRangesAreUnsupported() {
        val payload = ByteArray(2 * 1024 * 1024) { (it % 239).toByte() }
        val server = startServer(payload, supportsRanges = false)
        val target = Files.createTempFile("pumpkin-engine-fallback", ".so").toFile()
        try {
            EngineModuleManager.downloadAsset(
                "http://127.0.0.1:${server.address.port}/engine",
                target,
                AtomicBoolean(false)
            )
            assertArrayEquals(payload, target.readBytes())
        } finally {
            server.stop(0)
            target.delete()
        }
    }

    @Test
    fun cancelledDownloadStopsBeforeOpeningConnections() {
        val target = Files.createTempFile("pumpkin-engine-cancel", ".so").toFile()
        try {
            assertThrows(EngineDownloadCancelledException::class.java) {
                EngineModuleManager.downloadAsset(
                    "http://127.0.0.1:1/engine",
                    target,
                    AtomicBoolean(true)
                )
            }
        } finally {
            target.delete()
        }
    }

    private fun startServer(payload: ByteArray, supportsRanges: Boolean): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/engine") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (supportsRanges && range != null && range.startsWith("bytes=")) {
                val bounds = range.removePrefix("bytes=").split("-")
                val start = bounds[0].toInt()
                val end = bounds[1].toInt().coerceAtMost(payload.lastIndex)
                val body = payload.copyOfRange(start, end + 1)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                exchange.sendResponseHeaders(206, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } else {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        }
        server.start()
        return server
    }
}
