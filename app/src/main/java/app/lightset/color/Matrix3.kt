package app.lightset.color

/** Row-major 3×3 matrix. */
class Matrix3(val m: DoubleArray) {
    init {
        require(m.size == 9)
    }

    operator fun get(r: Int, c: Int): Double = m[r * 3 + c]

    operator fun times(o: Matrix3): Matrix3 = Matrix3(DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        this[r, 0] * o[0, c] + this[r, 1] * o[1, c] + this[r, 2] * o[2, c]
    })

    operator fun times(v: DoubleArray): DoubleArray = DoubleArray(3) { r ->
        this[r, 0] * v[0] + this[r, 1] * v[1] + this[r, 2] * v[2]
    }

    operator fun times(s: Double): Matrix3 = Matrix3(DoubleArray(9) { m[it] * s })

    operator fun plus(o: Matrix3): Matrix3 = Matrix3(DoubleArray(9) { m[it] + o.m[it] })

    fun inverse(): Matrix3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(kotlin.math.abs(det) > 1e-12) { "Matrix is singular" }
        val inv = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return Matrix3(DoubleArray(9) { inv[it] / det })
    }

    companion object {
        val IDENTITY = Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        fun diagonal(a: Double, b: Double, c: Double) =
            Matrix3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))
    }
}
