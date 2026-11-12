package com.blizzardfire.pumpkinlauncher

import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class EngineRelease(
    val tagName: String,
    val name: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val manifestUrl: String,
    val assetUrls: Map<String, String>
)

data class InstalledEngine(
    val version: String,
    val channel: String,
    val abi: String,
    val directory: File
) {
    val library: File
        get() = File(directory, "libpumpkin_engine.so")
}

data class EngineDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val activeConnections: Int,
    val bytesPerSecond: Long
) {
    val percent: Int
        get() = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
}

class EngineDownloadCancelledException : Exception("Download cancelled.")

object EngineModuleManager {
    private const val OWNER = "Fahry-a"
    private const val REPOSITORY = "PumpkinLauncher"
    private const val PREFS = "pumpkin_engine_modules"
    private const val ACTIVE_VERSION = "active_version"
    private const val MODULE_ROOT = "engine/modules/pumpkin"
    private const val MAX_DOWNLOAD_CONNECTIONS = 25
    private const val MIN_BYTES_PER_CONNECTION = 2L * 1024L * 1024L
    private const val SMALL_DOWNLOAD_SINGLE_CONNECTION = 4L * 1024L * 1024L
    private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val activeConnections = ConcurrentHashMap<AtomicBoolean, MutableSet<HttpURLConnection>>()

    fun deviceAbi(): String {
        val processAbis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        return processAbis.firstOrNull { it in supportedAbis }
            ?: throw IllegalStateException("This app process does not have a supported Android ABI.")
    }

