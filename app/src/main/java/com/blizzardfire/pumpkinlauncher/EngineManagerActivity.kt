package com.blizzardfire.pumpkinlauncher

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EngineManagerActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var downloadCancellation: AtomicBoolean? = null
    private var isDownloading = false

    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var progressCard: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressDetail: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPercent: TextView
    private lateinit var progressStats: TextView
    private lateinit var cancelButton: Button

    private var selectedChannel = "release"

    private val screenBackground = Color.rgb(13, 15, 19)
    private val surface = Color.rgb(25, 29, 36)
    private val surfaceRaised = Color.rgb(34, 39, 48)
    private val primaryText = Color.rgb(244, 245, 247)
    private val secondaryText = Color.rgb(157, 165, 177)
    private val accent = Color.rgb(229, 117, 64)
    private val green = Color.rgb(101, 195, 98)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        updateStatus()
        loadChannel("release")
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setBackgroundColor(screenBackground)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(actionButton("‹", filled = false).apply {
            textSize = 25f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("ENGINE MANAGER", 19f, primaryText, true))
            addView(label("Pumpkin module center", 11f, secondaryText))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("25×", 12f, accent, true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(surfaceRaised, 14)
        })
        root.addView(header)

        val intro = card().apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            addView(label("Manage your engine separately", 14f, primaryText, true))
            addView(label(
                "Download, verify and activate Pumpkin engine builds without bundling them into the launcher APK.",
                12f, secondaryText
            ).apply { setPadding(0, dp(5), 0, 0) })
        }
        root.addView(intro, marginParams(top = 14, bottom = 10))

        val statusCard = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(this@EngineManagerActivity).apply {
                background = rounded(green, 8)
            }, LinearLayout.LayoutParams(dp(4), dp(34)))
            status = label("", 12f, primaryText).apply {
                setPadding(dp(11), 0, dp(4), 0)
            }
            addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("DEVICE", 9f, secondaryText, true))
        }
        root.addView(statusCard, marginParams(bottom = 10))

        val channelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        channelRow.addView(actionButton("Releases") { loadChannel("release") },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        channelRow.addView(actionButton("Nightly", filled = false) { loadChannel("nightly") },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5); marginEnd = dp(5) })
        channelRow.addView(actionButton("Installed", filled = false) { showInstalled() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
        root.addView(channelRow, marginParams(bottom = 10))

        progressCard = card().apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            visibility = View.GONE
            progressTitle = label("Downloading engine", 14f, primaryText, true)
            addView(progressTitle)
            progressDetail = label("", 11f, secondaryText).apply { setPadding(0, dp(4), 0, dp(10)) }
            addView(progressDetail)
            progressBar = ProgressBar(
                this@EngineManagerActivity, null, android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(surfaceRaised)
            }
            addView(progressBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(7)
            ))
            val progressRow = LinearLayout(this@EngineManagerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
                progressPercent = label("0%", 13f, primaryText, true)
                addView(progressPercent)
                progressStats = label("Preparing…", 10f, secondaryText).apply {
                    gravity = Gravity.END
                }
                addView(progressStats, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ))
            }
            addView(progressRow)
            cancelButton = actionButton("Cancel download", filled = false) {
                downloadCancellation?.set(true)
                cancelButton.isEnabled = false
                cancelButton.text = "Cancelling…"
            }
            addView(cancelButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
            ).apply { topMargin = dp(10) })
        }
        root.addView(progressCard, marginParams(bottom = 10))

        val sectionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("AVAILABLE BUILDS", 10f, secondaryText, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("PUMPKIN-MC", 9f, accent, true))
        }
        root.addView(sectionHeader, marginParams(bottom = 7))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun loadChannel(channel: String) {
        if (isDownloading) return
        selectedChannel = channel
        content.removeAllViews()
        status.text = "Active: ${EngineModuleManager.activeVersion(this) ?: "None"}  •  Loading $channel…"
        content.addView(label("Fetching release information…", 12f, secondaryText).apply {
            setPadding(dp(4), dp(18), dp(4), dp(18))
        })

        executor.execute {
            try {
                val releases = EngineModuleManager.fetchReleases(channel)
                runOnUiThread {
                    if (isFinishing || channel != selectedChannel) return@runOnUiThread
                    content.removeAllViews()
                    if (releases.isEmpty()) {
                        content.addView(emptyState("No $channel builds found", "Published engine releases will appear here."))
                    } else {
                        releases.forEach { addReleaseCard(it) }
                    }
                    status.text = "Active: ${EngineModuleManager.activeVersion(this) ?: "None"}  •  ${releases.size} $channel build(s)"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || channel != selectedChannel) return@runOnUiThread
                    content.removeAllViews()
                    content.addView(emptyState("Could not load builds", error.message ?: "Unknown network error"))
                    status.text = "Release lookup failed"
                }
            }
        }
    }

    private fun addReleaseCard(release: EngineRelease) {
        val installed = EngineModuleManager.installed(this)
            .firstOrNull { it.directory.name == safeTag(release.tagName) }
        val activeVersion = EngineModuleManager.activeVersion(this)
        val active = activeVersion == release.tagName || activeVersion == safeTag(release.tagName)

        val item = card().apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            addView(label(release.name.ifBlank { release.tagName }, 15f, primaryText, true))
            addView(label(release.tagName, 11f, accent, true).apply {
                setPadding(0, dp(4), 0, dp(2))
            })
            addView(label("Published ${formatDate(release.publishedAt)}", 10f, secondaryText))
            val infoRow = LinearLayout(this@EngineManagerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(8))
                addView(label("ABI  ${runCatching { EngineModuleManager.deviceAbi() }.getOrDefault("Unknown")}", 10f, secondaryText))
                addView(label(
                    when { active -> "ACTIVE"; installed != null -> "INSTALLED"; else -> "NOT INSTALLED" },
                    9f, if (active) green else secondaryText, true
                ).apply {
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addView(infoRow)
            addView(actionButton(
                when { active -> "Currently active"; installed != null -> "Activate engine"; else -> "Download and install" },
                filled = !active
            ) {
                if (installed != null) activate(installed.directory.name) else install(release)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
            if (active) {
                (getChildAt(childCount - 1) as Button).isEnabled = false
            }
        }
        content.addView(item, marginParams(bottom = 9))
    }

    private fun showInstalled() {
        if (isDownloading) return
        content.removeAllViews()
        val engines = EngineModuleManager.installed(this)
        status.text = "Active: ${EngineModuleManager.activeVersion(this) ?: "None"}  •  ${engines.size} installed"
        if (engines.isEmpty()) {
            content.addView(emptyState("No engines installed", "Choose Releases or Nightly to find an engine build."))
            return
        }

        engines.forEach { engine ->
            val tag = engine.directory.name
            val active = EngineModuleManager.activeVersion(this)?.let { safeTag(it) } == tag
            val item = card().apply {
                setPadding(dp(14), dp(13), dp(14), dp(13))
                addView(label(engine.version, 15f, primaryText, true))
                addView(label("${engine.channel.uppercase()}  •  ${engine.abi}", 10f, accent, true)
                    .apply { setPadding(0, dp(4), 0, dp(6)) })
                addView(label(engine.library.name + "  •  " + formatBytes(engine.library.length()), 10f, secondaryText))
                val activateButton = actionButton(if (active) "Currently active" else "Activate engine", !active) {
                    activate(tag)
                }.apply { isEnabled = !active }
                addView(activateButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
                ).apply { topMargin = dp(10) })
            }
            content.addView(item, marginParams(bottom = 9))
        }
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
        progressBar.isIndeterminate = true
        progressPercent.text = "—"
        progressStats.text = "Starting"
        cancelButton.isEnabled = true
        cancelButton.text = "Cancel download"
        cancelButton.setOnClickListener {
            downloadCancellation?.set(true)
            cancelButton.isEnabled = false
            cancelButton.text = "Cancelling…"
        }
        progressBar.progress = 0
        status.text = "Downloading engine module…"

        executor.execute {
            try {
                val engine = EngineModuleManager.install(
                    this,
                    release,
                    cancelled = cancellation,
                    onProgress = { progress ->
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            progressBar.isIndeterminate = progress.totalBytes <= 0L
                            if (progress.totalBytes > 0L) {
                                progressBar.progress = progress.percent * 10
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
                        status.text = "Installed ${engine.version}. Stop the server before activating it."
                        showInstalled()
                    } else {
                        EngineModuleManager.activate(this, release.tagName)
                        updateStatus()
                        Toast.makeText(this, "Engine installed and activated", Toast.LENGTH_LONG).show()
                        if (PumpkinNative.isLoaded) {
                            Toast.makeText(this, "Restart PumpkinLauncher to load the selected engine.", Toast.LENGTH_LONG).show()
                        }
                        loadChannel(selectedChannel)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    isDownloading = false
                    downloadCancellation = null
                    progressBar.isIndeterminate = false
                    cancelButton.isEnabled = true
                    cancelButton.text = "Close"
                    cancelButton.setOnClickListener { progressCard.visibility = View.GONE }
                    val cancelled = error is EngineDownloadCancelledException || cancellation.get()
                    progressTitle.text = if (cancelled) "Download cancelled" else "Installation failed"
                    progressDetail.text = error.message ?: "The engine could not be installed."
                    progressPercent.text = if (cancelled) "Stopped" else "Error"
                    progressStats.text = "No engine was activated"
                    status.text = if (cancelled) "Download cancelled" else "Engine installation failed"
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
            updateStatus()
            showInstalled()
            if (PumpkinNative.isLoaded) {
                status.text = "Engine selected. Restart PumpkinLauncher to load the new version."
            }
        } catch (error: Exception) {
            status.text = "Activation failed: ${error.message}"
        }
    }

    private fun updateStatus() {
        val active = EngineModuleManager.activeVersion(this) ?: "None"
        val abi = runCatching { EngineModuleManager.deviceAbi() }.getOrElse { "unsupported" }
        status.text = "Active: $active  •  Device ABI: $abi"
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, 16)
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(
        title: String,
        filled: Boolean = true,
        onClick: (() -> Unit)? = null
    ) = Button(this).apply {
        text = title
        textSize = 12f
        isAllCaps = false
        setTextColor(if (filled) Color.WHITE else primaryText)
        background = rounded(if (filled) accent else surfaceRaised, 11)
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun emptyState(title: String, description: String) = card().apply {
        setPadding(dp(16), dp(18), dp(16), dp(18))
        addView(label(title, 14f, primaryText, true))
        addView(label(description, 11f, secondaryText).apply { setPadding(0, dp(5), 0, 0) })
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (top != 0) topMargin = dp(top)
            if (bottom != 0) bottomMargin = dp(bottom)
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
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        downloadCancellation?.set(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
