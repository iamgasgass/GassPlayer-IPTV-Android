package com.gassplayer.android.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

object JsonStore {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; isLenient = true }
    fun file(context: Context, name: String): File = File(context.filesDir, name)
    inline fun <reified T> read(context: Context, name: String, default: T): T = runCatching { json.decodeFromString<T>(file(context, name).readText()) }.getOrDefault(default)
    inline fun <reified T> write(context: Context, name: String, value: T) { file(context, name).writeText(json.encodeToString(value)) }
}