    fun fetchReleases(channel: String): List<EngineRelease> {
        require(channel == "release" || channel == "nightly")
        val connection = openConnection(
            "https://api.github.com/repos/$OWNER/$REPOSITORY/releases?per_page=50"
        )
        val body = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val releases = org.json.JSONArray(body)
        val result = mutableListOf<EngineRelease>()
        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            val tag = release.optString("tag_name")
            val prerelease = release.optBoolean("prerelease")
            if (!tag.startsWith("pumpkin-") || prerelease != (channel == "nightly")) continue

            val assets = release.optJSONArray("assets") ?: continue
            val urls = mutableMapOf<String, String>()
            var manifestUrl: String? = null
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                val assetName = asset.optString("name")
                val downloadUrl = asset.optString("browser_download_url")
                if (assetName == "manifest.json") manifestUrl = downloadUrl
                if (assetName.isNotBlank() && downloadUrl.isNotBlank()) urls[assetName] = downloadUrl
            }
            if (manifestUrl.isNullOrBlank()) continue
            result += EngineRelease(
                tagName = tag,
                name = release.optString("name", tag),
                publishedAt = release.optString("published_at"),
                prerelease = prerelease,
                manifestUrl = manifestUrl,
                assetUrls = urls
            )
        }
        return result
    }

    @Synchronized
    fun install(
        context: Context,
        release: EngineRelease,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (EngineDownloadProgress) -> Unit = {}
    ): InstalledEngine {
        checkNotCancelled(cancelled)
        val abi = deviceAbi()
        val manifestConnection = openConnection(release.manifestUrl, cancelled)
        val manifestText = try {
            manifestConnection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            manifestConnection.disconnect()
            unregisterConnection(cancelled, manifestConnection)
        }
        val manifest = JSONObject(manifestText)

        if (manifest.optInt("schemaVersion") != 1 || manifest.optString("engine") != "Pumpkin-MC") {
            throw IllegalStateException("Unsupported or invalid engine manifest.")
        }

        val asset = manifest.getJSONObject("assets").getJSONObject(abi)
        val assetName = asset.getString("name")
        val expectedSha256 = asset.getString("sha256").lowercase()
        if (expectedSha256.length != 64 || expectedSha256.any { it !in "0123456789abcdef" }) {
            throw SecurityException("The engine manifest contains an invalid SHA-256 checksum.")
        }
        if (assetName.isBlank() || assetName.contains('/') || assetName.contains('\\')) {
            throw SecurityException("The engine manifest contains an invalid asset name.")
        }
        val downloadUrl = release.assetUrls[assetName]
            ?: throw IllegalStateException("The release is missing $assetName.")
        if (URL(downloadUrl).protocol != "https") {
            throw SecurityException("Engine downloads must use HTTPS.")
        }

        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val versionDir = File(context.filesDir, "$MODULE_ROOT/versions/$safeTag")
        if (!versionDir.exists() && !versionDir.mkdirs()) {
            throw IllegalStateException("Could not create the engine module directory.")
        }

        val destination = File(versionDir, "libpumpkin_engine.so")
        if (destination.isFile && sha256(destination) == expectedSha256) {
            if (destination.canWrite() && !destination.setReadOnly()) {
                throw IllegalStateException("Could not make the installed engine library read-only.")
            }
            writeMetadata(versionDir, manifest, abi, release.tagName)
            return InstalledEngine(
                manifest.optString("version", release.tagName),
                manifest.optString("channel", if (release.prerelease) "nightly" else "release"),
                abi,
                versionDir
            )
        }

        val temporary = File(versionDir, "engine.download")
        if (temporary.exists()) temporary.delete()
        try {
            downloadAsset(downloadUrl, temporary, cancelled, onProgress)

            checkNotCancelled(cancelled)
            if (sha256(temporary) != expectedSha256) {
                throw SecurityException("Engine checksum verification failed.")
            }

            // Preserve the previous known-good library until the replacement is verified and installed.
            val backup = File(versionDir, "libpumpkin_engine.so.previous")
            if (backup.exists() && !backup.delete()) {
                throw IllegalStateException("Could not clear the previous engine backup.")
            }
            val hadPrevious = destination.exists()
            if (hadPrevious && !destination.renameTo(backup)) {
                throw IllegalStateException("Could not preserve the previous engine library.")
            }
            try {
                if (!temporary.renameTo(destination)) {
                    throw IllegalStateException("Could not install the downloaded engine library.")
                }
                if (!destination.setReadable(true, true) || !destination.setReadOnly()) {
                    throw IllegalStateException("Could not make the engine library read-only.")
                }
                writeMetadata(versionDir, manifest, abi, release.tagName)
                if (backup.exists()) backup.delete()
            } catch (installError: Exception) {
                destination.setWritable(true, true)
                destination.delete()
                if (hadPrevious && backup.exists() && !backup.renameTo(destination)) {
                    throw IllegalStateException("Installation failed and the previous engine could not be restored.", installError)
                }
                throw installError
            }
            return InstalledEngine(
                manifest.optString("version", release.tagName),
                manifest.optString("channel", if (release.prerelease) "nightly" else "release"),
                abi,
                versionDir
            )
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    internal fun downloadAsset(
        url: String,
        destination: File,
        cancelled: AtomicBoolean,
        onProgress: (EngineDownloadProgress) -> Unit
    ) {
        checkNotCancelled(cancelled)
        val totalBytes = probeRangeSupport(url, cancelled)
        if (totalBytes == null || totalBytes < SMALL_DOWNLOAD_SINGLE_CONNECTION) {
            downloadSingle(url, destination, cancelled, onProgress)
            return
        }

        // 25 is a ceiling; avoid creating many tiny chunks on small assets.
        val connectionCount = minOf(
            MAX_DOWNLOAD_CONNECTIONS.toLong(),
            (totalBytes / MIN_BYTES_PER_CONNECTION).coerceAtLeast(1L)
        ).toInt().coerceAtLeast(1)
        require(connectionCount in 1..MAX_DOWNLOAD_CONNECTIONS) { "Invalid downloader connection count." }
        val chunkSize = (totalBytes + connectionCount - 1L) / connectionCount
        val downloaded = AtomicLong(0L)
        val active = AtomicInteger(0)
        val lastCallback = AtomicLong(0L)
        val lastSpeedBytes = AtomicLong(0L)
        val lastSpeedTime = AtomicLong(System.nanoTime())

        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            val previous = lastCallback.get()
            if (!force && now - previous < 100_000_000L) return
            if (!lastCallback.compareAndSet(previous, now) && !force) return

            val bytes = downloaded.get().coerceAtMost(totalBytes)
            val elapsed = (now - lastSpeedTime.get()).coerceAtLeast(1L)
            val previousBytes = lastSpeedBytes.getAndSet(bytes)
            val speed = ((bytes - previousBytes).coerceAtLeast(0L) * 1_000_000_000L) / elapsed
            lastSpeedTime.set(now)
            onProgress(EngineDownloadProgress(bytes, totalBytes, active.get(), speed))
        }

        RandomAccessFile(destination, "rw").use { it.setLength(totalBytes) }
        val executor = Executors.newFixedThreadPool(connectionCount)
        val futures = mutableListOf<Future<Unit>>()
        try {
            for (index in 0 until connectionCount) {
                val start = index * chunkSize
                val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                if (start > end) continue

                futures += executor.submit(Callable {
                    var attempt = 0
                    while (true) {
                        checkNotCancelled(cancelled)
                        var chunkBytes = 0L
                        active.incrementAndGet()
                        try {
                            val connection = openRangeConnection(url, start, end, cancelled)
                            try {
                                if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                                    throw IllegalStateException("Server stopped supporting HTTP Range during download.")
                                }
                                val contentRange = connection.getHeaderField("Content-Range") ?: ""
                                val rangeParts = contentRange.removePrefix("bytes ").split('-', '/')
                                if (rangeParts.size != 3 ||
                                    rangeParts[0].toLongOrNull() != start ||
                                    rangeParts[1].toLongOrNull() != end ||
                                    rangeParts[2].toLongOrNull() != totalBytes
                                ) {
                                    throw IllegalStateException("Server returned a mismatched HTTP Range response.")
                                }
                                val responseLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
                                if (responseLength != null && responseLength != end - start + 1L) {
                                    throw IllegalStateException("HTTP Range response has an invalid Content-Length.")
                                }

                                RandomAccessFile(destination, "rw").use { output ->
                                    output.seek(start)
                                    connection.inputStream.use { input ->
                                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                                        val expectedChunkBytes = end - start + 1L
                                        while (chunkBytes < expectedChunkBytes) {
                                            checkNotCancelled(cancelled)
                                            val count = input.read(
                                                buffer,
                                                0,
                                                minOf(buffer.size.toLong(), expectedChunkBytes - chunkBytes).toInt()
                                            )
                                            if (count < 0) break
                                            output.write(buffer, 0, count)
                                            chunkBytes += count
                                            downloaded.addAndGet(count.toLong())
                                            report()
                                        }
                                        if (chunkBytes != expectedChunkBytes) {
                                            throw IllegalStateException("Incomplete download chunk ($chunkBytes/$expectedChunkBytes bytes).")
                                        }
                                    }
                                }
                            } finally {
                                connection.disconnect()
                                unregisterConnection(cancelled, connection)
                            }
                            report(force = true)
                            break
                        } catch (error: Exception) {
                            if (chunkBytes > 0L) downloaded.addAndGet(-chunkBytes)
                            if (cancelled.get() || error is InterruptedException) throw EngineDownloadCancelledException()
                            attempt++
                            if (attempt >= 3) throw error
                            Thread.sleep(350L * attempt)
                        } finally {
                            active.decrementAndGet()
                            report(force = true)
                        }
                    }
                    Unit
                })
            }
            futures.forEach { it.get() }
            checkNotCancelled(cancelled)
            onProgress(EngineDownloadProgress(totalBytes, totalBytes, 0, 0L))
        } catch (error: Exception) {
            futures.forEach { it.cancel(true) }
            if (cancelled.get() || error is InterruptedException) throw EngineDownloadCancelledException()
            val cause = error.cause
            if (cause is Exception) throw cause
            throw error
        } finally {
            executor.shutdownNow()
        }
    }

    private fun downloadSingle(
        url: String,
        destination: File,
        cancelled: AtomicBoolean,
        onProgress: (EngineDownloadProgress) -> Unit
    ) {
        val connection = openConnection(url, cancelled)
        val total = connection.contentLengthLong
        val started = System.nanoTime()
        var downloaded = 0L
        try {
            connection.inputStream.use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        checkNotCancelled(cancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val elapsed = (System.nanoTime() - started).coerceAtLeast(1L)
                        val speed = downloaded * 1_000_000_000L / elapsed
                        onProgress(EngineDownloadProgress(downloaded, total, 1, speed))
                    }
                }
            }
        } finally {
            connection.disconnect()
            unregisterConnection(cancelled, connection)
        }
        checkNotCancelled(cancelled)
        onProgress(EngineDownloadProgress(downloaded, total, 0, 0L))
    }

    private fun probeRangeSupport(url: String, cancelled: AtomicBoolean): Long? {
        val connection = try {
            openRangeConnection(url, 0L, 0L, cancelled)
        } catch (error: Exception) {
            if (cancelled.get()) throw EngineDownloadCancelledException()
            return null
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) return null
            val contentRange = connection.getHeaderField("Content-Range") ?: return null
            val rangeParts = contentRange.removePrefix("bytes ").split('-', '/')
            if (rangeParts.size != 3 || rangeParts[0].toLongOrNull() != 0L || rangeParts[1].toLongOrNull() != 0L) {
                return null
            }
            val total = rangeParts[2].toLongOrNull() ?: return null
            val probeLength = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (total <= 0L || (probeLength != null && probeLength != 1L)) null else total
        } finally {
            connection.disconnect()
            unregisterConnection(cancelled, connection)
        }
    }

    private fun openRangeConnection(
        url: String,
        start: Long,
        end: Long,
        cancelled: AtomicBoolean? = null
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 90_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "PumpkinLauncher-Android")
        connection.setRequestProperty("Range", "bytes=$start-$end")
        if (cancelled != null) registerConnection(cancelled, connection)
        try {
            connection.connect()
            if (cancelled?.get() == true) throw EngineDownloadCancelledException()
            return connection
        } catch (error: Exception) {
            connection.disconnect()
            if (cancelled != null) unregisterConnection(cancelled, connection)
            throw error
        }
    }

    fun cancelDownload(cancelled: AtomicBoolean) {
        cancelled.set(true)
        activeConnections.remove(cancelled)?.forEach { it.disconnect() }
    }

    private fun registerConnection(cancelled: AtomicBoolean, connection: HttpURLConnection) {
        val connections = activeConnections.computeIfAbsent(cancelled) {
            ConcurrentHashMap.newKeySet<HttpURLConnection>()
        }
        connections.add(connection)
        if (cancelled.get()) {
            connection.disconnect()
            connections.remove(connection)
            throw EngineDownloadCancelledException()
        }
    }

    private fun unregisterConnection(cancelled: AtomicBoolean, connection: HttpURLConnection) {
        activeConnections[cancelled]?.let { connections ->
            connections.remove(connection)
            if (connections.isEmpty()) activeConnections.remove(cancelled, connections)
        }
    }

    private fun checkNotCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw EngineDownloadCancelledException()
        }
    }

    fun activate(context: Context, versionTag: String) {
        val safeTag = versionTag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val versionDir = File(context.filesDir, "$MODULE_ROOT/versions/$safeTag")
        val library = File(versionDir, "libpumpkin_engine.so")
        if (!library.isFile || library.canWrite()) {
            throw IllegalStateException("The selected engine is missing or is not read-only.")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_VERSION, versionTag)
            .apply()
    }

    fun activeVersion(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE_VERSION, null)

    fun activeLibrary(context: Context): File? {
        val version = activeVersion(context) ?: return null
        val safeTag = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(context.filesDir, "$MODULE_ROOT/versions/$safeTag/libpumpkin_engine.so")
        return file.takeIf { it.isFile && !it.canWrite() }
    }

    fun installed(context: Context): List<InstalledEngine> {
        val versionsDir = File(context.filesDir, "$MODULE_ROOT/versions")
        return versionsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "module.json").isFile }
            ?.mapNotNull { directory ->
                try {
                    val metadata = JSONObject(File(directory, "module.json").readText())
                    val engine = InstalledEngine(
                        metadata.optString("version", directory.name),
                        metadata.optString("channel", "unknown"),
                        metadata.optString("abi", ""),
                        directory
                    )
                    engine.takeIf { it.library.isFile && !it.library.canWrite() }
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.directory.lastModified() }
            ?: emptyList()
    }

    private fun writeMetadata(directory: File, manifest: JSONObject, abi: String, tag: String) {
        val metadata = JSONObject()
            .put("tag", tag)
            .put("version", manifest.optString("version", tag))
            .put("channel", manifest.optString("channel", "unknown"))
            .put("abi", abi)
            .put("sourceSha", manifest.optString("sourceSha"))
        File(directory, "module.json").writeText(metadata.toString(2))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun openConnection(url: String, cancelled: AtomicBoolean? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "PumpkinLauncher-Android")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (cancelled != null) registerConnection(cancelled, connection)
        try {
            connection.connect()
            if (cancelled?.get() == true) throw EngineDownloadCancelledException()
            if (connection.responseCode !in 200..299) {
                val code = connection.responseCode
                throw IllegalStateException("GitHub returned HTTP $code.")
            }
            return connection
        } catch (error: Exception) {
            connection.disconnect()
            if (cancelled != null) unregisterConnection(cancelled, connection)
            throw error
        }
    }
}
