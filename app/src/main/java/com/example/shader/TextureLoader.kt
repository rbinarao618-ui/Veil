package com.example.shader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import com.example.model.BuiltInTexture
import com.example.model.TextureFilterMode
import com.example.model.TextureWrapMode
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

object TextureLoader {

    fun importPickerUriToInternalStorage(context: Context, sourceUri: Uri): String? {
        return runCatching {
            val texturesDir = File(context.filesDir, "textures").apply { mkdirs() }
            // Decode bounds first to cap texture dimension at 1024px for GPU/VRAM efficiency
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            val maxDim = 1024
            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxDim || boundsOpts.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            val outFile = File(texturesDir, "tex_${System.currentTimeMillis()}.png")
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bitmap.recycle()
            outFile.absolutePath
        }.getOrNull()
    }

    fun loadBitmapForVariable(
        context: Context,
        textureUri: String?,
        builtIn: BuiltInTexture,
    ): Bitmap {
        if (!textureUri.isNullOrBlank()) {
            val decoded = runCatching {
                val file = File(textureUri)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    val uri = Uri.parse(textureUri)
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
            }.getOrNull()
            if (decoded != null) return decoded
        }
        return generateBuiltInBitmap(builtIn)
    }

    fun generateBuiltInBitmap(type: BuiltInTexture, size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        when (type) {
            BuiltInTexture.NONE, BuiltInTexture.CHECKERBOARD -> {
                val canvas = Canvas(bmp)
                val paint = Paint()
                val cell = size / 8
                for (y in 0 until 8) {
                    for (x in 0 until 8) {
                        paint.color = if ((x + y) % 2 == 0) Color.rgb(220, 230, 242) else Color.rgb(32, 42, 58)
                        canvas.drawRect(
                            (x * cell).toFloat(),
                            (y * cell).toFloat(),
                            ((x + 1) * cell).toFloat(),
                            ((y + 1) * cell).toFloat(),
                            paint
                        )
                    }
                }
            }
            BuiltInTexture.SCREEN_CAPTURE -> {
                // High-contrast diagnostic standby pattern rendered before MediaProjection frames arrive
                val canvas = Canvas(bmp)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                bgPaint.shader = LinearGradient(
                    0f, 0f, size.toFloat(), size.toFloat(),
                    intArrayOf(
                        Color.rgb(15, 23, 42),
                        Color.rgb(20, 56, 82),
                        Color.rgb(55, 29, 86),
                    ),
                    null,
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

                val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(95, 34, 211, 238)
                    strokeWidth = 2f
                }
                val step = size / 8f
                for (i in 1 until 8) {
                    val pos = i * step
                    canvas.drawLine(pos, 0f, pos, size.toFloat(), gridPaint)
                    canvas.drawLine(0f, pos, size.toFloat(), pos, gridPaint)
                }
            }
            BuiltInTexture.VALUE_NOISE -> {
                val pixels = IntArray(size * size)
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val u = x.toFloat() / size
                        val v = y.toFloat() / size
                        val n = 0.5f +
                            0.25f * sin(u * 18.84f) * cos(v * 18.84f) +
                            0.15f * sin((u + v) * 31.41f) +
                            0.10f * cos((u - v) * 43.98f)
                        val c = (n.coerceIn(0f, 1f) * 255f).toInt()
                        pixels[y * size + x] = Color.argb(255, c, (c * 0.92f).toInt(), (255 - c / 3).coerceIn(0, 255))
                    }
                }
                bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            }
            BuiltInTexture.RADIAL_GLOW -> {
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val half = size / 2f
                paint.shader = RadialGradient(
                    half, half, half,
                    intArrayOf(
                        Color.argb(255, 34, 211, 238),
                        Color.argb(180, 167, 139, 250),
                        Color.argb(0, 11, 14, 20),
                    ),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            }
            BuiltInTexture.COLOR_SPECTRUM -> {
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = LinearGradient(
                    0f, 0f, size.toFloat(), size.toFloat(),
                    intArrayOf(
                        Color.rgb(34, 211, 238),
                        Color.rgb(16, 185, 129),
                        Color.rgb(245, 158, 11),
                        Color.rgb(251, 113, 133),
                    ),
                    null,
                    Shader.TileMode.MIRROR,
                )
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            }
        }
        return bmp
    }

    fun uploadGlTexture(
        existingTextureId: Int,
        bitmap: Bitmap,
        filterMode: TextureFilterMode,
        wrapMode: TextureWrapMode,
        isGles3: Boolean = true,
    ): Int {
        val ids = IntArray(1)
        val texId = if (existingTextureId != 0) {
            existingTextureId
        } else {
            GLES20.glGenTextures(1, ids, 0)
            ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        applyTextureParameters(filterMode, wrapMode, bitmap.width, bitmap.height, isGles3)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }

    fun uploadRgbaBufferToGlTexture(
        existingTextureId: Int,
        width: Int,
        height: Int,
        canReuseExistingSize: Boolean,
        pixels: ByteBuffer,
        filterMode: TextureFilterMode,
        wrapMode: TextureWrapMode,
        isGles3: Boolean,
    ): Int {
        if (width <= 0 || height <= 0) return existingTextureId
        pixels.rewind()
        if (pixels.remaining() < width * height * 4) return existingTextureId

        val ids = IntArray(1)
        val texId = if (existingTextureId != 0) {
            existingTextureId
        } else {
            GLES20.glGenTextures(1, ids, 0)
            ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        applyTextureParameters(filterMode, wrapMode, width, height, isGles3)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        if (existingTextureId != 0 && canReuseExistingSize) {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                pixels,
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                pixels,
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return texId
    }

    private fun applyTextureParameters(
        filterMode: TextureFilterMode,
        wrapMode: TextureWrapMode,
        width: Int,
        height: Int,
        isGles3: Boolean,
    ) {
        val glFilter = when (filterMode) {
            TextureFilterMode.LINEAR -> GLES20.GL_LINEAR
            TextureFilterMode.NEAREST -> GLES20.GL_NEAREST
        }
        val isPot = width > 0 && height > 0 && (width and (width - 1)) == 0 && (height and (height - 1)) == 0
        val effectiveWrap = if (!isGles3 && !isPot) TextureWrapMode.CLAMP_TO_EDGE else wrapMode
        val glWrap = when (effectiveWrap) {
            TextureWrapMode.REPEAT -> GLES20.GL_REPEAT
            TextureWrapMode.CLAMP_TO_EDGE -> GLES20.GL_CLAMP_TO_EDGE
            TextureWrapMode.MIRRORED_REPEAT -> GLES20.GL_MIRRORED_REPEAT
        }

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, glFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, glFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, glWrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, glWrap)
    }
}
