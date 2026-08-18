/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bitmap operations used only by the imported-signature editor. */
object ImportedSignatureProcessor {

    fun copyArgb8888(bitmap: Bitmap, mutable: Boolean = true): Bitmap {
        return bitmap.copy(Bitmap.Config.ARGB_8888, mutable)
            ?: throw IllegalArgumentException("Unable to copy signature bitmap")
    }

    /**
     * Estimates a good [removeBackground] threshold for a given photo.
     *
     * The paper is the most frequent BRIGHT luminance. The global mode is not
     * used because photos that include a dark desk or shadow (e.g. a signature
     * photographed on a table) can have a huge black peak that would produce a
     * threshold that keeps the paper instead of removing it.
     */
    fun estimateBackgroundThreshold(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return 160
        val pixels = IntArray(width * height)
        val histogram = IntArray(256)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (pixel in pixels) {
            val luminance =
                (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
            histogram[luminance.coerceIn(0, 255)]++
        }
        var paperLuminance = -1
        var paperCount = -1
        for (luminance in 128..255) {
            if (histogram[luminance] > paperCount) {
                paperCount = histogram[luminance]
                paperLuminance = luminance
            }
        }
        if (paperCount <= 0 || paperCount * 100 < pixels.size) {
            // No dominant bright peak (very dim photo): fall back to the
            // global mode so dim backgrounds are still estimated.
            paperLuminance = -1
            paperCount = -1
            for (luminance in histogram.indices) {
                if (histogram[luminance] > paperCount) {
                    paperCount = histogram[luminance]
                    paperLuminance = luminance
                }
            }
        }
        if (paperCount <= 0) return 160
        return (paperLuminance - 20).coerceIn(60, 240)
    }

    /** Removes bright paper pixels while preserving any existing alpha. */
    fun removeBackground(bitmap: Bitmap, threshold: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val safeThreshold = threshold.coerceIn(0, 255)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var zeroed = 0
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val sourceAlpha = Color.alpha(pixel)
            val luminance = (red * 0.299 + green * 0.587 + blue * 0.114).toInt()
            val alpha = when {
                sourceAlpha == 0 -> 0
                luminance > safeThreshold -> 0
                luminance > safeThreshold - 30 ->
                    (safeThreshold - luminance) * 255 / 30

                else -> 255
            }.coerceIn(0, sourceAlpha)
            if (alpha == 0) zeroed++

            pixels[i] = Color.argb(alpha, red, green, blue)
        }

        Log.d("ImportedSignature", "removeBackground ${width}x$height threshold=$safeThreshold zeroed=$zeroed")
        // Pure pass: the input bitmap is left untouched and the result is built
        // directly from the final pixel array. Bitmaps created via
        // createBitmap(colors) must NEVER be mutated with setPixels — some
        // GPUs/emulators reject it with IllegalStateException — and mutating a
        // bitmap with setPixels then drawing/compressing it can serve stale
        // (pre-mutation) pixels. A fresh bitmap avoids both problems.
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Removes every opaque component that touches the image border (pure
     * connectivity flood, no luminance comparison).
     *
     * Run AFTER [removeBackground]: the threshold pass carves the ink out of
     * the paper (surrounding the strokes with transparent pixels), so this
     * pass can erase any border-connected background — a dark desk, shadow
     * bands, paper texture — whatever its brightness, while interior islands
     * (the black signature ink) survive. This is what makes bimodal photos
     * (bright paper + dark desk) work: the desk is erased here, not by the
     * threshold.
     */
    fun removeBorderConnected(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return bitmap
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val queue = IntArray(width * height)
        val visited = BooleanArray(width * height)
        var head = 0
        var tail = 0

        fun seed(index: Int) {
            if (visited[index] || Color.alpha(pixels[index]) == 0) return
            visited[index] = true
            queue[tail++] = index
        }
        for (x in 0 until width) {
            seed(x)
            seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            seed(y * width + width - 1)
        }

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            if (x > 0) {
                val neighbor = index - 1
                if (!visited[neighbor] && Color.alpha(pixels[neighbor]) > 0) {
                    visited[neighbor] = true
                    queue[tail++] = neighbor
                }
            }
            if (x < width - 1) {
                val neighbor = index + 1
                if (!visited[neighbor] && Color.alpha(pixels[neighbor]) > 0) {
                    visited[neighbor] = true
                    queue[tail++] = neighbor
                }
            }
            if (y > 0) {
                val neighbor = index - width
                if (!visited[neighbor] && Color.alpha(pixels[neighbor]) > 0) {
                    visited[neighbor] = true
                    queue[tail++] = neighbor
                }
            }
            if (y < height - 1) {
                val neighbor = index + width
                if (!visited[neighbor] && Color.alpha(pixels[neighbor]) > 0) {
                    visited[neighbor] = true
                    queue[tail++] = neighbor
                }
            }
        }

        var erased = 0
        for (i in visited.indices) {
            if (visited[i]) {
                pixels[i] = pixels[i] and 0x00FFFFFF
                erased++
            }
        }
        Log.d("ImportedSignature", "removeBorderConnected ${width}x$height erased=$erased")
        // Pure pass (see removeBackground): never setPixels on the result.
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun applyColor(bitmap: Bitmap, colorArgb: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val red = Color.red(colorArgb)
        val green = Color.green(colorArgb)
        val blue = Color.blue(colorArgb)
        val colorAlpha = Color.alpha(colorArgb)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val src = pixels[i]
            val alpha = (Color.alpha(src) * colorAlpha / 255).coerceIn(0, 255)
            if (alpha == 0) continue
            val srcRed = Color.red(src)
            val srcGreen = Color.green(src)
            val srcBlue = Color.blue(src)
            val luminance = (srcRed * 0.299 + srcGreen * 0.587 + srcBlue * 0.114).toInt()
            // Weight by luminance so dark ink stays darker than paper leftovers.
            // The floor keeps the chosen color recognizable.
            val factor = 0.4f + 0.6f * luminance / 255f
            val r = (red * factor).roundToInt()
            val g = (green * factor).roundToInt()
            val b = (blue * factor).roundToInt()
            // Skip pixels already colored by a previous pass (idempotency).
            if (abs(srcRed - r) <= 8 && abs(srcGreen - g) <= 8 && abs(srcBlue - b) <= 8) continue
            pixels[i] = Color.argb(alpha, r, g, b)
        }

        // Pure pass (see removeBackground): never setPixels on the result.
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Erases pixels by setting their alpha to zero, never by painting a color. */
    fun eraseAt(bitmap: Bitmap, centerX: Int, centerY: Int, radius: Int) {
        if (bitmap.width == 0 || bitmap.height == 0) return

        val safeRadius = radius.coerceAtLeast(1)
        val x0 = (centerX - safeRadius).coerceIn(0, bitmap.width - 1)
        val x1 = (centerX + safeRadius).coerceIn(0, bitmap.width - 1)
        val y0 = (centerY - safeRadius).coerceIn(0, bitmap.height - 1)
        val y1 = (centerY + safeRadius).coerceIn(0, bitmap.height - 1)
        val rowWidth = x1 - x0 + 1
        val pixels = IntArray(rowWidth * (y1 - y0 + 1))
        val radiusSquared = safeRadius * safeRadius

        bitmap.getPixels(pixels, 0, rowWidth, x0, y0, rowWidth, y1 - y0 + 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy <= radiusSquared) {
                    val index = (y - y0) * rowWidth + (x - x0)
                    pixels[index] = pixels[index] and 0x00FFFFFF
                }
            }
        }
        bitmap.setPixels(pixels, 0, rowWidth, x0, y0, rowWidth, y1 - y0 + 1)
    }

    /** Erases a continuous path so fast finger movements do not leave gaps. */
    fun eraseLine(
        bitmap: Bitmap,
        fromX: Int?,
        fromY: Int?,
        toX: Int,
        toY: Int,
        radius: Int,
    ) {
        if (fromX == null || fromY == null) {
            eraseAt(bitmap, toX, toY, radius)
            return
        }

        val distance = maxOf(abs(toX - fromX), abs(toY - fromY))
        val steps = distance.coerceAtLeast(1)
        for (step in 0..steps) {
            val fraction = step.toFloat() / steps
            eraseAt(
                bitmap,
                (fromX + (toX - fromX) * fraction).roundToInt(),
                (fromY + (toY - fromY) * fraction).roundToInt(),
                radius,
            )
        }
    }

    fun alphaBounds(bitmap: Bitmap): Rect {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.alpha(pixels[y * width + x]) > 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }

        return if (right < left || bottom < top) {
            Rect()
        } else {
            Rect(left, top, right + 1, bottom + 1)
        }
    }

    /**
     * Builds a fresh software bitmap from [bitmap]'s current pixels. Bitmaps
     * mutated via setPixels can encode/draw stale content on some GPUs, so any
     * pixel array that must survive to disk or screen goes through here.
     */
    fun freshCopy(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun toPngBytes(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        val fresh = freshCopy(bitmap)
        check(fresh.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "Unable to encode imported signature"
        }
        return output.toByteArray()
    }

    fun decodeArgb8888(bytes: ByteArray): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
    }
}
