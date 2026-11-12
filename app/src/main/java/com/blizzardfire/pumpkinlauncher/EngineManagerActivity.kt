package com.blizzardfire.pumpkinlauncher

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class EngineManagerActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var selectedChannel = "release"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Engine Manager"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Pumpkin-MC is installed independently from the launcher. Choose a channel, then install or activate an engine version."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        })
        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        val channels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        channels.addView(Button(this).apply {
            text = "Releases"
            setOnClickListener { loadChannel("release") }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        channels.addView(Button(this).apply {
            text = "Nightly"
            setOnClickListener { loadChannel("nightly") }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        channels.addView(Button(this).apply {
            text = "Installed"
            setOnClickListener { showInstalled() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(channels)

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        updateStatus()
        loadChannel("release")
    }

    private fun loadChannel(channel: String) {
        selectedChannel = channel
        content.removeAllViews()
        status.text = "Loading $channel builds…"
        executor.execute {
            try {
                val releases = EngineModuleManager.fetchReleases(channel)
                runOnUiThread {
                    content.removeAllViews()
                    status.text = if (releases.isEmpty()) {
                        "No $channel engine releases have been published yet."
                    } else {
                        "${releases.size} $channel build(s) available"
                    }
                    releases.forEach { release -> addReleaseRow(release) }
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "Could not load releases: ${error.message}" }
            }
        }
    }

    private fun addReleaseRow(release: EngineRelease) {
        val installed = EngineModuleManager.installed(this)
            .firstOrNull { it.directory.name == safeTag(release.tagName) }
        val activeVersion = EngineModuleManager.activeVersion(this)
        val active = activeVersion == release.tagName || activeVersion == safeTag(release.tagName)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFEFEFEF.toInt())
        }
        card.addView(TextView(this).apply {
            text = release.name.ifBlank { release.tagName }
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = "${release.tagName}\nPublished: ${release.publishedAt}"
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
        })
        card.addView(Button(this).apply {
            text = when {
                active -> "Active"
                installed != null -> "Activate"
                else -> "Download and install"
            }
            isEnabled = !active
            setOnClickListener {
                if (installed != null) activate(installed.directory.name) else install(release)
            }
        })
        content.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(6), 0, dp(6)) })
    }

    private fun showInstalled() {
        content.removeAllViews()
        val engines = EngineModuleManager.installed(this)
        status.text = if (engines.isEmpty()) "No engine versions installed." else "Installed versions"
        engines.forEach { engine ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(0xFFEFEFEF.toInt())
            }
            val tag = engine.directory.name
            val active = EngineModuleManager.activeVersion(this)?.let { safeTag(it) } == tag
            card.addView(TextView(this).apply {
                text = "${engine.version} (${engine.channel})"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = "ABI: ${engine.abi}\n${engine.library.absolutePath}"
                textSize = 11f
                setPadding(0, dp(4), 0, dp(6))
            })
            card.addView(Button(this).apply {
                text = if (active) "Active" else "Activate"
                isEnabled = !active
                setOnClickListener { activate(tag) }
            })
            content.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) })
        }
    }

    private fun install(release: EngineRelease) {
        status.text = "Downloading ${release.tagName}…"
        executor.execute {
            try {
                val engine = EngineModuleManager.install(this, release)
                runOnUiThread {
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
                runOnUiThread { status.text = "Installation failed: ${error.message}" }
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
        status.text = "Active engine: $active\nDevice ABI: $abi"
    }

    private fun safeTag(tag: String): String = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
