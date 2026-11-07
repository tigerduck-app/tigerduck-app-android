package org.ntust.app.tigerduck.shared

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build

/**
 * The one home for how an accent of several colors fills a shape, in each of its four styles, as
 * the iPhone draws them: linear along its direction, radial out from the middle to an ellipse
 * touching each side, angular clockwise from the top round to its first color again, and a 3 by 3
 * mesh. The phone's screens and widgets and the watch all paint with it, through a brush or a
 * bitmap of their own.
 *
 * Android has no mesh gradient, so on a screen from Android 13 ([MESH_MIN_SDK]) a mesh is drawn by
 * an AGSL shader through its nine colors (see [meshAt], which works out the same colors). Below
 * that, it is the iPhone's own stand-in for where a mesh can't be drawn: a linear gradient of its
 * stops from the top left to the bottom right. A bitmap ([bitmap], the widgets') is drawn in
 * software, which takes no runtime shader on any Android, and has its mesh worked out pixel by
 * pixel instead, through the same nine colors.
 */
object AccentShader {

    /** The first Android with runtime shaders, which a mesh needs. */
    const val MESH_MIN_SDK = Build.VERSION_CODES.TIRAMISU

    /** Whether Android [sdkInt] draws a mesh as one, rather than its stand-in. */
    fun drawsMesh(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= MESH_MIN_SDK

    /**
     * The shader that fills the [width] by [height] box at [left], [top] with [gradient], each of
     * its colors as [tone] draws it (see [rampColors] for [samples]). A single color is a gradient of
     * itself, which callers draw as a plain color instead. A mesh is one on this device's Android
     * ([drawsMesh]), and its stand-in below it.
     */
    fun create(
        gradient: AccentGradient,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        tone: (Int) -> Int = ::opaque,
        samples: Int = 0,
    ): Shader {
        val w = width.coerceAtLeast(1f)
        val h = height.coerceAtLeast(1f)
        val colors = gradient.rampColors(tone, samples).toIntArray()
        val cx = left + w / 2
        val cy = top + h / 2
        return when (gradient.style) {
            AccentGradientStyle.Linear -> linear(gradient.direction, left, top, w, h, colors)
            AccentGradientStyle.Radial -> RadialGradient(cx, cy, w / 2, colors, null, Shader.TileMode.CLAMP).apply {
                // A circle as wide as the box, squeezed to its height: an ellipse touching each side.
                setLocalMatrix(Matrix().apply { setScale(1f, h / w, cx, cy) })
            }
            AccentGradientStyle.Angular -> SweepGradient(cx, cy, colors, null).apply {
                // Android sweeps from the right; the iPhone's starts at the top.
                setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
            }
            AccentGradientStyle.Mesh -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mesh(gradient.meshTones(tone), left, top, w, h)
            } else {
                linear(AccentGradientDirection.TopLeftToBottomRight, left, top, w, h, colors)
            }
        }
    }

    /**
     * [gradient] drawn over a [width] by [height] bitmap, each color as [tone] draws it: what a
     * widget, which can only show images, fills an accent mark with.
     */
    fun bitmap(gradient: AccentGradient, width: Int, height: Int, tone: (Int) -> Int = ::opaque): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        if (gradient.style == AccentGradientStyle.Mesh) {
            val mesh = gradient.meshTones(tone)
            val pixels = IntArray(w * h) { meshAt(mesh, (it % w) / (w - 1f).coerceAtLeast(1f), (it / w) / (h - 1f).coerceAtLeast(1f)) }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = create(gradient, 0f, 0f, w.toFloat(), h.toFloat(), tone) }
            Canvas(this).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    private fun linear(
        direction: AccentGradientDirection,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        colors: IntArray,
    ): Shader = LinearGradient(
        left + direction.startX * width,
        top + direction.startY * height,
        left + direction.endX * width,
        top + direction.endY * height,
        colors,
        null,
        Shader.TileMode.CLAMP,
    )

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun mesh(colors: List<Int>, left: Float, top: Float, width: Float, height: Float): Shader =
        RuntimeShader(MESH_AGSL).apply {
            setFloatUniform("origin", left, top)
            setFloatUniform("size", width, height)
            // sRGB components as they are, as the other styles' gradients mix them.
            colors.forEachIndexed { index, color ->
                setFloatUniform("c$index", channel(color, 16), channel(color, 8), channel(color, 0), 1f)
            }
        }

    private fun channel(argb: Int, shift: Int): Float = ((argb shr shift) and 0xFF) / 255f

    /**
     * Each axis a quadratic through its three colors (see [quadratic]), so the fill passes through
     * all nine, as the iPhone's mesh does, and is smooth between them.
     */
    private val MESH_AGSL = """
        uniform float2 origin;
        uniform float2 size;
        uniform float4 c0;
        uniform float4 c1;
        uniform float4 c2;
        uniform float4 c3;
        uniform float4 c4;
        uniform float4 c5;
        uniform float4 c6;
        uniform float4 c7;
        uniform float4 c8;

        float3 weights(float t) {
            return float3(2.0 * (t - 0.5) * (t - 1.0), -4.0 * t * (t - 1.0), 2.0 * t * (t - 0.5));
        }

        float3 along(float3 w, float3 a, float3 b, float3 c) {
            return a * w.x + b * w.y + c * w.z;
        }

        half4 main(float2 position) {
            float2 uv = clamp((position - origin) / size, 0.0, 1.0);
            float3 across = weights(uv.x);
            float3 top = along(across, c0.rgb, c1.rgb, c2.rgb);
            float3 middle = along(across, c3.rgb, c4.rgb, c5.rgb);
            float3 bottom = along(across, c6.rgb, c7.rgb, c8.rgb);
            float3 rgb = along(weights(uv.y), top, middle, bottom);
            return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
        }
    """.trimIndent()
}
