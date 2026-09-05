package com.tdee.app.addfood

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

object LabelPhoto {
    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 80

    /**
     * Creates an empty file under cacheDir/label/ and returns a FileProvider content URI for it.
     * ACTION_IMAGE_CAPTURE writes the full-size photo there, so no CAMERA permission is needed.
     */
    fun newCaptureUri(context: Context): Uri {
        val labelDir = File(context.cacheDir, "label")
        labelDir.mkdirs()

        // Delete any existing files to avoid accumulating full-size camera images.
        labelDir.listFiles()?.forEach { it.delete() }

        val file = File(labelDir, "label.jpg")
        file.createNewFile()

        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Reads the photo at [uri], downscales it to at most [MAX_EDGE] px on its long edge, and
     * re-encodes it as JPEG. Returns null when the image cannot be read or decoded.
     */
    fun readDownscaledJpeg(context: Context, uri: Uri): ByteArray? {
        return try {
            // First pass: read dimensions only. decodeStream returns null by contract when
            // inJustDecodeBounds is set, so the result says nothing about success. Read the answer
            // out of the options instead, and check the stream separately.
            val dimensions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            val bounds = context.contentResolver.openInputStream(uri) ?: return null
            bounds.use { input -> BitmapFactory.decodeStream(input, null, dimensions) }

            val width = dimensions.outWidth
            val height = dimensions.outHeight
            if (width <= 0 || height <= 0) return null

            // Compute inSampleSize as the largest power of two such that both dimensions
            // stay at or above MAX_EDGE.
            var sampleSize = 1
            while ((height / (sampleSize * 2)) >= MAX_EDGE && (width / (sampleSize * 2)) >= MAX_EDGE) {
                sampleSize *= 2
            }

            // Second pass: decode with sampling.
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            // If the long edge still exceeds MAX_EDGE, scale exactly.
            val scaledBitmap = if (width > MAX_EDGE || height > MAX_EDGE) {
                val scale = (MAX_EDGE.toFloat() / maxOf(width, height)).coerceAtMost(1.0f)
                val newWidth = (width * scale).toInt().coerceAtLeast(1)
                val newHeight = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }

            // Apply EXIF orientation.
            val orientedBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(scaledBitmap, 90)
                    ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(scaledBitmap, 180)
                    ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(scaledBitmap, 270)
                    else -> scaledBitmap
                }
            } ?: scaledBitmap

            // Compress to JPEG.
            ByteArrayOutputStream().use { output ->
                orientedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
