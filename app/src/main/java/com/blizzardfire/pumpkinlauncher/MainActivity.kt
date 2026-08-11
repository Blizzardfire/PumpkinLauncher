package com.blizzardfire.pumpkinlauncher

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.view.ContextThemeWrapper
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private var pumpkinService: PumpkinService? = null
    private var isBound = false
    private var isServerRunning = false
    private var playerCount = 0

    private lateinit var statusText: TextView
    private lateinit var startStopButton: Button
    private lateinit var consoleText: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var commandInput: EditText
    private lateinit var sendCommandButton: Button
    private lateinit var uptimeText: TextView
    private lateinit var menuButton: Button
    private lateinit var playersCountText: TextView

    private val pluginPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importPluginFromUri(uri)
    }

    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            updateUptime()
            syncStatusToService()
            uptimeHandler.postDelayed(this, 1000)
        }
    }

    private fun syncStatusToService() {
        val actuallyRunning = pumpkinService?.isRunning == true
        if (actuallyRunning != isServerRunning) {
            isServerRunning = actuallyRunning
            if (isServerRunning) {
                startStopButton.text = "Stop"
                statusText.text = "Status: Running"
            } else {
                startStopButton.text = "Start"
                statusText.text = "Status: Stopped"
            }
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val line = intent?.getStringExtra("line") ?: return
            appendToConsole(line)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as PumpkinService.LocalBinder
            pumpkinService = binder.getService()
            isBound = true

            isServerRunning = pumpkinService?.isRunning == true
            if (isServerRunning) {
                startStopButton.text = "Stop"
                statusText.text = "Status: Running"
            } else {
                startStopButton.text = "Start"
                statusText.text = "Status: Stopped"
            }

            uptimeHandler.post(uptimeRunnable)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pumpkinService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        startStopButton = findViewById(R.id.startStopButton)
        consoleText = findViewById(R.id.consoleText)
        consoleScroll = findViewById(R.id.consoleScroll)
        commandInput = findViewById(R.id.commandInput)
        sendCommandButton = findViewById(R.id.sendCommandButton)
        uptimeText = findViewById(R.id.uptimeText)
        menuButton = findViewById(R.id.menuButton)
        playersCountText = findViewById(R.id.playersCountText)

        startStopButton.setOnClickListener {
            if (isServerRunning) {
                stopServer()
            } else {
                startServer()
            }
        }

        sendCommandButton.setOnClickListener {
            sendCommand()
        }

        commandInput.setOnEditorActionListener { _, _, _ ->
            sendCommand()
            true
        }

        menuButton.setOnClickListener {
            val popup = PopupMenu(ContextThemeWrapper(this, R.style.PopupMenuDark), menuButton)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_worlds -> startActivity(Intent(this, WorldsActivity::class.java))
                    R.id.menu_save -> pumpkinService?.sendCommand("save-all")
                    R.id.menu_restart -> {
                        pumpkinService?.sendCommand("stop")
                        appendToConsole("Restarting server...")
                        Handler(Looper.getMainLooper()).postDelayed({ startServer() }, 3000)
                    }
                    R.id.menu_backup -> pumpkinService?.backupWorld()
                }
                true
            }
            popup.show()
        }
    }

    private fun showWorldsDialog() {
        if (isServerRunning) {
            appendToConsole("Stop the server before managing worlds.")
            return
        }

        val worlds = WorldManager.listWorlds(this).toTypedArray()
        val activeWorld = WorldManager.getActiveWorldName(this)
        var selectedIndex = worlds.indexOf(activeWorld).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Select World")
            .setSingleChoiceItems(worlds, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("Select") { _, _ ->
                WorldManager.setActiveWorldName(this, worlds[selectedIndex])
                appendToConsole("Active world set to: ${worlds[selectedIndex]}")
            }
            .setNeutralButton("New World") { _, _ -> showNewWorldDialog() }
            .setNegativeButton("Delete") { _, _ ->
                if (worlds.size <= 1) {
                    appendToConsole("Cannot delete the only remaining world.")
                } else {
                    confirmDeleteWorld(worlds[selectedIndex])
                }
            }
            .show()
    }

    private fun showNewWorldDialog() {
        val input = EditText(this)
        input.hint = "World name"
        AlertDialog.Builder(this)
            .setTitle("New World")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (WorldManager.createWorld(this, name)) {
                        appendToConsole("Created new world: $name")
                    } else {
                        appendToConsole("Could not create world (name may already exist).")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteWorld(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete World")
            .setMessage("Permanently delete \"$name\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (WorldManager.deleteWorld(this, name)) {
                    appendToConsole("Deleted world: $name")
                } else {
                    appendToConsole("Failed to delete world: $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPluginsDialog() {
        val plugins = PluginManager.listPlugins(this).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Plugins — ${WorldManager.getActiveWorldName(this)}")
            .setItems(plugins) { _, which -> confirmDeletePlugin(plugins[which]) }
            .setPositiveButton("Import .wasm") { _, _ -> pluginPickerLauncher.launch("*/*") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmDeletePlugin(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove Plugin")
            .setMessage("Remove \"$name\"? Restart the server for this to take effect.")
            .setPositiveButton("Remove") { _, _ ->
                if (PluginManager.deletePlugin(this, name)) {
                    appendToConsole("Removed plugin: $name")
                } else {
                    appendToConsole("Failed to remove plugin: $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                appendToConsole("Import failed: file must be a .wasm plugin")
                return
            }

            val targetFile = File(PluginManager.getPluginsDir(this), fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }

            appendToConsole("Imported plugin: $fileName (restart server to load)")
        } catch (e: Exception) {
            appendToConsole("Import failed: ${e.message}")
        }
    }

    private fun startServer() {
        val serviceIntent = Intent(this, PumpkinService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        isServerRunning = true
        startStopButton.text = "Stop"
        statusText.text = "Status: Running"
        playerCount = 0
        playersCountText.text = "0"
        appendToConsole("Server starting...")
    }

    private fun stopServer() {
        pumpkinService?.stopServer()

        isServerRunning = false
        startStopButton.text = "Start"
        statusText.text = "Status: Stopped"
        playerCount = 0
        playersCountText.text = "0"
        appendToConsole("Server stopping...")
    }

    private fun sendCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isNotEmpty() && isBound) {
            pumpkinService?.sendCommand(command)
            commandInput.text.clear()
        }
    }

    private fun appendToConsole(line: String) {
        val spannable = android.text.SpannableString(line)

        val infoColor = android.graphics.Color.parseColor("#65C362")
        val warnColor = android.graphics.Color.parseColor("#F4D95D")
        val errorColor = android.graphics.Color.parseColor("#E3585C")
        val grayColor = android.graphics.Color.parseColor("#888888")

        when {
            line.contains("ERROR") -> colorWord(spannable, "ERROR", errorColor)
            line.contains("WARN") -> colorWord(spannable, "WARN", warnColor)
            line.contains("INFO") -> colorWord(spannable, "INFO", infoColor)
        }

        val urlRegex = Regex("""https?://\S+""")
        urlRegex.findAll(line).forEach { match ->
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#556CD9")),
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (line.contains("Pumpkin")) {
            colorWord(spannable, "Pumpkin", android.graphics.Color.parseColor("#E57540"))
        }

        val timestampRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
        timestampRegex.find(line)?.let { match ->
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(grayColor),
                match.range.first,
                match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        when {
            line.contains("joined the game") -> {
                playerCount++
                playersCountText.text = playerCount.toString()
            }
            line.contains("left the game") -> {
                playerCount = (playerCount - 1).coerceAtLeast(0)
                playersCountText.text = playerCount.toString()
            }
        }

        consoleText.append(spannable)
        consoleText.append("\n")

        consoleScroll.post {
            consoleScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun colorWord(spannable: android.text.SpannableString, word: String, color: Int) {
        val start = spannable.indexOf(word)
        if (start >= 0) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start,
                start + word.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun updateUptime() {
        val startTime = pumpkinService?.serverStartTime ?: 0
        if (startTime == 0L || pumpkinService?.isRunning != true) {
            uptimeText.text = "00:00:00"
            return
        }
        val elapsedMillis = System.currentTimeMillis() - startTime
        val days = elapsedMillis / 86400000
        val hours = (elapsedMillis / 3600000) % 24
        val minutes = (elapsedMillis / 60000) % 60
        val seconds = (elapsedMillis / 1000) % 60

        uptimeText.text = if (days > 0) {
            String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds)
        } else {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.blizzardfire.pumpkinlauncher.LOG_UPDATE")
        LocalBroadcastManager.getInstance(this).registerReceiver(logReceiver, filter)

        val serviceIntent = Intent(this, PumpkinService::class.java)
        bindService(serviceIntent, connection, 0)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
        uptimeHandler.removeCallbacks(uptimeRunnable)
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}