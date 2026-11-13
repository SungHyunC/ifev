package com.ifev.app.ui.topview

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Draws a [NeonScene] with android.graphics: cyan outlines with a blurred glow, red brake glow, amber turn
 * glow. Paths, paints and gradients are built once per layout, not per frame. Per frame there are a few dozen
 * draw calls at most; the glow layers of the fixed outline are one path drawn three times (wide glow, tight
 * glow, line), the front wheels (which turn) are a second, small path.
 */
internal class NeonPainter {
    private var built = -1

    private val staticPath = Path()
    private val frontWheel = Path()
    private val dynPath = Path()
    private val windowsPath = Path()
    private val brakeLamps = Path()
    private val arcLeft = Path()
    private val arcRight = Path()
    private val airflow = Array(3) { Path() }
    private val matrix = Matrix()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val glowTight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val glowWide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val radial = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val treadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val lampGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }

    private var brakeShader: RadialGradient? = null
    private val turnShader = arrayOfNulls<RadialGradient>(4)
    private var lineW = 3f
    private var pal = NeonPalette.DARK

    private fun build(sc: NeonScene) {
        val sh = sc.shapes
        val s = sh.s
        sh.body.into(staticPath)
        sh.cabin.addTo(staticPath)
        sh.mirrors.addTo(staticPath)
        sh.rearWheels.addTo(staticPath)
        sh.windows.into(windowsPath)
        sh.frontWheelAtOrigin.into(frontWheel)
        sh.brakeLamps.into(brakeLamps)
        sh.turnArcLeft.into(arcLeft)
        sh.turnArcRight.into(arcRight)
        for (i in 0 until 3) sh.airflow[i].into(airflow[i])

        lineW = maxOf(2f, 0.017f * s)
        line.strokeWidth = lineW
        treadPaint.strokeWidth = lineW * 0.8f
        glowTight.strokeWidth = 0.05f * s
        glowTight.maskFilter = BlurMaskFilter(0.10f * s, BlurMaskFilter.Blur.NORMAL)
        glowWide.strokeWidth = 0.10f * s
        glowWide.maskFilter = BlurMaskFilter(0.34f * s, BlurMaskFilter.Blur.NORMAL)
        dot.strokeWidth = 0.055f * s
        dotGlow.strokeWidth = 0.12f * s
        dotGlow.maskFilter = BlurMaskFilter(0.10f * s, BlurMaskFilter.Blur.NORMAL)
        lampGlow.strokeWidth = 0.07f * s
        lampGlow.maskFilter = BlurMaskFilter(0.12f * s, BlurMaskFilter.Blur.NORMAL)

        brakeShader = RadialGradient(
            sh.brakeGlowX, sh.brakeGlowY, sh.brakeGlowR,
            intArrayOf(withA(pal.red, 0.62f * pal.ground), withA(pal.red, 0.22f * pal.ground), withA(pal.red, 0f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        for (i in 0 until 4) {
            turnShader[i] = RadialGradient(
                sh.turnGlowX[i], sh.turnGlowY[i], sh.turnGlowR[i],
                intArrayOf(withA(pal.amber, 0.50f * pal.ground), withA(pal.amber, 0.16f * pal.ground), withA(pal.amber, 0f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        built = sc.layoutVersion
        builtPal = pal
    }
    private var builtPal: NeonPalette? = null

    fun draw(c: Canvas, sc: NeonScene, palette: NeonPalette = NeonPalette.DARK) {
        if (!sc.ready) return
        pal = palette
        if (built != sc.layoutVersion || builtPal !== palette) build(sc)
        val sh = sc.shapes
        val a = sc.alpha

        // 1. Lane dashes, very faint.
        if (sc.laneCount > 0) {
            line.color = pal.line
            line.alpha = alpha(pal.lane * a)
            line.strokeWidth = lineW * 0.8f
            c.drawLines(sc.laneLines, 0, sc.laneCount * 4, line)
            line.strokeWidth = lineW
        }

        // 2. Soft lamp light on the ground: brake, turn signals.
        if (sc.brake > 0f) {
            radial.shader = brakeShader
            radial.alpha = alpha(a)
            c.drawCircle(sh.brakeGlowX, sh.brakeGlowY, sh.brakeGlowR, radial)
        }
        for (i in 0 until 4) {
            val on = if (i % 2 == 0) sc.turnLeft else sc.turnRight
            if (on <= 0f) continue
            radial.shader = turnShader[i]
            radial.alpha = alpha(a)
            c.drawCircle(sh.turnGlowX[i], sh.turnGlowY[i], sh.turnGlowR[i], radial)
        }
        radial.shader = null

        // 3. Glass body (dark or frosted, by theme).
        fill.color = pal.bodyFill
        fill.alpha = alpha(a * 0.72f)
        c.drawPath(staticPath, fill)

        // 4. Battery gauge, restrained: a faint fill rising in the cabin, empty cells above it.
        val gc = if (sc.fillAmber) pal.amber else pal.line
        if (sc.fillVisible) {
            fill.color = gc
            fill.alpha = alpha(0.10f * a)
            c.drawRoundRect(sh.gaugeLeft, sc.fillTop, sh.gaugeRight, sh.gaugeBottom, sh.gaugeRadius, sh.gaugeRadius, fill)
            line.color = gc
            line.alpha = alpha(0.55f * a)
            line.strokeWidth = lineW * 0.6f
            c.drawLine(sh.gaugeLeft + sh.gaugeRadius, sc.fillTop, sh.gaugeRight - sh.gaugeRadius, sc.fillTop, line)
        }
        if (sc.usedVisible) {
            line.color = gc
            line.alpha = alpha(0.22f * a)
            line.strokeWidth = lineW * 0.6f
            c.drawRoundRect(sh.gaugeLeft, sc.usedTop, sh.gaugeRight, sc.fillTop, sh.gaugeRadius, sh.gaugeRadius, line)
        }
        line.strokeWidth = lineW

        // 5. Cabin airflow.
        for (i in 0 until 3) {
            if (sc.airAlpha[i] <= 0f) continue
            line.color = pal.line
            line.alpha = alpha(sc.airAlpha[i] * a)
            line.strokeWidth = lineW * 0.7f
            c.save()
            c.translate(sc.airSway[i], 0f)
            c.drawPath(airflow[i], line)
            c.restore()
        }
        line.strokeWidth = lineW

        // 6. The outline: wide glow, tight glow, the line itself.
        dynPath.rewind()
        for (w in 0 until 2) {
            matrix.setRotate(sc.steerDeg)
            matrix.postTranslate(sh.frontWheelX[w], sh.frontWheelY)
            dynPath.addPath(frontWheel, matrix)
        }
        glowWide.color = pal.line
        glowWide.alpha = alpha(pal.glowWide * a)
        c.drawPath(staticPath, glowWide)
        glowTight.color = pal.line
        glowTight.alpha = alpha(pal.glowTight * a)
        c.drawPath(staticPath, glowTight)
        c.drawPath(dynPath, glowTight)
        line.color = pal.line
        line.alpha = alpha(a)
        c.drawPath(staticPath, line)
        c.drawPath(dynPath, line)
        line.alpha = alpha(0.55f * a)
        line.strokeWidth = lineW * 0.75f
        c.drawPath(windowsPath, line)
        line.strokeWidth = lineW

        // 7. Tread lines in the wheels.
        treadPaint.color = pal.line
        treadPaint.alpha = alpha(0.85f * a)
        c.drawLines(sc.treadLines, 0, sc.treadLines.size, treadPaint)

        // 8. Regeneration: light particles from the front wheels to the battery.
        if (sc.regenMid + sc.regenEdge > 0) {
            dotGlow.color = pal.line
            dotGlow.alpha = alpha(0.7f * sc.regenAlpha * a)
            dot.color = pal.line
            if (sc.regenMid > 0) {
                c.drawPoints(sc.regenPoints, 0, sc.regenMid * 2, dotGlow)
                dot.alpha = alpha(sc.regenAlpha * a)
                c.drawPoints(sc.regenPoints, 0, sc.regenMid * 2, dot)
            }
            if (sc.regenEdge > 0) {
                dot.alpha = alpha(0.4f * sc.regenAlpha * a)
                c.drawPoints(sc.regenPoints, sc.regenMid * 2, sc.regenEdge * 2, dot)
            }
        }

        // 9. Lamp lines on the car.
        if (sc.brake > 0f) {
            lampGlow.color = pal.red
            lampGlow.alpha = alpha(a)
            c.drawPath(brakeLamps, lampGlow)
            line.color = pal.redCore
            line.alpha = alpha(a)
            line.strokeWidth = lineW * 1.4f
            c.drawPath(brakeLamps, line)
        }
        if (sc.turnLeft > 0f || sc.turnRight > 0f) {
            lampGlow.color = pal.amber
            lampGlow.alpha = alpha(a)
            line.color = pal.amberCore
            line.alpha = alpha(a)
            line.strokeWidth = lineW * 1.4f
            if (sc.turnLeft > 0f) { c.drawPath(arcLeft, lampGlow); c.drawPath(arcLeft, line) }
            if (sc.turnRight > 0f) { c.drawPath(arcRight, lampGlow); c.drawPath(arcRight, line) }
        }
        line.strokeWidth = lineW
    }

    private fun alpha(x: Float): Int = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private fun withA(rgb: Int, a: Float): Int = (alpha(a) shl 24) or (rgb and 0x00FFFFFF)

    private fun PathSpec.into(p: Path) {
        p.rewind()
        addTo(p)
    }

    private fun PathSpec.addTo(p: Path) {
        var i = 0
        for (v in verbs) {
            when (v) {
                PathSpec.MOVE -> { p.moveTo(coords[i], coords[i + 1]); i += 2 }
                PathSpec.LINE -> { p.lineTo(coords[i], coords[i + 1]); i += 2 }
                PathSpec.QUAD -> { p.quadTo(coords[i], coords[i + 1], coords[i + 2], coords[i + 3]); i += 4 }
                PathSpec.CUBIC -> { p.cubicTo(coords[i], coords[i + 1], coords[i + 2], coords[i + 3], coords[i + 4], coords[i + 5]); i += 6 }
                PathSpec.CLOSE -> p.close()
            }
        }
    }
}
