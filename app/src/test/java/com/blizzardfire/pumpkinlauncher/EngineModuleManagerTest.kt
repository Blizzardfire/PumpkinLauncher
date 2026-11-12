package com.blizzardfire.pumpkinlauncher

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun retriesTransientRangeRequestFailure() {
        val payload = ByteArray(8 * 1024 * 1024) { (it % 223).toByte() }
        val server = startServer(payload, supportsRanges = true, failFirstRange = true)
        val target = Files.createTempFile("pumpkin-engine-retry", ".so").toFile()
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

    @Test
    fun extractLibraryReconstructsLibraryBytes() {
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val archive = Files.createTempFile("pumpkin-engine-pkg", ".zip").toFile()
        val target = File(archive.parentFile, "pumpkin-engine-out.so")
        try {
            writeZip(archive, mapOf("libpumpkin_engine-arm64-v8a.so" to payload))
            EngineModuleManager.extractLibrary(archive, target, AtomicBoolean(false))
            assertArrayEquals(payload, target.readBytes())
        } finally {
            archive.delete()
            target.delete()
        }
    }

    @Test
    fun extractLibraryRejectsTraversalEntries() {
        val archive = Files.createTempFile("pumpkin-engine-traversal", ".zip").toFile()
        val target = File(archive.parentFile, "pumpkin-engine-traversal.so")
        try {
            writeZip(archive, mapOf("../evil.so" to byteArrayOf(1, 2, 3)))
            assertThrows(SecurityException::class.java) {
                EngineModuleManager.extractLibrary(archive, target, AtomicBoolean(false))
            }
            assertFalse(target.exists())
        } finally {
            archive.delete()
            target.delete()
        }
    }

    @Test
    fun extractLibraryRejectsArchivesWithoutALibrary() {
        val archive = Files.createTempFile("pumpkin-engine-empty", ".zip").toFile()
        val target = File(archive.parentFile, "pumpkin-engine-empty.so")
        try {
            writeZip(archive, mapOf("notes.txt" to byteArrayOf(1)))
            assertThrows(SecurityException::class.java) {
                EngineModuleManager.extractLibrary(archive, target, AtomicBoolean(false))
            }
            assertFalse(target.exists())
        } finally {
            archive.delete()
            target.delete()
        }
    }

    @Test
    fun channelForMapsTagsToChannels() {
        assertEquals("release", EngineModuleManager.channelFor("pumpkin-0.2.2+26.3-26.60", false))
        assertNull(EngineModuleManager.channelFor("pumpkin-0.2.2+26.3-26.60", true))
        assertEquals("nightly", EngineModuleManager.channelFor("nightly", true))
        assertNull(EngineModuleManager.channelFor("v1.2.3", false))
        assertNull(EngineModuleManager.channelFor("canary", true))
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun startServer(payload: ByteArray, supportsRanges: Boolean, failFirstRange: Boolean = false): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val rangeRequests = AtomicInteger(0)
        server.createContext("/engine") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (supportsRanges && range != null && range.startsWith("bytes=")) {
                if (failFirstRange && rangeRequests.incrementAndGet() == 2) {
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                    return@createContext
                }
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
