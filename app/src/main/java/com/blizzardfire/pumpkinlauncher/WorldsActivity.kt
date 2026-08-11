package com.blizzardfire.pumpkinlauncher

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class WorldsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WorldsAdapter

    private val worldPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importWorldFromTree(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_worlds)

        recyclerView = findViewById(R.id.worldsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = WorldsAdapter()
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        findViewById<Button>(R.id.newWorldButton).setOnClickListener {
            val nameInput = EditText(this)
            nameInput.hint = "World name"

            val seedInput = EditText(this)
            seedInput.hint = "Seed (optional, numbers only)"

            val container = android.widget.LinearLayout(this)
            container.orientation = android.widget.LinearLayout.VERTICAL
            container.addView(nameInput)
            container.addView(seedInput)

            AlertDialog.Builder(this)
                .setTitle("New World")
                .setView(container)
                .setPositiveButton("Create") { _, _ ->
                    val name = nameInput.text.toString().trim()
                    val seedText = seedInput.text.toString().trim()

                    if (name.isNotEmpty()) {
                        if (WorldManager.createWorld(this, name)) {
                            if (seedText.isNotEmpty()) {
                                val seedValue = seedText.toLongOrNull()
                                if (seedValue != null) {
                                    val worldDir = WorldManager.getWorldDir(this, name)
                                    val configFile = WorldManager.getConfigFile(worldDir)
                                    configFile.writeText(
                                        "java_edition = true\n" +
                                                "java_edition_address = \"0.0.0.0:25565\"\n" +
                                                "bedrock_edition = true\n" +
                                                "bedrock_edition_address = \"0.0.0.0:19132\"\n" +
                                                "seed = $seedValue\n" +
                                                "max_players = 1000\n" +
                                                "view_distance = 16\n" +
                                                "simulation_distance = 10\n" +
                                                "default_difficulty = \"Normal\"\n" +
                                                "op_permission_level = 4\n" +
                                                "allow_nether = true\n" +
                                                "allow_end = true\n" +
                                                "hardcore = false\n" +
                                                "online_mode = true\n" +
                                                "encryption = true\n" +
                                                "motd = \"A blazingly fast Pumpkin server!\"\n" +
                                                "tps = 20.0\n" +
                                                "default_gamemode = \"Survival\"\n" +
                                                "force_gamemode = false\n" +
                                                "scrub_ips = true\n" +
                                                "use_favicon = true\n" +
                                                "favicon_path = null\n" +
                                                "default_level_name = \"world\"\n" +
                                                "allow_chat_reports = false\n" +
                                                "white_list = false\n" +
                                                "enforce_whitelist = false\n"
                                    )
                                } else {
                                    AlertDialog.Builder(this)
                                        .setTitle("Seed Ignored")
                                        .setMessage("Seed must be a whole number. World was created with a random seed instead.")
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            }
                            adapter.refresh()
                        } else if (WorldManager.listWorlds(this).size >= WorldManager.MAX_WORLDS) {
                            showLimitReachedDialog()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<Button>(R.id.importWorldButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Coming Soon")
                .setMessage("Importing existing worlds is temporarily disabled due to an upstream bug in Pumpkin's handling of imported world data. This will be enabled once fixed.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.refresh()
    }

    private fun showLimitReachedDialog() {
        AlertDialog.Builder(this)
            .setTitle("World Limit Reached")
            .setMessage("You can have up to ${WorldManager.MAX_WORLDS} worlds. Delete one first.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun importWorldFromTree(treeUri: Uri) {
        val sourceDoc = DocumentFile.fromTreeUri(this, treeUri)
        if (sourceDoc == null || !sourceDoc.isDirectory) {
            AlertDialog.Builder(this)
                .setTitle("Import Failed")
                .setMessage("That doesn't look like a valid folder.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val suggestedName = sourceDoc.name ?: "Imported World ${System.currentTimeMillis()}"
        val input = EditText(this)
        input.setText(suggestedName)

        AlertDialog.Builder(this)
            .setTitle("Import World")
            .setMessage("Name this imported world:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton

                val targetDir = File(WorldManager.getWorldsDir(this), name)
                if (targetDir.exists()) {
                    AlertDialog.Builder(this)
                        .setTitle("Import Failed")
                        .setMessage("A world named \"$name\" already exists.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }

                val progressDialog = AlertDialog.Builder(this)
                    .setTitle("Importing...")
                    .setMessage("Copying world files, please wait.")
                    .setCancelable(false)
                    .show()

                Thread {
                    val tempDir = File(WorldManager.getWorldsDir(this), ".importing_${System.currentTimeMillis()}")
                    try {
                        // Pumpkin expects actual save data inside a "world" subfolder
                        // of the working directory, not directly at the root - so we
                        // nest the copied files one level deeper to match that.
                        val worldSubDir = File(tempDir, "world")
                        worldSubDir.mkdirs()
                        copyDocumentTreeRecursively(sourceDoc, worldSubDir)

                        // Only becomes visible/selectable once the copy is fully done
                        if (!tempDir.renameTo(targetDir)) {
                            throw Exception("Failed to finalize imported world folder.")
                        }

                        runOnUiThread {
                            progressDialog.dismiss()
                            adapter.refresh()
                            AlertDialog.Builder(this)
                                .setTitle("Import Complete")
                                .setMessage("Imported world \"$name\".")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    } catch (e: Exception) {
                        tempDir.deleteRecursively()
                        runOnUiThread {
                            progressDialog.dismiss()
                            AlertDialog.Builder(this)
                                .setTitle("Import Failed")
                                .setMessage(e.message ?: "Unknown error while copying the folder.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Recursively copies everything inside a picked DocumentFile folder into a real File directory. */
    private fun copyDocumentTreeRecursively(sourceDir: DocumentFile, targetDir: File) {
        for (child in sourceDir.listFiles()) {
            val childName = child.name ?: continue
            if (child.isDirectory) {
                val newTargetDir = File(targetDir, childName)
                newTargetDir.mkdirs()
                copyDocumentTreeRecursively(child, newTargetDir)
            } else {
                val targetFile = File(targetDir, childName)
                contentResolver.openInputStream(child.uri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    inner class WorldsAdapter : RecyclerView.Adapter<WorldsAdapter.ViewHolder>() {
        private var worlds: List<String> = WorldManager.listWorlds(this@WorldsActivity)

        fun refresh() {
            worlds = WorldManager.listWorlds(this@WorldsActivity)
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.worldNameText)
            val activeLabel: TextView = view.findViewById(R.id.activeLabel)
            val deleteButton: Button = view.findViewById(R.id.deleteWorldButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_world, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val name = worlds[position]
            holder.nameText.text = name

            val isActive = name == WorldManager.getActiveWorldName(this@WorldsActivity)
            holder.activeLabel.visibility = if (isActive) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener {
                val intent = Intent(this@WorldsActivity, PluginsActivity::class.java)
                intent.putExtra("world_name", name)
                startActivity(intent)
            }

            holder.deleteButton.setOnClickListener {
                if (worlds.size <= 1) {
                    AlertDialog.Builder(this@WorldsActivity)
                        .setTitle("Cannot Delete")
                        .setMessage("You need at least one world.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@WorldsActivity)
                        .setTitle("Delete World")
                        .setMessage("Permanently delete \"$name\"? This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            WorldManager.deleteWorld(this@WorldsActivity, name)
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        override fun getItemCount() = worlds.size
    }
}