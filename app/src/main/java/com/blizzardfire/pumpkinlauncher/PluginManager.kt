package com.blizzardfire.pumpkinlauncher

import android.content.Context
import java.io.File

object PluginManager {

    fun getPluginsDir(worldDir: File): File {
        val dir = File(worldDir, "plugins")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPluginsDir(context: Context): File = getPluginsDir(WorldManager.getActiveWorldDir(context))

    fun listPlugins(worldDir: File): List<String> {
        return getPluginsDir(worldDir)
            .listFiles { f -> f.isFile && f.name.endsWith(".wasm") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun listPlugins(context: Context): List<String> = listPlugins(WorldManager.getActiveWorldDir(context))

    fun deletePlugin(worldDir: File, name: String): Boolean {
        return File(getPluginsDir(worldDir), name).delete()
    }

    fun deletePlugin(context: Context, name: String): Boolean = deletePlugin(WorldManager.getActiveWorldDir(context), name)
}