package com.blizzardfire.pumpkinlauncher

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class PluginsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PluginsAdapter
    private lateinit var worldName: String
    private lateinit var worldDir: File
    private lateinit var activeStatusText: TextView
    private lateinit var setActiveButton: Button

    private val pluginPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importPluginFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)

        worldName = intent.getStringExtra("world_name") ?: WorldManager.getActiveWorldName(this)
        worldDir = WorldManager.getWorldDir(this, worldName)

        findViewById<TextView>(R.id.pluginsTitleText).text = "Plugins — $worldName"

        activeStatusText = findViewById(R.id.activeStatusText)
        setActiveButton = findViewById(R.id.setActiveButton)

        setActiveButton.setOnClickListener {
            WorldManager.setActiveWorldName(this, worldName)
            updateActiveRow()
        }

        recyclerView = findViewById(R.id.pluginsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PluginsAdapter()
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        findViewById<Button>(R.id.importPluginButton).setOnClickListener {
            pluginPickerLauncher.launch("*/*")
        }

        findViewById<Button>(R.id.serverSettingsButton).setOnClickListener {
            val intent = Intent(this, ConfigActivity::class.java)
            intent.putExtra("world_name", worldName)
            startActivity(intent)
        }

        updateActiveRow()
    }

    override fun onResume() {
        super.onResume()
        adapter.refresh()
    }

    private fun updateActiveRow() {
        val isActive = worldName == WorldManager.getActiveWorldName(this)
        if (isActive) {
            activeStatusText.text = "This world is currently active"
            setActiveButton.visibility = View.GONE
        } else {
            activeStatusText.text = "Not the active world"
            setActiveButton.visibility = View.VISIBLE
        }
    }

    private fun importPluginFromUri(uri: Uri) {
        try {
            var fileName = "plugin_${System.currentTimeMillis()}.wasm"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            if (!fileName.endsWith(".wasm")) {
                AlertDialog.Builder(this)
                    .setTitle("Import Failed")
                    .setMessage("File must be a .wasm plugin")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            val targetFile = File(PluginManager.getPluginsDir(worldDir), fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }

            adapter.refresh()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Import Failed")
                .setMessage(e.message ?: "Unknown error")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    inner class PluginsAdapter : RecyclerView.Adapter<PluginsAdapter.ViewHolder>() {
        private var plugins: List<String> = PluginManager.listPlugins(worldDir)

        fun refresh() {
            plugins = PluginManager.listPlugins(worldDir)
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.pluginNameText)
            val removeButton: Button = view.findViewById(R.id.removePluginButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plugin, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val name = plugins[position]
            holder.nameText.text = name

            holder.removeButton.setOnClickListener {
                AlertDialog.Builder(this@PluginsActivity)
                    .setTitle("Remove Plugin")
                    .setMessage("Remove \"$name\"? Restart the server for this to take effect.")
                    .setPositiveButton("Remove") { _, _ ->
                        PluginManager.deletePlugin(worldDir, name)
                        refresh()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        override fun getItemCount() = plugins.size
    }
}