package com.blizzardfire.pumpkinlauncher

import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

object EngineModuleManager {
    private const val OWNER = "Fahry-a"
    private const val REPOSITORY = "PumpkinLauncher"
    private const val PREFS = "pumpkin_engine_modules"
    private const val ACTIVE_VERSION = "active_version"
    private const val MODULE_ROOT = "engine/modules/pumpkin"
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

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
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

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
                if (assetName.isNotBlank() && downloadUrl.isNotBlank()) {
                    urls[assetName] = downloadUrl
                }
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

    fun install(context: Context, release: EngineRelease): InstalledEngine {
        val abi = deviceAbi()
        val manifestConnection = openConnection(release.manifestUrl)
        val manifestText = manifestConnection.inputStream.bufferedReader().use { it.readText() }
        manifestConnection.disconnect()
        val manifest = JSONObject(manifestText)

        if (manifest.optInt("schemaVersion") != 1 ||
            manifest.optString("engine") != "Pumpkin-MC"
        ) {
            throw IllegalStateException("Unsupported or invalid engine manifest.")
        }

        val asset = manifest.getJSONObject("assets").getJSONObject(abi)
        val assetName = asset.getString("name")
        val expectedSha256 = asset.getString("sha256").lowercase()
        val downloadUrl = release.assetUrls[assetName]
            ?: throw IllegalStateException("The release is missing $assetName.")

        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val root = File(context.filesDir, MODULE_ROOT)
        val versionDir = File(root, "versions/$safeTag")
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
        temporary.delete()
        val downloadConnection = openConnection(downloadUrl)
        try {
            downloadConnection.inputStream.use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
        } finally {
            downloadConnection.disconnect()
        }

        if (sha256(temporary) != expectedSha256) {
            temporary.delete()
            throw SecurityException("Engine checksum verification failed.")
        }

        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Could not replace the previous engine file.")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Could not install the downloaded engine library.")
        }
        if (!destination.setReadable(true, true) || !destination.setReadOnly()) {
            destination.delete()
            throw IllegalStateException("Could not make the engine library read-only.")
        }

        writeMetadata(versionDir, manifest, abi, release.tagName)
        return InstalledEngine(
            manifest.optString("version", release.tagName),
            manifest.optString("channel", if (release.prerelease) "nightly" else "release"),
            abi,
            versionDir
        )
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

    private fun writeMetadata(
        directory: File,
        manifest: JSONObject,
        abi: String,
        tag: String
    ) {
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
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("User-Agent", "PumpkinLauncher-Android")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IllegalStateException("GitHub returned HTTP $code.")
        }
        return connection
    }
}
