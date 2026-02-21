package com.example.remotecontrol.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

object ScreenshotUtil {
    fun bitmapToPng(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
