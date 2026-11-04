package com.mipago.ex2center.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import com.mipago.ex2center.R
import java.io.File
import java.io.FileOutputStream

/**
 * The car's display name and photo (Cài đặt → Hồ sơ xe).
 * The photo is copied into app storage, downscaled to at most [MAX_PHOTO_PX] on its longest side.
 */
object CarProfile {

    const val DEFAULT_NAME = "Geely EX2"
    private const val PREFS = "ex2_car_profile"
    private const val KEY_NAME = "car_name"
    private const val PHOTO_FILE = "car_photo.jpg"
    private const val MAX_PHOTO_PX = 512
    const val MAX_NAME_LENGTH = 32

    fun getName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME

    /** Empty / blank resets to the default name. */
    fun setName(context: Context, name: String) {
        val clean = name.trim().take(MAX_NAME_LENGTH)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (clean.isEmpty()) remove(KEY_NAME) else putString(KEY_NAME, clean)
        }.apply()
    }

    private fun photoFile(context: Context) = File(context.filesDir, PHOTO_FILE)

    fun hasCustomPhoto(context: Context): Boolean = photoFile(context).isFile

    /** Blocking (decodes + writes) — call off the main thread. */
    fun setPhoto(context: Context, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            false
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_PX) sample *= 2
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            if (decoded == null) {
                false
            } else {
                val scale = MAX_PHOTO_PX.toFloat() / maxOf(decoded.width, decoded.height)
                val bmp = if (scale < 1f) {
                    Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                } else decoded
                val tmp = File(context.filesDir, "$PHOTO_FILE.tmp")
                FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                tmp.renameTo(photoFile(context))
            }
        }
    } catch (_: Throwable) {
        false
    }

    fun clearPhoto(context: Context) {
        photoFile(context).delete()
    }

    /** Shows the custom photo (or the bundled EX2 image) cropped into a circle. */
    fun bindPhoto(context: Context, view: ImageView) {
        val file = photoFile(context)
        val bmp = if (file.isFile) try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Throwable) { null } else null
        if (bmp != null) view.setImageBitmap(bmp) else view.setImageResource(R.drawable.img_my_ex2)
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setOval(0, 0, v.width, v.height)
        }
        view.clipToOutline = true
    }
}
