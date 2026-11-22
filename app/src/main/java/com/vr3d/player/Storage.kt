package com.vr3d.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Storage {
    /** Guarda un video en Películas/VR3D (Android 10+) o en la carpeta de la app (Android 9 o anterior). */
    fun saveVideo(ctx: Context, file: File, displayName: String, mime: String = "video/mp4"): Uri {
        if (Build.VERSION.SDK_INT < 29) {
            val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir
            val dir = File(base, "VR3D")
            dir.mkdirs()
            val dest = File(dir, displayName)
            file.inputStream().use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
            return Uri.fromFile(dest)
        }
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
        values.put(MediaStore.Video.Media.MIME_TYPE, mime)
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VR3D")
        values.put(MediaStore.Video.Media.IS_PENDING, 1)
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear el archivo en Películas")
        val os = cr.openOutputStream(uri) ?: throw IllegalStateException("No se pudo escribir el archivo")
        os.use { out -> file.inputStream().use { it.copyTo(out) } }
        val done = ContentValues()
        done.put(MediaStore.Video.Media.IS_PENDING, 0)
        cr.update(uri, done, null, null)
        return uri
    }

    fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }
}
