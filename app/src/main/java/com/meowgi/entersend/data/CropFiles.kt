package com.meowgi.entersend.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.meowgi.entersend.core.Pixels
import java.io.File

interface Crops {
    fun load(path: String): Pixels?
    /** Writes [pixels] and returns the absolute path. */
    fun save(pkg: String, pixels: Pixels): String
    fun delete(path: String)
}

/** Send-button crops live as PNGs in app-private storage; the database only stores the path. */
class CropFiles(private val filesDir: File) : Crops {
    override fun load(path: String): Pixels? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Pixels(bitmap.width, bitmap.height, argb)
    }

    override fun save(pkg: String, pixels: Pixels): String {
        val file = File(dir(filesDir), "$pkg-${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    companion object {
        fun dir(filesDir: File): File = File(filesDir, "crops").apply { mkdirs() }
    }
}
