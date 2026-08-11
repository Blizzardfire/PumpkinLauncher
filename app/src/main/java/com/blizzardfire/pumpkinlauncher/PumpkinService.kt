package com.blizzardfire.pumpkinlauncher

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

class PumpkinService : Service() {

    private var serverProcess: Process? = null
    private var serverWriter: BufferedWriter? = null
    private val channelId = "pumpkin_server_channel"
    private val notificationId = 1
    private val binder = LocalBinder()
    var serverStartTime: Long = 0
        private set
    var isRunning: Boolean = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): PumpkinService = this@PumpkinService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Starting server...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }

        Thread { startPumpkinServer() }.start()

        return START_STICKY
    }

    private fun startPumpkinServer() {
        try {
            val workDir = WorldManager.getActiveWorldDir(this)
            val binaryPath = File(applicationInfo.nativeLibraryDir, "libpumpkin.so").absolutePath

            updateNotification("Server running")
            broadcastLog("Starting Pumpkin server...")

            val processBuilder = ProcessBuilder(binaryPath)
            processBuilder.directory(workDir)
            processBuilder.redirectErrorStream(true)

            serverProcess = processBuilder.start()
            serverWriter = BufferedWriter(OutputStreamWriter(serverProcess!!.outputStream))
            serverStartTime = System.currentTimeMillis()
            isRunning = true

            serverProcess?.inputStream?.bufferedReader()?.forEachLine { line: String ->
                Log.d("PumpkinServer", line)
                broadcastLog(line)
            }

            broadcastLog("Server process ended.")
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

        } catch (e: Exception) {
            Log.e("PumpkinServer", "Failed to start server", e)
            broadcastLog("ERROR: ${e.message}")
            isRunning = false
            updateNotification("Server failed: ${e.message}")
        }
    }

    fun sendCommand(command: String) {
        try {
            serverWriter?.write(command)
            serverWriter?.newLine()
            serverWriter?.flush()
            broadcastLog("> $command")
        } catch (e: Exception) {
            broadcastLog("ERROR sending command: ${e.message}")
        }
    }

    fun stopServer() {
        try {
            sendCommand("stop")
        } catch (e: Exception) {
            Log.e("PumpkinServer", "Failed to send stop command", e)
        }
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun backupWorld() {
        Thread {
            try {
                val workDir = WorldManager.getActiveWorldDir(this)
                val worldDir = File(workDir, "world")
                if (!worldDir.exists()) {
                    broadcastLog("Backup failed: world folder not found")
                    return@Thread
                }
                val backupsDir = File(workDir, "backups")
                if (!backupsDir.exists()) backupsDir.mkdirs()

                val timestamp = System.currentTimeMillis()
                val backupTarget = File(backupsDir, "world_backup_$timestamp")
                worldDir.copyRecursively(backupTarget, overwrite = true)

                broadcastLog("Backup complete: ${backupTarget.name}")

                val existingBackups = backupsDir.listFiles { file -> file.isDirectory }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                if (existingBackups.size > 2) {
                    existingBackups.drop(2).forEach { oldBackup ->
                        oldBackup.deleteRecursively()
                        broadcastLog("Deleted old backup: ${oldBackup.name}")
                    }
                }

            } catch (e: Exception) {
                broadcastLog("Backup failed: ${e.message}")
            }
        }.start()
    }

    private fun broadcastLog(line: String) {
        val cleanLine = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
        val intent = Intent("com.blizzardfire.pumpkinlauncher.LOG_UPDATE")
        intent.putExtra("line", cleanLine)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Pumpkin Server")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            channelId,
            "Pumpkin Server",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serverProcess?.destroy()
        super.onDestroy()
    }
}