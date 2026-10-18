package id.dsngroup.kalkulatortkpanen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Menggambar area tracking (peta offline + jalur merah) menjadi gambar. */
object TrackRender {

    fun render(
        ctx: Context,
        map: MapInfo?,
        pts: List<TPoint>,
        w: Int,
        h: Int,
        title: String? = null,
        dots: Boolean = false,      // true: gambar setiap titik (pokok sensus)
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFDDE8DD.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = h * 0.05f
            typeface = Typeface.DEFAULT_BOLD
        }
        if (pts.isEmpty()) {
            c.drawText("Belum ada titik tracking", w * 0.05f, h * 0.5f, text)
            return bmp
        }

        // 1) Proyeksi titik ke "satuan peta": piksel gambar peta, atau meter jika tanpa peta
        val n = pts.size
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        if (map != null) {
            for (i in 0 until n) {
                val (x, y) = map.px(pts[i].lat, pts[i].lon)
                xs[i] = x; ys[i] = y
            }
        } else {
            val lat0 = pts[0].lat
            val lon0 = pts[0].lon
            val k = cos(Math.toRadians(lat0))
            for (i in 0 until n) {
                xs[i] = (pts[i].lon - lon0) * 111320.0 * k
                ys[i] = -(pts[i].lat - lat0) * 110574.0
            }
        }

        // 2) Jendela tampilan: seluruh jalur + margin, minimal selebar ~800 m (peta) / 160 m (tanpa peta)
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val minSpan = if (map != null) 110.0 else 160.0
        val needW = max((maxX - minX) * 1.35, minSpan)
        val needH = max((maxY - minY) * 1.35, minSpan * h / w)
        val s = max(needW / w, needH / h)          // satuan peta per piksel keluaran
        val viewW = s * w
        val viewH = s * h
        val left = cx - viewW / 2
        val top = cy - viewH / 2

        // 3) Latar peta
        if (map != null) drawMap(ctx, map, c, left, top, viewW, viewH, s)

        // 4) Jalur merah
        val path = Path()
        for (i in 0 until n) {
            val ox = ((xs[i] - left) / s).toFloat()
            val oy = ((ys[i] - top) / s).toFloat()
            if (i == 0 || pts[i].brk) path.moveTo(ox, oy) else path.lineTo(ox, oy)    // jeda: putus garis
        }
        val sw = max(5f, w * 0.006f)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = sw
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        c.drawPath(path, line)
        if (dots) {
            val dp2 = Paint(Paint.ANTI_ALIAS_FLAG)
            val r0 = max(6f, w * 0.0075f)
            for (i in 0 until n) {
                val ox = ((xs[i] - left) / s).toFloat()
                val oy = ((ys[i] - top) / s).toFloat()
                dp2.color = Color.WHITE
                c.drawCircle(ox, oy, r0 + 2f, dp2)
                dp2.color = 0xFFE53935.toInt()
                c.drawCircle(ox, oy, r0, dp2)
            }
        }
        // titik awal (hijau) dan akhir (biru)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val rr = sw * 1.9f
        dot.color = Color.WHITE
        c.drawCircle(((xs[0] - left) / s).toFloat(), ((ys[0] - top) / s).toFloat(), rr + 2f, dot)
        c.drawCircle(((xs[n - 1] - left) / s).toFloat(), ((ys[n - 1] - top) / s).toFloat(), rr + 2f, dot)
        dot.color = 0xFF2E7D32.toInt()
        c.drawCircle(((xs[0] - left) / s).toFloat(), ((ys[0] - top) / s).toFloat(), rr, dot)
        dot.color = 0xFF1565C0.toInt()
        c.drawCircle(((xs[n - 1] - left) / s).toFloat(), ((ys[n - 1] - top) / s).toFloat(), rr, dot)

        // 5) Judul kecil di pojok kiri atas
        if (!title.isNullOrBlank()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = h * 0.05f
                typeface = Typeface.DEFAULT_BOLD
            }
            val pad = h * 0.02f
            val tw = tp.measureText(title)
            val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
            c.drawRect(0f, 0f, tw + 2 * pad, tp.textSize + 2 * pad, bg)
            c.drawText(title, pad, pad + tp.textSize * 0.85f, tp)
        }
        return bmp
    }

    private fun drawMap(ctx: Context, map: MapInfo, c: Canvas, left: Double, top: Double, viewW: Double, viewH: Double, s: Double) {
        val il = max(0.0, left)
        val itop = max(0.0, top)
        val ir = min(map.w.toDouble(), left + viewW)
        val ib = min(map.h.toDouble(), top + viewH)
        if (ir <= il || ib <= itop) return
        val src = Rect(il.toInt(), itop.toInt(), min(map.w, ceil(ir).toInt()), min(map.h, ceil(ib).toInt()))
        if (src.width() <= 0 || src.height() <= 0) return
        val file = MapStore.imageFile(ctx, map.id)
        if (!file.exists()) return
        try {
            @Suppress("DEPRECATION")
            val dec = BitmapRegionDecoder.newInstance(file.path, false) ?: return
            var part: Bitmap? = null
            try {
                part = dec.decodeRegion(src, BitmapFactory.Options())
            } finally {
                dec.recycle()
            }
            if (part == null) return
            val dst = RectF(
                ((src.left - left) / s).toFloat(), ((src.top - top) / s).toFloat(),
                ((src.right - left) / s).toFloat(), ((src.bottom - top) / s).toFloat(),
            )
            c.drawBitmap(part, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            part.recycle()
        } catch (e: Exception) {
        } catch (e: OutOfMemoryError) {
        }
    }
}
