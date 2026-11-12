package com.blizzardfire.pumpkinlauncher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EngineManagerActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var downloadCancellation: AtomicBoolean? = null
    private var isDownloading = false

    private lateinit var statusText: TextView
    private lateinit var sectionLabel: TextView
    private lateinit var emptyText: TextView
    private lateinit var adapter: EngineAdapter
    private lateinit var tabButtons: Map<String, Button>
    private lateinit var progressCard: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressDetail: TextView
    private lateinit var engineProgressBar: ProgressBar
    private lateinit var progressPercent: TextView
    private lateinit var progressStats: TextView
    private lateinit var cancelButton: Button

    private var selectedChannel = "release"

    private sealed class Row {
        abstract val active: Boolean

        data class Release(
            val release: EngineRelease,
            val installed: InstalledEngine?,
            override val active: Boolean
        ) : Row()

        data class Installed(
            val engine: InstalledEngine,
            override val active: Boolean
        ) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_manager)

        statusText = findViewById(R.id.statusText)
        sectionLabel = findViewById(R.id.sectionLabel)
        emptyText = findViewById(R.id.emptyText)
        progressCard = findViewById(R.id.progressCard)
        progressTitle = findViewById(R.id.progressTitle)
        progressDetail = findViewById(R.id.progressDetail)
        engineProgressBar = findViewById(R.id.engineProgressBar)
        progressPercent = findViewById(R.id.progressPercent)
        progressStats = findViewById(R.id.progressStats)
        cancelButton = findViewById(R.id.cancelButton)

        findViewById<TextView>(R.id.deviceAbiText).text =
            runCatching { EngineModuleManager.deviceAbi() }.getOrDefault("Unsupported ABI")

        adapter = EngineAdapter()
        recyclerView().layoutManager = LinearLayoutManager(this)
        recyclerView().adapter = adapter

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        tabButtons = mapOf(
            "release" to findViewById<Button>(R.id.releasesTabButton),
            "nightly" to findViewById<Button>(R.id.nightlyTabButton),
            "installed" to findViewById<Button>(R.id.installedTabButton)
        )
        tabButtons.forEach { (channel, button) ->
            button.setOnClickListener { selectTab(channel) }
        }

        selectTab("release")
    }

    private fun recyclerView(): RecyclerView = findViewById(R.id.engineRecyclerView)

    private fun selectTab(channel: String) {
        if (isDownloading) return
        selectedChannel = channel
        tabButtons.forEach { (key, button) -> button.alpha = if (key == channel) 1f else 0.55f }
        if (channel == "installed") showInstalled() else loadChannel(channel)
    }

    private fun loadChannel(channel: String) {
        if (isDownloading) return
        sectionLabel.text = if (channel == "nightly") "NIGHTLY BUILDS" else "AVAILABLE BUILDS"
        showRows(emptyList(), "Fetching release information…", "")
        statusText.text = "Active: ${EngineModuleManager.activeVersion(this) ?: "None"}  •  Loading releases…"

        executor.execute {
            try {
                val releases = EngineModuleManager.fetchReleases(channel)
                runOnUiThread {
                    if (isFinishing || channel != selectedChannel) return@runOnUiThread
                    val activeVersion = EngineModuleManager.activeVersion(this)
                    val installed = EngineModuleManager.installed(this)
                    val rows = releases.map { release ->
                        val match = installed.firstOrNull { it.directory.name == safeTag(release.tagName) }
                        Row.Release(
                            release,
                            match,
                            activeVersion == release.tagName || activeVersion == safeTag(release.tagName)
                        )
                    }
                    showRows(rows, "No $channel builds found", "Published engine releases will appear here.")
                    statusText.text = "Active: ${activeVersion ?: "None"}  •  ${releases.size} $channel build(s)"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || channel != selectedChannel) return@runOnUiThread
                    showRows(emptyList(), "Could not load builds", error.message ?: "Unknown network error")
                    statusText.text = "Release lookup failed"
                }
            }
        }
    }

    private fun showInstalled() {
        if (isDownloading) return
        sectionLabel.text = "INSTALLED ENGINES"
        val engines = EngineModuleManager.installed(this)
        val activeVersion = EngineModuleManager.activeVersion(this)
        val rows = engines.map { engine ->
            Row.Installed(engine, activeVersion?.let { safeTag(it) } == engine.directory.name)
        }
        showRows(rows, "No engines installed", "Choose Releases to find a published engine build.")
        statusText.text = "Active: ${activeVersion ?: "None"}  •  ${engines.size} installed"
    }

    private fun showRows(rows: List<Row>, title: String, description: String) {
        adapter.submit(rows)
        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = if (description.isBlank()) title else "$title\n\n$description"
    }

    private fun install(release: EngineRelease) {
        if (isDownloading) return
        if (PumpkinService.isServerActive) {
            Toast.makeText(this, "You can download an update, but stop the server before activating it.", Toast.LENGTH_LONG).show()
        }
        isDownloading = true
        val cancellation = AtomicBoolean(false)
        downloadCancellation = cancellation
        progressCard.visibility = View.VISIBLE
        progressTitle.text = "Downloading ${release.tagName}"
        progressDetail.text = "Preparing secure download…"
        engineProgressBar.isIndeterminate = true
        progressPercent.text = "—"
        progressStats.text = "Starting"
        cancelButton.isEnabled = true
        cancelButton.text = "Cancel download"
        cancelButton.setOnClickListener {
            downloadCancellation?.set(true)
            cancelButton.isEnabled = false
            cancelButton.text = "Cancelling…"
        }
        engineProgressBar.progress = 0
        statusText.text = "Downloading engine module…"

        executor.execute {
            try {
                val engine = EngineModuleManager.install(
                    this,
                    release,
                    cancelled = cancellation,
                    onProgress = { progress ->
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            engineProgressBar.isIndeterminate = progress.totalBytes <= 0L
                            if (progress.totalBytes > 0L) {
                                engineProgressBar.progress = progress.percent * 10
                                progressPercent.text = "${progress.percent}%"
                                progressDetail.text = "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
                            } else {
                                progressPercent.text = formatBytes(progress.downloadedBytes)
                                progressDetail.text = "Downloaded ${formatBytes(progress.downloadedBytes)}"
                            }
                            val speed = if (progress.bytesPerSecond > 0L) "  •  ${formatBytes(progress.bytesPerSecond)}/s" else ""
                            progressStats.text = "${progress.activeConnections} active connections$speed"
                        }
                    }
                )
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    isDownloading = false
                    downloadCancellation = null
                    progressCard.visibility = View.GONE
                    if (PumpkinService.isServerActive) {
                        selectTab(selectedChannel)
                        statusText.text = "Installed ${engine.version}. Stop the server before activating it."
                    } else {
                        EngineModuleManager.activate(this, release.tagName)
                        Toast.makeText(this, "Engine installed and activated", Toast.LENGTH_LONG).show()
                        if (PumpkinNative.isLoaded) {
                            Toast.makeText(this, "Restart PumpkinLauncher to load the selected engine.", Toast.LENGTH_LONG).show()
                        }
                        selectTab(selectedChannel)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    isDownloading = false
                    downloadCancellation = null
                    engineProgressBar.isIndeterminate = false
                    cancelButton.isEnabled = true
                    cancelButton.text = "Close"
                    cancelButton.setOnClickListener { progressCard.visibility = View.GONE }
                    val cancelled = error is EngineDownloadCancelledException || cancellation.get()
                    progressTitle.text = if (cancelled) "Download cancelled" else "Installation failed"
                    progressDetail.text = error.message ?: "The engine could not be installed."
                    progressPercent.text = if (cancelled) "Stopped" else "Error"
                    progressStats.text = "No engine was activated"
                    statusText.text = if (cancelled) "Download cancelled" else "Engine installation failed"
                }
            }
        }
    }

    private fun activate(tag: String) {
        if (PumpkinService.isServerActive) {
            Toast.makeText(this, "Stop the server before changing its engine.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            EngineModuleManager.activate(this, tag)
            selectTab(selectedChannel)
            if (PumpkinNative.isLoaded) {
                statusText.text = "Engine selected. Restart PumpkinLauncher to load the new version."
            }
        } catch (error: Exception) {
            statusText.text = "Activation failed: ${error.message}"
        }
    }

    private inner class EngineAdapter : RecyclerView.Adapter<EngineAdapter.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(value: List<Row>) {
            rows = value
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.releaseNameText)
            val tagText: TextView = view.findViewById(R.id.releaseTagText)
            val metaText: TextView = view.findViewById(R.id.releaseMetaText)
            val actionButton: Button = view.findViewById(R.id.releaseActionButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_engine_release, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = rows[position]
            val active = row.active
            when (row) {
                is Row.Release -> {
                    val release = row.release
                    holder.nameText.text = release.name.ifBlank { release.tagName }
                    holder.tagText.text = release.tagName
                    val state = when {
                        active -> "ACTIVE"
                        row.installed != null -> "INSTALLED"
                        else -> "NOT INSTALLED"
                    }
                    holder.metaText.text = "Published ${formatDate(release.publishedAt)}  •  $state"
                    holder.actionButton.text = when {
                        active -> "Currently active"
                        row.installed != null -> "Activate engine"
                        else -> "Download and install"
                    }
                    holder.actionButton.setOnClickListener {
                        if (row.installed != null) activate(row.installed.directory.name) else install(release)
                    }
                }
                is Row.Installed -> {
                    val engine = row.engine
                    holder.nameText.text = engine.version
                    holder.tagText.text = "${engine.channel.uppercase()}  •  ${engine.abi}"
                    holder.metaText.text = "${engine.library.name}  •  ${formatBytes(engine.library.length())}"
                    holder.actionButton.text = if (active) "Currently active" else "Activate engine"
                    holder.actionButton.setOnClickListener { activate(engine.directory.name) }
                }
            }
            holder.actionButton.isEnabled = !active
            holder.actionButton.alpha = if (active) 0.6f else 1f
        }

        override fun getItemCount() = rows.size
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Unknown size"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun formatDate(value: String): String =
        value.take(10).ifBlank { "Unknown date" }

    private fun safeTag(tag: String): String = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")

    override fun onDestroy() {
        downloadCancellation?.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
