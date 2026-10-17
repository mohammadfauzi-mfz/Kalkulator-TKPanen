package id.dsngroup.kalkulatortkpanen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Peta offline yang bisa digeser & di-zoom, dengan lapisan posisi, tracking, tanda, dan pengukuran. */
class MapCanvasView(context: Context) : View(context) {

    enum class Mode { NORMAL, UKUR_JARAK, UKUR_LUAS }

    interface Listener {
        fun onMapTap(lat: Double, lon: Double)
        fun onMapLongPress(lat: Double, lon: Double)
        fun onMeasureChanged()
    }

    var listener: Listener? = null
    var mode = Mode.NORMAL
        set(v) { field = v; invalidate() }
    var showLabels = true
        set(v) { field = v; invalidate() }
    var showHistory = true
        set(v) { field = v; invalidate() }

    // --- peta ---
    private var info: MapInfo? = null
    private var bmp: Bitmap? = null
    private var s = 1f                 // piksel layar per piksel peta
    private var tx = 0f
    private var ty = 0f
    private var fitted = false

    // --- data ---
    private var history: List<FloatArray> = emptyList()   // pasangan x,y (piksel peta)
    private var live: List<FloatArray> = emptyList()
    private var me: Triple<Float, Float, Float>? = null  // x, y, akurasi (m)
    private var sel: Pair<Float, Float>? = null
    private val measure = ArrayList<Pair<Double, Double>>()  // lat, lon

    val measurePoints: List<Pair<Double, Double>> get() = measure
    val currentMap: MapInfo? get() = info

    // --- cat ---
    private val dens = resources.displayMetrics.density
    private fun d(v: Float) = v * dens
    private val bgPaint = Paint().apply { color = 0xFFE9EFE4.toInt() }
    private val gridPaint = Paint().apply { color = 0xFFCBD6C4.toInt(); strokeWidth = 1f }
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt(); style = Paint.Style.STROKE; strokeWidth = d(3f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val histPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAC62828.toInt(); style = Paint.Style.STROKE; strokeWidth = d(2f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val measLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.STROKE; strokeWidth = d(3f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(d(10f), d(6f)), 0f)
    }
    private val measFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x331565C0; style = Paint.Style.FILL }

    // ---------- data ----------

    /** Ganti peta. [bitmap] boleh null (latar polos). Tampilan diatur ulang agar pas di layar. */
    fun setMap(m: MapInfo, bitmap: Bitmap?) {
        detail = null
        detailGen++
        info = m
        bmp = bitmap
        fitted = false
        measure.clear()
        requestLayoutFit()
    }

    private fun requestLayoutFit() { fitted = false; invalidate() }

    private fun fit() {
        val m = info ?: return
        if (width == 0 || height == 0) return
        s = min(width.toFloat() / m.w, height.toFloat() / m.h)
        tx = (width - m.w * s) / 2f
        ty = (height - m.h * s) / 2f
        fitted = true
    }

    fun setHistory(tracks: List<List<TPoint>>) {
        val m = info
        history = if (m == null) emptyList() else tracks.flatMap { t -> Geo.segments(t).map { toXY(m, it) } }
        invalidate()
    }

    fun setLive(pts: List<TPoint>) {
        val m = info
        live = if (m == null) emptyList() else Geo.segments(pts).map { toXY(m, it) }
        invalidate()
    }

    private fun toXY(m: MapInfo, pts: List<TPoint>): FloatArray {
        val a = FloatArray(pts.size * 2)
        for (i in pts.indices) {
            val (x, y) = m.px(pts[i].lat, pts[i].lon)
            a[i * 2] = x.toFloat(); a[i * 2 + 1] = y.toFloat()
        }
        return a
    }

    fun setMe(lat: Double, lon: Double, acc: Float) {
        val m = info ?: return
        val (x, y) = m.px(lat, lon)
        me = Triple(x.toFloat(), y.toFloat(), acc)
        invalidate()
    }

    fun clearMe() { me = null; invalidate() }

    /** Arah hadap HP (derajat dari utara, searah jarum jam); null = sembunyikan kerucut arah. */
    private var heading: Float? = null
    fun setHeading(deg: Float?) {
        if (heading == deg) return
        heading = deg
        if (me != null) invalidate()
    }

