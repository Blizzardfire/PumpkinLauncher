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
import java.io.File
import java.io.RandomAccessFile

class PumpkinService : Service() {
    companion object {
        @Volatile var isServerActive: Boolean = false
    }

    private val channelId = "pumpkin_server_channel"
    private val notificationId = 1
    private val binder = LocalBinder()
    private var engineThread: Thread? = null
    @Volatile private var stopRequested: Boolean = false

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
        if (engineThread?.isAlive != true) {
            stopRequested = false
            engineThread = Thread({ startPumpkinServer() }, "PumpkinEngineMonitor").also { it.start() }
        }
        return START_STICKY
    }

    private fun startPumpkinServer() {
        isServerActive = true
        try {
            val engineLibrary = EngineModuleManager.activeLibrary(this)
                ?: throw IllegalStateException("No Pumpkin engine is installed. Open Engine Manager first.")
            PumpkinNative.load(engineLibrary)

            val workDir = WorldManager.getActiveWorldDir(this)
            val logFile = File(filesDir, "engine-logs/latest.log")
            val initialOffset = if (logFile.isFile) logFile.length() else 0L

            broadcastLog("Starting Pumpkin engine ${EngineModuleManager.activeVersion(this) ?: ""}...")
            val startResult = PumpkinNative.nativeStart(workDir.absolutePath, logFile.absolutePath)
            if (startResult != 0) {
                throw IllegalStateException("Native engine refused to start (code $startResult).")
            }

            if (stopRequested) PumpkinNative.nativeStop()
            serverStartTime = System.currentTimeMillis()
            isRunning = true
            updateNotification("Pumpkin server running")
            broadcastLog("Pumpkin engine started.")

            var offset = initialOffset
            while (PumpkinNative.nativeIsRunning()) {
                offset = readNewLogLines(logFile, offset)
                Thread.sleep(250)
            }
            readNewLogLines(logFile, offset)
            broadcastLog("Pumpkin server stopped.")
        } catch (error: Throwable) {
            Log.e("PumpkinServer", "Failed to start or monitor Pumpkin engine", error)
            broadcastLog("ERROR: ${error.message ?: error.javaClass.simpleName}")
            updateNotification("Server failed")
        } finally {
            isRunning = false
            isServerActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun readNewLogLines(file: File, currentOffset: Long): Long {
        if (!file.isFile) return currentOffset
        return try {
            RandomAccessFile(file, "r").use { reader ->
                var offset = currentOffset
                if (reader.length() < offset) offset = 0L
                reader.seek(offset)
                while (true) {
                    val line = reader.readLine() ?: break
                    val decoded = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    broadcastLog(decoded)
                }
                reader.filePointer
            }
        } catch (error: Exception) {
            Log.w("PumpkinServer", "Could not read engine log", error)
            currentOffset
        }
    }

    fun sendCommand(command: String) {
        if (!isRunning) {
            broadcastLog("ERROR: Engine is not running.")
            return
        }
        if (PumpkinNative.nativeSendCommand(command)) {
            broadcastLog("> $command")
        } else {
            broadcastLog("ERROR: Could not send command to Pumpkin.")
        }
    }

    fun stopServer() {
        stopRequested = true
        if (engineThread?.isAlive != true) {
            stopSelf()
            return
        }
        broadcastLog("Stopping Pumpkin server...")
        updateNotification("Stopping Pumpkin server...")
        if (PumpkinNative.isLoaded) PumpkinNative.nativeStop()
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
            } catch (error: Exception) {
                broadcastLog("Backup failed: ${error.message}")
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
            .setOngoing(isRunning || text.startsWith("Starting") || text.startsWith("Stopping"))
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
        if (engineThread?.isAlive == true && PumpkinNative.isLoaded) {
            try {
                PumpkinNative.nativeStop()
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }
}
