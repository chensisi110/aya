package io.liriliri.aya

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import org.json.JSONArray
import java.io.ByteArrayOutputStream

object Util {
    fun jsonArrayToStringArray(jsonArray: JSONArray): Array<String> {
        val list = ArrayList<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list.toTypedArray()
    }

    fun drawableToBitmap(drawable: Drawable, fallbackSize: Int = 96): Bitmap {
        var width = drawable.intrinsicWidth
        var height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) {
            width = fallbackSize
            height = fallbackSize
        }
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.setHasAlpha(true)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    fun scaleBitmap(bitmap: Bitmap, size: Int): Bitmap {
        if (bitmap.width == size && bitmap.height == size) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, size, size, true)
    }

    fun bitMapToPng(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, quality, stream)
        return stream.toByteArray()
    }
}