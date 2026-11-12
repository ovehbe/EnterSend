package com.ovehbe.entersend.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ovehbe.entersend.core.Pixels
import java.io.File

/** Send-button crops live as PNGs in app-private storage; the database only stores the path. */
object CropFiles {
    fun dir(filesDir: File): File = File(filesDir, "crops").apply { mkdirs() }

    fun save(filesDir: File, pkg: String, pixels: Pixels): File {
        val file = File(dir(filesDir), "$pkg-${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun load(path: String): Pixels? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Pixels(bitmap.width, bitmap.height, argb)
    }
}
