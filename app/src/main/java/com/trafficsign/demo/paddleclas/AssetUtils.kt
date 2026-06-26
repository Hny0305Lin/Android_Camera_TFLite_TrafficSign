package com.trafficsign.demo.paddleclas

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object AssetUtils {
    fun assetExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun readLabels(context: Context, assetPath: String): List<String> {
        if (!assetExists(context, assetPath)) {
            return emptyList()
        }
        return context.assets.open(assetPath).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }

    fun copyAssetToCache(context: Context, assetPath: String): File {
        val targetFile = File(context.cacheDir, assetPath.replace('/', '_'))
        if (targetFile.exists() && targetFile.length() > 0L) {
            return targetFile
        }

        targetFile.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return targetFile
    }
}
