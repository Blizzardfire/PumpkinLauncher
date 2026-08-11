package com.blizzardfire.pumpkinlauncher

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ConfigActivity : AppCompatActivity() {

    private lateinit var worldName: String
    private lateinit var configFile: File
    private lateinit var editText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        worldName = intent.getStringExtra("world_name") ?: WorldManager.getActiveWorldName(this)
        val worldDir = WorldManager.getWorldDir(this, worldName)
        configFile = WorldManager.getConfigFile(worldDir)

        findViewById<TextView>(R.id.configTitleText).text = "Settings — $worldName"
        editText = findViewById(R.id.configEditText)

        if (configFile.exists()) {
            editText.setText(configFile.readText())
        } else {
            editText.setText(
                "java_edition = true\n" +
                        "java_edition_address = \"0.0.0.0:25565\"\n" +
                        "bedrock_edition = true\n" +
                        "bedrock_edition_address = \"0.0.0.0:19132\"\n" +
                        "seed = 0\n" +
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
        }

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        findViewById<Button>(R.id.saveConfigButton).setOnClickListener {
            try {
                configFile.parentFile?.mkdirs()
                configFile.writeText(editText.text.toString())
                AlertDialog.Builder(this)
                    .setTitle("Saved")
                    .setMessage("Settings saved. Restart the server for changes to take effect.")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                AlertDialog.Builder(this)
                    .setTitle("Save Failed")
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}