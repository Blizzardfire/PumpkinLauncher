package com.blizzardfire.pumpkinlauncher

import android.content.Context
import java.io.File

object WorldManager {
    private const val PREFS_NAME = "pumpkin_prefs"
    private const val KEY_ACTIVE_WORLD = "active_world"
    private const val DEFAULT_WORLD = "World 1"
    const val MAX_WORLDS = 5

    fun getWorldDir(context: Context, name: String): File {
        val dir = File(getWorldsDir(context), name)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getConfigFile(worldDir: File): File {
        val configDir = File(worldDir, "config")
        if (!configDir.exists()) configDir.mkdirs()
        return File(configDir, "configuration.toml")
    }

    fun getWorldsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val worldsDir = File(base, "worlds")
        if (!worldsDir.exists()) worldsDir.mkdirs()
        return worldsDir
    }

    fun listWorlds(context: Context): List<String> {
        val worldsDir = getWorldsDir(context)
        val existing = worldsDir.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        if (existing.isEmpty()) {
            File(worldsDir, DEFAULT_WORLD).mkdirs()
            return listOf(DEFAULT_WORLD)
        }
        return existing
    }

    fun getActiveWorldName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ACTIVE_WORLD, null)
        val worlds = listWorlds(context)
        return if (saved != null && worlds.contains(saved)) saved else worlds.first()
    }

    fun setActiveWorldName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_WORLD, name).apply()
    }

    fun getActiveWorldDir(context: Context): File {
        val dir = File(getWorldsDir(context), getActiveWorldName(context))
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun createWorld(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (listWorlds(context).size >= MAX_WORLDS) return false
        val dir = File(getWorldsDir(context), trimmed)
        if (dir.exists()) return false
        return dir.mkdirs()
    }

    fun deleteWorld(context: Context, name: String): Boolean {
        val dir = File(getWorldsDir(context), name)
        val deleted = dir.deleteRecursively()
        if (deleted && getActiveWorldName(context) == name) {
            setActiveWorldName(context, listWorlds(context).first())
        }
        return deleted
    }
}