    fun setSelected(lat: Double, lon: Double) {
        val m = info ?: return
        val (x, y) = m.px(lat, lon)
        sel = Pair(x.toFloat(), y.toFloat())
        invalidate()
    }

    fun clearSelected() { sel = null; invalidate() }

    fun hasMe() = me != null

    fun centerOn(lat: Double, lon: Double, zoomTo: Float? = null) {
        val m = info ?: return
        if (!fitted) fit()
        val (x, y) = m.px(lat, lon)
        if (zoomTo != null) s = zoomTo.coerceIn(minScale(), maxScale())
        tx = (width / 2f - x.toFloat() * s)
        ty = (height / 2f - y.toFloat() * s)
        invalidate()
    }

    /** Zoom agar kotak lat/lon tampak penuh. */
    fun fitBounds(pts: List<TPoint>) {
        val m = info ?: return
        if (pts.isEmpty() || width == 0) return
        var x1 = Double.MAX_VALUE; var y1 = Double.MAX_VALUE; var x2 = -Double.MAX_VALUE; var y2 = -Double.MAX_VALUE
        for (p in pts) {
            val (x, y) = m.px(p.lat, p.lon)
            x1 = min(x1, x); x2 = max(x2, x); y1 = min(y1, y); y2 = max(y2, y)
        }
        val minSpan = 120.0
        val w = max(x2 - x1, minSpan)
        val h = max(y2 - y1, minSpan)
        val ns = (min(width / (w * 1.4), height / (h * 1.4))).toFloat().coerceIn(minScale(), maxScale())
        s = ns
        val cx = (x1 + x2) / 2
        val cy = (y1 + y2) / 2
        tx = (width / 2f - cx.toFloat() * s)
        ty = (height / 2f - cy.toFloat() * s)
        fitted = true
        invalidate()
    }

    fun zoomBy(f: Float) {
        val old = s
        s = (s * f).coerceIn(minScale(), maxScale())
        val k = s / old
        val cx = width / 2f
        val cy = height / 2f
        tx = cx - (cx - tx) * k
        ty = cy - (cy - ty) * k
        invalidate()
    }

    // ---------- pengukuran ----------

    fun addMeasurePoint(lat: Double, lon: Double) {
        measure.add(Pair(lat, lon)); invalidate(); listener?.onMeasureChanged()
    }

    fun undoMeasure() {
        if (measure.isNotEmpty()) measure.removeAt(measure.size - 1)
        invalidate(); listener?.onMeasureChanged()
    }

    fun clearMeasure() { measure.clear(); invalidate(); listener?.onMeasureChanged() }

    private fun pts() = measure.map { TPoint(it.first, it.second, 0L) }

    fun measureLength(): Double = Geo.length(pts())
    fun measureArea(): Double = Geo.areaM2(pts())
    fun measurePerimeter(): Double {
        val p = pts()
        return if (p.size < 3) Geo.length(p) else Geo.length(p) + Geo.dist(p.last(), p.first())
    }

    // ---------- gambar ----------

    private fun minScale(): Float {
        val m = info ?: return 0.05f
        if (width == 0) return 0.05f
        return min(width.toFloat() / m.w, height.toFloat() / m.h) * 0.6f
    }

    var maxZoom = 6f
    private fun maxScale() = maxZoom

