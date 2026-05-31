package com.fivelidz.compositecamera.common

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

sealed class SaveResult {
    data class Ok(val uri: Uri, val displayPath: String) : SaveResult()
    data class Err(val message: String) : SaveResult()
}

/**
 * Saves the bitmap to /Pictures/<subfolder>/<filename> via MediaStore on Q+, or via direct
 * file write on <Q. Returns the resulting Uri + a human-readable path string.
 */
fun saveBitmapToGallery(
    ctx: Context,
    bitmap: Bitmap,
    subfolder: String,
    filename: String,
    quality: Int = 95,
): SaveResult {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$subfolder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return SaveResult.Err("MediaStore.insert returned null")
            resolver.openOutputStream(uri).use { out ->
                if (out == null) return SaveResult.Err("openOutputStream returned null")
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            cv.clear()
            cv.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            SaveResult.Ok(uri, "Pictures/$subfolder/$filename")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), subfolder)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            SaveResult.Ok(Uri.fromFile(file), file.absolutePath)
        }
    } catch (t: Throwable) {
        SaveResult.Err(t.message ?: t.javaClass.simpleName)
    }
}

fun saveVideoFile(
    ctx: Context,
    file: File,
    subfolder: String,
): SaveResult {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$subfolder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                ?: return SaveResult.Err("MediaStore.insert returned null")
            resolver.openOutputStream(uri).use { out ->
                if (out == null) return SaveResult.Err("openOutputStream returned null")
                file.inputStream().copyTo(out)
            }
            cv.clear(); cv.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
            SaveResult.Ok(uri, "Movies/$subfolder/${file.name}")
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), subfolder)
            if (!dir.exists()) dir.mkdirs()
            val dest = File(dir, file.name)
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            SaveResult.Ok(Uri.fromFile(dest), dest.absolutePath)
        }
    } catch (t: Throwable) {
        SaveResult.Err(t.message ?: t.javaClass.simpleName)
    }
}
