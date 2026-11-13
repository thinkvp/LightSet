package app.lightset.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.lightset.sun.Sky
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sky icons: two-tone, in the sky's own colours (amber sun, grey cloud, blue rain), so
 * they're drawn untinted, with Image rather than Icon. Thick skies stack a darker cloud
 * behind; haze is the sun behind grey bands. Drawn on a 24-unit grid like Material icons.
 */
internal fun skyIcon(sky: Sky): ImageVector = SkyIcons.getValue(sky)

private val Amber = Color(0xFFFFC94D)
private val CloudLight = Color(0xFFB0B4C0)
private val CloudMid = Color(0xFF8A8E9A)
private val CloudDark = Color(0xFF5C5F69)
private val Rain = Color(0xFF7FB0DD)
private val RainDeep = Color(0xFF4A86D8)
private val Snow = Color(0xFFE0E6F0)
private val Fog = Color(0xFF9A9AA0)
private val Moon = Color(0xFFECE4C6)

private const val CLOUD = "M7 18H17.5A3.5 3.5 0 0 0 17.5 11A5.5 5.5 0 0 0 7 10.5A3.75 3.75 0 0 0 7 18Z"
private const val MOON = "M20 14.5A8 8 0 1 1 9.5 4A9 9 0 0 0 20 14.5Z"
private const val BOLT = "M13.2 13.2L9 19H12.2L10.6 23.2L15.8 16.8H12.7Z"

private val SkyIcons: Map<Sky, ImageVector> = Sky.entries.associateWith { sky ->
    icon(sky.name) {
        when (sky) {
            Sky.SUN -> {
                stroke(rays(12f, 12f, 6.6f, 9.4f), Amber, 2f)
                fill(circle(12f, 12f, 4.6f), Amber)
            }
            Sky.HAZY -> {
                fill(circle(12f, 10.5f, 6f), Amber)
                fill(pill(3f, 13f, 18f, 2.2f) + pill(5f, 17f, 14f, 2.2f), CloudLight, alpha = 0.9f)
                fill(pill(7f, 9f, 10f, 2f), CloudLight, alpha = 0.45f)
            }
            Sky.OVERCAST -> thickCloud(CloudLight)
            Sky.CLOUDY -> thickCloud(CloudMid)
            Sky.CLEAR -> {
                fill(MOON, Moon)
                stroke(star(18.5f, 4.5f, 1.6f) + star(21f, 9f, 1f), Amber, 1.3f)
            }
            Sky.PARTLY -> {
                group(4f, -2f, 0.62f) { fill(MOON, Moon) }
                group(0f, 1f) { fill(CLOUD, CloudMid) }
            }
            Sky.FOG -> {
                fill(pill(3f, 6f, 18f, 2.2f), Fog, alpha = 0.5f)
                fill(pill(5f, 10f, 15f, 2.2f), Fog, alpha = 0.7f)
                fill(pill(3f, 14f, 17f, 2.2f), Fog, alpha = 0.85f)
                fill(pill(6f, 18f, 13f, 2.2f), Fog)
            }
            Sky.DRIZZLE -> {
                raisedCloud(CloudLight)
                fill(circle(8f, 18.5f, 1.2f) + circle(12f, 20.5f, 1.2f) + circle(16f, 18.5f, 1.2f), Rain)
            }
            Sky.RAIN -> {
                raisedCloud(CloudMid)
                stroke("M8.5 17L6.8 21.2M12.5 17L10.8 22M16.5 17L14.8 21.2", RainDeep, 2.1f)
            }
            Sky.SNOW -> {
                raisedCloud(CloudLight)
                stroke(asterisk(8f, 19f, 1.8f) + asterisk(12.5f, 20.6f, 1.8f) + asterisk(17f, 19f, 1.8f), Snow, 1.4f)
            }
            Sky.STORM -> {
                raisedCloud(CloudDark)
                fill(BOLT, Amber)
            }
        }
    }
}

/** A dark cloud behind a [front] one: overcast, or cloudy at night. */
private fun ImageVector.Builder.thickCloud(front: Color) {
    group(2.5f, -3.5f, 0.82f) { fill(CLOUD, CloudDark) }
    group(-1f, 1f) { fill(CLOUD, front) }
}

/** The cloud lifted to leave room for what falls from it. */
private fun ImageVector.Builder.raisedCloud(color: Color) = group(0f, -3.5f) { fill(CLOUD, color) }

private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder("Sky.$name", 24.dp, 24.dp, 24f, 24f).apply(build).build()

private fun ImageVector.Builder.fill(d: String, color: Color, alpha: Float = 1f) {
    addPath(addPathNodes(d), fill = SolidColor(color), fillAlpha = alpha)
}

private fun ImageVector.Builder.stroke(d: String, color: Color, width: Float) {
    addPath(addPathNodes(d), stroke = SolidColor(color), strokeLineWidth = width, strokeLineCap = StrokeCap.Round)
}

private fun ImageVector.Builder.group(dx: Float, dy: Float, scale: Float = 1f, content: ImageVector.Builder.() -> Unit) {
    addGroup(translationX = dx, translationY = dy, scaleX = scale, scaleY = scale)
    content()
    clearGroup()
}

private fun n(v: Float) = "%.2f".format(Locale.US, v)

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${n(cx - r)} ${n(cy)}A${n(r)} ${n(r)} 0 1 0 ${n(cx + r)} ${n(cy)}A${n(r)} ${n(r)} 0 1 0 ${n(cx - r)} ${n(cy)}Z"

/** A bar with round ends. */
private fun pill(x: Float, y: Float, w: Float, h: Float): String {
    val r = h / 2
    return "M${n(x + r)} ${n(y)}H${n(x + w - r)}A${n(r)} ${n(r)} 0 0 1 ${n(x + w - r)} ${n(y + h)}" +
        "H${n(x + r)}A${n(r)} ${n(r)} 0 0 1 ${n(x + r)} ${n(y)}Z"
}

/** Eight rays around ([cx], [cy]) from radius [r1] out to [r2]. */
private fun rays(cx: Float, cy: Float, r1: Float, r2: Float) = (0 until 8).joinToString("") { i ->
    val a = i * PI / 4
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    "M${n(cx + r1 * c)} ${n(cy + r1 * s)}L${n(cx + r2 * c)} ${n(cy + r2 * s)}"
}

private fun star(x: Float, y: Float, r: Float) = "M${n(x - r)} ${n(y)}H${n(x + r)}M${n(x)} ${n(y - r)}V${n(y + r)}"

/** A six-armed snowflake. */
private fun asterisk(x: Float, y: Float, r: Float) = (0 until 3).joinToString("") { i ->
    val a = (i * 60 + 90) * PI / 180
    val dx = (r * cos(a)).toFloat()
    val dy = (r * sin(a)).toFloat()
    "M${n(x - dx)} ${n(y - dy)}L${n(x + dx)} ${n(y + dy)}"
}