    private fun sx(x: Float) = x * s + tx
    private fun sy(y: Float) = y * s + ty

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!fitted) fit()
    }

    override fun onDraw(c: Canvas) {
        val m = info
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (m == null) {
            text.color = 0xFF555555.toInt(); text.textSize = d(14f)
            c.drawText("Belum ada peta", width / 2f, height / 2f, text)
            return
        }
        if (!fitted) fit()

        c.save()
        c.translate(tx, ty)
        c.scale(s, s)
        val b = bmp
        val ts = tileSrc
        if (ts != null) { /* tile online digambar di bawah */ }
        else if (b != null) {
            c.drawBitmap(b, null, RectF(0f, 0f, m.w.toFloat(), m.h.toFloat()), imgPaint)
            val dt = detail
            if (dt != null) c.drawBitmap(dt, null, detailRect, imgPaint)
        }
        else drawGrid(c, m)
        c.restore()
        if (ts != null) drawTiles(c, m, ts)

        if (showHistory) for (h in history) drawPath(c, h, histPaint)
        for (seg in live) drawPath(c, seg, trackPaint)
        val firstSeg = live.firstOrNull()
        val lastSeg = live.lastOrNull()
        if (firstSeg != null && lastSeg != null && firstSeg.size >= 2 && lastSeg.size >= 2) {
            dot(c, sx(firstSeg[0]), sy(firstSeg[1]), 0xFF2E7D32.toInt(), d(6f))
            dot(c, sx(lastSeg[lastSeg.size - 2]), sy(lastSeg[lastSeg.size - 1]), 0xFF1565C0.toInt(), d(6f))
        }
        drawMeasure(c, m)
        sel?.let { (x, y) -> drawCross(c, sx(x), sy(y)) }
        me?.let { (x, y, acc) -> drawMe(c, m, x, y, acc) }
        drawScaleBar(c, m)
        removeCallbacks(hiRunnable)
        if (needDetail()) postDelayed(hiRunnable, 300)
    }

    // ---------- detail resolusi tinggi (render ulang PDF saat zoom dekat) ----------

    /** Penyedia gambar wilayah (x0,y0,x1,y1 dalam piksel peta, lebar, tinggi). Null = tidak tersedia. */
    var hiRes: ((Float, Float, Float, Float, Int, Int) -> Bitmap?)? = null
        set(v) {
            field = v
            detail = null
            detailGen++
            maxZoom = if (v != null) 28f else 6f
            invalidate()
        }
    private var detail: Bitmap? = null
    private val detailRect = RectF()
    private var detailScale = 0f
    private var detailGen = 0
    private val hiPool = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val hiRunnable = Runnable { requestDetail() }

    private fun needDetail(): Boolean {
        if (hiRes == null || tileSrc != null || bmp == null || s <= 1.15f) return false
        val d = detail ?: return true
        val m = info ?: return false
        val vx0 = max(0f, -tx / s); val vy0 = max(0f, -ty / s)
        val vx1 = min(m.w.toFloat(), (width - tx) / s); val vy1 = min(m.h.toFloat(), (height - ty) / s)
        val covered = vx0 >= detailRect.left - 1 && vy0 >= detailRect.top - 1 && vx1 <= detailRect.right + 1 && vy1 <= detailRect.bottom + 1
        val scaleOk = Math.abs(Math.log((s / detailScale).toDouble())) < 0.35
        return !(covered && scaleOk) && d.width > 0
    }

    private fun requestDetail() {
        val m = info ?: return
        val f = hiRes ?: return
        if (width == 0) return
        val mx = (width / s) * 0.15f
        val my = (height / s) * 0.15f
        val x0 = max(0f, -tx / s - mx); val y0 = max(0f, -ty / s - my)
        val x1 = min(m.w.toFloat(), (width - tx) / s + mx); val y1 = min(m.h.toFloat(), (height - ty) / s + my)
        if (x1 - x0 < 2f || y1 - y0 < 2f) return
        var ow = ((x1 - x0) * s).toInt()
        var oh = ((y1 - y0) * s).toInt()
        val k = min(1f, min(2400f / ow, 2400f / oh))
        ow = max(64, (ow * k).toInt()); oh = max(64, (oh * k).toInt())
        val gen = ++detailGen
        val sNow = s
        hiPool.execute {
            val b = try { f(x0, y0, x1, y1, ow, oh) } catch (e: Throwable) { null }
            post {
                if (gen == detailGen && b != null) {
                    detail = b
                    detailRect.set(x0, y0, x1, y1)
                    detailScale = sNow
                    invalidate()
                }
            }
        }
    }

    // ---------- peta online (tile) ----------

    class TileSrc(val key: String, val name: String, val url: String, val attr: String, val maxZ: Int)

    var tileSrc: TileSrc? = null
        set(v) { field = v; invalidate() }

    private val tileCache = android.util.LruCache<String, Bitmap>(140)
    private val inflight = HashSet<String>()
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val tileDir = java.io.File(context.cacheDir, "tiles")

    private fun tileX(lon: Double, n: Double) = (lon + 180.0) / 360.0 * n
    private fun tileY(lat: Double, n: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * n
    }
    private fun tileLon(x: Int, n: Double) = x / n * 360.0 - 180.0
    private fun tileLat(y: Int, n: Double) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / n))))

    private fun drawTiles(c: Canvas, m: MapInfo, ts: TileSrc) {
        val tl = toLatLon(0f, 0f) ?: return
        val br = toLatLon(width.toFloat(), height.toFloat()) ?: return
        val mps = m.metersPerPixel / s
        if (mps <= 0.0) return
        val latC = (tl.first + br.first) / 2
        var z = Math.round(Math.log(156543.03392 * Math.cos(Math.toRadians(latC)) / mps) / Math.log(2.0)).toInt().coerceIn(2, ts.maxZ)
        var x0: Int; var x1: Int; var y0: Int; var y1: Int
        while (true) {
            val n = Math.pow(2.0, z.toDouble())
            x0 = Math.floor(tileX(tl.second, n)).toInt().coerceIn(0, n.toInt() - 1)
            x1 = Math.floor(tileX(br.second, n)).toInt().coerceIn(0, n.toInt() - 1)
            y0 = Math.floor(tileY(tl.first, n)).toInt().coerceIn(0, n.toInt() - 1)
            y1 = Math.floor(tileY(br.first, n)).toInt().coerceIn(0, n.toInt() - 1)
            if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 70 || z <= 2) break
            z--
        }
        val n = Math.pow(2.0, z.toDouble())
        for (ty in y0..y1) for (tx2 in x0..x1) {
            val key = "${ts.key}/$z/$tx2/$ty"
            val bm = tileCache.get(key)
            if (bm == null) { requestTile(ts, z, tx2, ty, key); continue }
            val (ax, ay) = m.px(tileLat(ty, n), tileLon(tx2, n))
            val (bx, by) = m.px(tileLat(ty + 1, n), tileLon(tx2 + 1, n))
            c.drawBitmap(bm, null, RectF(sx(ax.toFloat()) - 0.5f, sy(ay.toFloat()) - 0.5f, sx(bx.toFloat()) + 0.5f, sy(by.toFloat()) + 0.5f), imgPaint)
        }
        text.textAlign = Paint.Align.RIGHT; text.textSize = d(10f); text.typeface = Typeface.DEFAULT
        text.style = Paint.Style.STROKE; text.strokeWidth = d(3f); text.color = 0xFFFFFFFF.toInt()
        c.drawText(ts.attr, width - d(6f), height - d(5f), text)
        text.style = Paint.Style.FILL; text.color = 0xFF222222.toInt()
        c.drawText(ts.attr, width - d(6f), height - d(5f), text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun requestTile(ts: TileSrc, z: Int, x: Int, y: Int, key: String) {
        synchronized(inflight) { if (!inflight.add(key)) return }
        pool.execute {
            var bm: Bitmap? = null
            try {
                val f = java.io.File(tileDir, "$key.img")
                if (f.exists()) bm = BitmapFactory.decodeFile(f.path)
                if (bm == null) {
                    val u = java.net.URL(ts.url.replace("{x}", "$x").replace("{y}", "$y").replace("{z}", "$z"))
                    val con = u.openConnection() as java.net.HttpURLConnection
                    con.connectTimeout = 8000
                    con.readTimeout = 8000
                    con.setRequestProperty("User-Agent", "KalkulatorTKPanen/1.2 (Android)")
                    val data = con.inputStream.use { it.readBytes() }
                    con.disconnect()
                    bm = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bm != null) { f.parentFile?.mkdirs(); f.writeBytes(data) }
                }
            } catch (e: Exception) {
            } catch (e: OutOfMemoryError) {
            }
            synchronized(inflight) { inflight.remove(key) }
            if (bm != null) { tileCache.put(key, bm); postInvalidate() }
        }
    }

    private fun drawGrid(c: Canvas, m: MapInfo) {
        // grid tiap 100 m
        val mpp = m.metersPerPixel
        if (mpp <= 0.0) return
        val step = (100.0 / mpp).toFloat()
        gridPaint.strokeWidth = 1f / s
        var x = 0f
        while (x <= m.w) { c.drawLine(x, 0f, x, m.h.toFloat(), gridPaint); x += step }
        var y = 0f
        while (y <= m.h) { c.drawLine(0f, y, m.w.toFloat(), y, gridPaint); y += step }
    }

    private fun drawPath(c: Canvas, a: FloatArray, p: Paint) {
        if (a.size < 4) return
        val path = Path()
        path.moveTo(sx(a[0]), sy(a[1]))
        var i = 2
        while (i < a.size) { path.lineTo(sx(a[i]), sy(a[i + 1])); i += 2 }
        c.drawPath(path, p)
    }

    private fun dot(c: Canvas, x: Float, y: Float, color: Int, r: Float) {
        fill.color = color; c.drawCircle(x, y, r, fill)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = d(2f); c.drawCircle(x, y, r, stroke)
    }

    private fun drawMe(c: Canvas, m: MapInfo, x: Float, y: Float, acc: Float) {
        val px = sx(x)
        val py = sy(y)
        val mpp = m.metersPerPixel
        if (mpp > 0) {
            val r = (acc / mpp).toFloat() * s
            if (r > d(8f)) {
                fill.color = 0x221E88E5; c.drawCircle(px, py, r, fill)
                stroke.color = 0x661E88E5; stroke.strokeWidth = d(1f); c.drawCircle(px, py, r, stroke)
            }
        }
        heading?.let { h ->
            // kerucut arah hadap (0 derajat = utara peta)
            val len = d(34f)
            val oval = android.graphics.RectF(px - len, py - len, px + len, py + len)
            fill.color = 0x551E88E5
            c.drawArc(oval, h - 90f - 28f, 56f, true, fill)
        }
        dot(c, px, py, 0xFF1E88E5.toInt(), d(8f))
    }

    private fun drawCross(c: Canvas, x: Float, y: Float) {
        stroke.color = 0xFFFF6F00.toInt(); stroke.strokeWidth = d(2.5f)
        c.drawCircle(x, y, d(10f), stroke)
        c.drawLine(x - d(16f), y, x + d(16f), y, stroke)
        c.drawLine(x, y - d(16f), x, y + d(16f), stroke)
    }

    private fun drawMeasure(c: Canvas, m: MapInfo) {
        if (measure.isEmpty()) return
        val xy = measure.map { val (x, y) = m.px(it.first, it.second); Pair(sx(x.toFloat()), sy(y.toFloat())) }
        val path = Path()
        xy.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second) }
        if (mode == Mode.UKUR_LUAS && xy.size >= 3) {
            val closed = Path(path); closed.close()
            c.drawPath(closed, measFill)
            c.drawPath(closed, measLine)
        } else {
            c.drawPath(path, measLine)
        }
        // panjang tiap ruas
        text.textSize = d(11f); text.typeface = Typeface.DEFAULT_BOLD
        for (i in 1 until xy.size) {
            val a = measure[i - 1]; val b = measure[i]
            val dist = Geo.dist(TPoint(a.first, a.second, 0), TPoint(b.first, b.second, 0))
            val mx = (xy[i - 1].first + xy[i].first) / 2
            val my = (xy[i - 1].second + xy[i].second) / 2
            labelBox(c, Geo.meter(dist), mx, my - d(8f))
        }
        xy.forEachIndexed { i, p ->
            dot(c, p.first, p.second, if (i == 0) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt(), d(6f))
        }
    }

    private fun labelBox(c: Canvas, t: String, x: Float, y: Float) {
        text.textSize = d(11f); text.typeface = Typeface.DEFAULT_BOLD; text.style = Paint.Style.FILL
        val w = text.measureText(t) + d(10f)
        fill.color = 0xDD1565C0.toInt()
        c.drawRoundRect(RectF(x - w / 2, y - d(13f), x + w / 2, y + d(4f)), d(4f), d(4f), fill)
        text.color = 0xFFFFFFFF.toInt()
        c.drawText(t, x, y, text)
    }

    private fun drawScaleBar(c: Canvas, m: MapInfo) {
        val mpp = m.metersPerPixel
        if (mpp <= 0.0) return
        val mPerScreenPx = mpp / s
        val target = 90.0 * mPerScreenPx
        val pow10 = 10.0.pow(Math.floor(Math.log10(target)))
        val nice = listOf(1.0, 2.0, 5.0, 10.0).last { it * pow10 <= target }
        val meters = nice * pow10
        val len = (meters / mPerScreenPx).toFloat()
        val x0 = d(12f)
        val y0 = height - d(14f)
        stroke.color = 0xFF000000.toInt(); stroke.strokeWidth = d(3f)
        c.drawLine(x0, y0, x0 + len, y0, stroke)
        c.drawLine(x0, y0 - d(5f), x0, y0 + d(5f), stroke)
        c.drawLine(x0 + len, y0 - d(5f), x0 + len, y0 + d(5f), stroke)
        text.textAlign = Paint.Align.LEFT; text.textSize = d(11f); text.typeface = Typeface.DEFAULT_BOLD
        text.style = Paint.Style.STROKE; text.strokeWidth = d(3f); text.color = 0xFFFFFFFF.toInt()
        val lbl = Geo.meter(meters)
        c.drawText(lbl, x0, y0 - d(9f), text)
        text.style = Paint.Style.FILL; text.color = 0xFF000000.toInt()
        c.drawText(lbl, x0, y0 - d(9f), text)
        text.textAlign = Paint.Align.CENTER
    }

    // ---------- sentuhan ----------

    private fun toLatLon(px: Float, py: Float): Pair<Double, Double>? {
        val m = info ?: return null
        val wx = (px - tx) / s
        val wy = (py - ty) / s
        return m.latLon(wx.toDouble(), wy.toDouble())
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val old = s
            s = (s * det.scaleFactor).coerceIn(minScale(), maxScale())
            val k = s / old
            tx = det.focusX - (det.focusX - tx) * k
            ty = det.focusY - (det.focusY - ty) * k
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val ll = toLatLon(e.x, e.y) ?: return true
            val m = info
            if (m != null && !m.contains(ll.first, ll.second)) return true
            if (mode == Mode.NORMAL) {
                setSelected(ll.first, ll.second)
                listener?.onMapTap(ll.first, ll.second)
            } else {
                addMeasurePoint(ll.first, ll.second)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.NORMAL) return
            val ll = toLatLon(e.x, e.y) ?: return
            val m = info
            if (m != null && !m.contains(ll.first, ll.second)) return
            setSelected(ll.first, ll.second)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            listener?.onMapLongPress(ll.first, ll.second)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val old = s
            s = (s * 2f).coerceIn(minScale(), maxScale())
            val k = s / old
            tx = e.x - (e.x - tx) * k
            ty = e.y - (e.y - ty) * k
            invalidate()
            return true
        }
    })

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = true
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i); lastY = e.getY(i)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (!dragging && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop)) dragging = true
                    if (dragging) { tx += e.x - lastX; ty += e.y - lastY; invalidate() }
                }
                if (e.pointerCount == 1) { lastX = e.x; lastY = e.y }
            }
        }
        return true
    }

    companion object {
        /** Muat gambar peta, diperkecil bila terlalu besar agar aman untuk memori / GPU. */
        fun loadBitmap(path: String, maxSide: Int = 4096): Bitmap? {
            return try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var sample = 1
                while (max(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2
                val o2 = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(path, o2)
            } catch (e: OutOfMemoryError) {
                null
            } catch (e: Exception) {
                null
            }
        }

        val SOURCES = listOf(
            TileSrc("gmap", "Google Maps", "https://mt1.google.com/vt/lyrs=m&x={x}&y={y}&z={z}", "© Google", 20),
            TileSrc("gsat", "Google Satelit", "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}", "© Google", 20),
            TileSrc("ghyb", "Google Hybrid", "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}", "© Google", 20),
            TileSrc("esri", "Esri Satelit", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", "© Esri, Maxar, Earthstar Geographics", 19),
            TileSrc("osm", "OpenStreetMap", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", "© OpenStreetMap contributors", 19),
        )

        /** Bidang 20 x 20 km (1 m/piksel) di sekitar titik acuan sebagai wadah peta online. */
        fun onlineMap(lat: Double, lon: Double): MapInfo = plainMap(lat, lon, 20000, "online", "Online")

        /** Peta datar tanpa gambar (grid 100 m) sebesar 4 x 4 km di sekitar titik acuan. */
        fun plainMap(lat: Double, lon: Double, size: Int = 4000, id: String = "plain", name: String = "Tanpa peta"): MapInfo {
            val mPerPx = 1.0
            val dLat = mPerPx / 110574.0
            val dLon = mPerPx / (111320.0 * Math.cos(Math.toRadians(lat)))
            val half = size / 2
            return MapInfo(
                id, name, size, size, size.toDouble(), size.toDouble(),
                0.0, 0.0, lon - half * dLon, lat - half * dLat,
                size.toDouble(), size.toDouble(), lon + half * dLon, lat + half * dLat,
            )
        }
    }
}
