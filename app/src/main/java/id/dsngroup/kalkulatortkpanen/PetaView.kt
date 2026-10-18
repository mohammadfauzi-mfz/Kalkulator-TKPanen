package id.dsngroup.kalkulatortkpanen

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface PetaHost {
    fun startSensus()
    fun hasLocationPermission(): Boolean
    fun requestLocationPermission()
    fun importMap()
    fun importData()
}

/**
 * Tab Peta: (1) peta offline interaktif (posisi saya, tracking Start/Pause/Stop, ukur jarak/luas, cari koordinat);
 * (2) riwayat jalur tracking (bukti pengambilan %AKP) dengan share & export GIS. Semua data tersimpan di HP.
 */
class PetaView(context: Context, private val host: PetaHost) : LinearLayout(context), MapCanvasView.Listener {

    private val idn = Locale("in", "ID")
    private val prefs = context.getSharedPreferences("tkpanen", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    // panel
    private val tabs = TabLayout(context)
    private val pane = FrameLayout(context)
    private val mapPane = LinearLayout(context)
    private val historyPane = LinearLayout(context)

    // peta
    private val canvas = MapCanvasView(context)
    private val tvInfo = TextView(context)
    private lateinit var btnMap: MaterialButton
    private lateinit var btnTrack: MaterialButton
    private val barNormal = LinearLayout(context)
    private val barMeasure = LinearLayout(context)
    private var curMap: MapInfo? = null
    private var loadGen = 0
    private var pending: (() -> Unit)? = null
    private var lastLoc: Location? = null
    private var lastGps = 0L
    private var centeredOnce = false
    private var selected: Pair<Double, Double>? = null
    private var shown = false
    private var hires: PdfRegion? = null

    // riwayat
    private val historyList = LinearLayout(context)

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { onLoc(location) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    init {
        orientation = VERTICAL
        tabs.addTab(tabs.newTab().setText("Peta"))
        tabs.addTab(tabs.newTab().setText("Riwayat Jalur"))
        tabs.tabMode = TabLayout.MODE_FIXED
        addView(tabs)
        addView(pane, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildMapPane()
        buildHistoryPane()
        pane.addView(mapPane)
        pane.addView(historyPane)
        selectPane(0)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { selectPane(tab.position) }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        canvas.listener = this
    }

    // =====================================================================
    // Umum
    // =====================================================================

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    private fun btn(label: String, filled: Boolean = false, onClick: () -> Unit): MaterialButton {
        val b = if (filled) MaterialButton(context) else MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        )
        b.text = label
        b.textSize = 12f
        b.isAllCaps = false
        b.layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun selectPane(i: Int) {
        mapPane.visibility = if (i == 0) View.VISIBLE else View.GONE
        historyPane.visibility = if (i == 1) View.VISIBLE else View.GONE
        if (tabs.selectedTabPosition != i) tabs.getTabAt(i)?.select()
        if (i == 1) refreshHistory()
    }

    // ---- kompas ----
    private val sensorMgr = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private var azimuth = -1f
    private var lastShownAz = -999f
    private val rotMat = FloatArray(9)
    private val orient = FloatArray(3)
    private val compassListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            try {
                android.hardware.SensorManager.getRotationMatrixFromVector(rotMat, e.values)
                android.hardware.SensorManager.getOrientation(rotMat, orient)
            } catch (x: Exception) { return }
            var az = Math.toDegrees(orient[0].toDouble()).toFloat()
            if (az < 0) az += 360f
            azimuth = if (azimuth < 0) az else {
                var d = az - azimuth
                if (d > 180) d -= 360f else if (d < -180) d += 360f
                ((azimuth + d * 0.25f) + 360f) % 360f
            }
            var diff = Math.abs(azimuth - lastShownAz)
            if (diff > 180) diff = 360f - diff
            if (diff >= 2f) {
                lastShownAz = azimuth
                canvas.setHeading(azimuth)
                updateInfo()
            }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun startCompass() {
        try {
            val s = sensorMgr.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) ?: return
            sensorMgr.registerListener(compassListener, s, android.hardware.SensorManager.SENSOR_DELAY_UI)
        } catch (e: Exception) {
        }
    }

    private fun stopCompass() {
        try { sensorMgr.unregisterListener(compassListener) } catch (e: Exception) {}
        azimuth = -1f; lastShownAz = -999f
        canvas.setHeading(null)
    }

    private fun arah8(deg: Float): String {
        val n = arrayOf("Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut")
        return n[(((deg + 22.5f) % 360f) / 45f).toInt() % 8]
    }

    /** Dipanggil saat tab Peta ditampilkan. */
    fun onShown() {
        shown = true
        startLocation()
        startCompass()
        if (curMap == null) initMap() else reloadOverlays()
        handler.removeCallbacks(tick)
        handler.post(tick)
        refreshHistory()
    }

    fun onHidden() {
        shown = false
        stopLocation()
        stopCompass()
        handler.removeCallbacks(tick)
    }

    /** Muat ulang data (setelah import / tracking selesai). */
    fun refresh() {
        reloadOverlays()
        refreshHistory()
    }

    override fun onDetachedFromWindow() {
        onHidden()
        super.onDetachedFromWindow()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!shown) return
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    // =====================================================================
    // Lokasi
    // =====================================================================

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!host.hasLocationPermission()) { updateInfo(); return }
        stopLocation()
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper())
            } catch (e: Exception) {
            }
        }
        if (lastLoc == null) {
            try {
                val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (l != null && System.currentTimeMillis() - l.time < 10 * 60 * 1000L) onLoc(l)
            } catch (e: Exception) {
            }
        }
    }

    private fun stopLocation() {
        try { lm.removeUpdates(locListener) } catch (e: Exception) {}
    }

    private fun onLoc(l: Location) {
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGps = now
        else if (now - lastGps < 15000L) return          // GPS masih segar: abaikan lokasi jaringan
        lastLoc = l
        val m = curMap
        if (m == null) {
            if (MapStore.all(context).isEmpty()) showMap(MapCanvasView.plainMap(l.latitude, l.longitude))
            else initMap()
            return
        }
        canvas.setMe(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
        if (!centeredOnce && m.contains(l.latitude, l.longitude)) {
            centeredOnce = true
            canvas.centerOn(l.latitude, l.longitude, zoomFor(m, 250.0))
        }
        updateInfo()
    }

    private fun zoomFor(m: MapInfo, meters: Double): Float {
        val w = canvas.width.coerceAtLeast(300)
        return (w * m.metersPerPixel / meters).toFloat()
    }

    // =====================================================================
    // Pane 1: peta
    // =====================================================================

    private fun buildMapPane() {
        mapPane.orientation = VERTICAL
        tvInfo.textSize = 12f
        tvInfo.setTextColor(0xFF263238.toInt())
        tvInfo.setBackgroundColor(0xFFF1F5EE.toInt())
        tvInfo.setPadding(dp(10), dp(4), dp(10), dp(4))
        tvInfo.minLines = 2
        tvInfo.maxLines = 4
        tvInfo.setOnClickListener { copySelected() }

        val head = LinearLayout(context)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(6), dp(2), dp(6), 0)
        btnMap = btn("Pilih peta") { pilihPeta() }
        head.addView(btnMap)
        mapPane.addView(head)
        mapPane.addView(tvInfo)

        val fr = FrameLayout(context)
        fr.addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val zoom = LinearLayout(context)
        zoom.orientation = VERTICAL
        zoom.addView(round("+") { canvas.zoomBy(1.5f) })
        zoom.addView(round("−") { canvas.zoomBy(1f / 1.5f) })
        zoom.addView(round("◎") { ke("me") })
        fr.addView(zoom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL
        ).apply { rightMargin = dp(8) })
        mapPane.addView(fr, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        barNormal.orientation = HORIZONTAL
        barNormal.setPadding(dp(4), dp(4), dp(4), dp(4))
        btnTrack = btn("● Sensus AKP", true) { host.startSensus() }
        btnTrack.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
        barNormal.addView(btnTrack)
        barNormal.addView(btn("📏 Ukur") { pilihUkur() })
        barNormal.addView(btn("⋯ Lainnya") { menuLainnya() })
        mapPane.addView(barNormal)

        barMeasure.orientation = HORIZONTAL
        barMeasure.setPadding(dp(4), dp(4), dp(4), dp(4))
        barMeasure.visibility = View.GONE
        barMeasure.addView(btn("↶ Undo") { canvas.undoMeasure() })
        barMeasure.addView(btn("＋ Posisi Saya") { ukurPosisiSaya() })
        barMeasure.addView(btn("Selesai", true) { selesaiUkur() })
        mapPane.addView(barMeasure)
    }

    private fun round(label: String, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(context)
        b.text = label
        b.textSize = 18f
        b.insetTop = 0
        b.insetBottom = 0
        b.setPadding(0, 0, 0, 0)
        b.minimumWidth = 0
        b.minWidth = 0
        b.backgroundTintList = android.content.res.ColorStateList.valueOf(0xE6FFFFFF.toInt())
        b.setTextColor(0xFF1B5E20.toInt())
        b.layoutParams = LayoutParams(dp(44), dp(44)).apply { bottomMargin = dp(6) }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun initMap() {
        val maps = MapStore.all(context)
        val me = lastLoc
        val saved = prefs.getString("peta_map", "")
        val pick = (if (me != null) maps.firstOrNull { it.contains(me.latitude, me.longitude) } else null)
            ?: maps.firstOrNull { it.id == saved }
            ?: maps.firstOrNull()
        when {
            pick != null -> showMap(pick)
            me != null -> showMap(MapCanvasView.plainMap(me.latitude, me.longitude))
            else -> {
                btnMap.text = "Belum ada peta"
                updateInfo()
            }
        }
    }

    private var onlineSrc: MapCanvasView.TileSrc? = null
    private var onlineRef: Pair<Double, Double>? = null

    private fun showMap(m: MapInfo) {
        curMap = m
        centeredOnce = false
        selected = null
        onlineSrc = null
        canvas.tileSrc = null
        if (m.id != "plain") prefs.edit().putString("peta_map", m.id).apply()
        btnMap.text = if (m.id == "plain") "Tanpa peta (grid 100 m)" else "Peta: ${m.name}"
        val gen = ++loadGen
        if (m.id == "plain") {
            canvas.setMap(m, null)
            setHiRes(null)
            afterMap()
            return
        }
        Thread {
            val b = MapCanvasView.loadBitmap(MapStore.imageFile(context, m.id).path)
            canvas.post {
                if (gen != loadGen) return@post
                if (b == null) toast("Gambar peta tidak dapat dimuat (memori kurang / file rusak)")
                canvas.setMap(m, b)
                setHiRes(m)
                afterMap()
            }
        }.start()
    }

    /** Peta online (Google Maps / Satelit / Hybrid / OSM) berpusat di titik acuan. */
    private fun showOnline(src: MapCanvasView.TileSrc, lat: Double, lon: Double) {
        loadGen++
        val m = MapCanvasView.onlineMap(lat, lon)
        curMap = m
        centeredOnce = true
        selected = null
        onlineSrc = src
        onlineRef = Pair(lat, lon)
        canvas.tileSrc = src
        canvas.setMap(m, null)
        setHiRes(null)
        btnMap.text = "🌐 ${src.name}"
        if (pending == null) canvas.centerOn(lat, lon, zoomFor(m, 300.0))
        afterMap()
    }

    private fun onlineRefPoint(): Pair<Double, Double>? {
        val l = lastLoc
        if (l != null) return Pair(l.latitude, l.longitude)
        selected?.let { return it }
        onlineRef?.let { return it }
        val m = curMap
        if (m != null && m.id != "plain" && m.id != "online") return m.latLon(m.w / 2.0, m.h / 2.0)
        return null
    }

    private fun pilihOnline(src: MapCanvasView.TileSrc) {
        val r = onlineRefPoint()
        if (r == null) { toast("Posisi GPS belum didapat. Tunggu titik biru muncul, atau pilih peta offline dulu."); return }
        showOnline(src, r.first, r.second)
    }

    private fun afterMap() {
        val m = curMap ?: return
        reloadOverlays()
        val l = lastLoc
        if (l != null) {
            canvas.setMe(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 0f)
            if (m.contains(l.latitude, l.longitude) && pending == null) {
                centeredOnce = true
                canvas.centerOn(l.latitude, l.longitude, zoomFor(m, 250.0))
            }
        }
        val p = pending
        pending = null
        p?.invoke()
        updateInfo()
    }

    private fun reloadOverlays() {
        val m = curMap ?: return
        canvas.setHistory(TrackStore.all(context).filter { r ->
            r.points.isNotEmpty() && m.contains(r.points[r.points.size / 2].lat, r.points[r.points.size / 2].lon)
        }.map { it.points })
    }

    private fun pilihPeta() {
        val maps = MapStore.all(context)
        val names = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        for (m in maps) { names.add("🗺 ${m.name} (offline)"); acts.add { showMap(m) } }
        for (src in MapCanvasView.SOURCES) { names.add("🌐 ${src.name} (online)"); acts.add { pilihOnline(src) } }
        names.add("⬜ Tanpa peta (grid 100 m)")
        acts.add {
            val l = lastLoc
            if (l != null) showMap(MapCanvasView.plainMap(l.latitude, l.longitude)) else toast("Posisi GPS belum didapat")
        }
        names.add("📥 Import peta GeoPDF…")
        acts.add { host.importMap() }
        MaterialAlertDialogBuilder(context).setTitle("Pilih peta")
            .setItems(names.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun setHiRes(m: MapInfo?) {
        canvas.hiRes = null
        hires?.close()
        hires = null
        if (m == null || m.id == "plain" || m.id == "online") return
        val pf = MapStore.pdfFile(context, m.id)
        if (!pf.exists()) return
        val pr = PdfRegion(pf, m)
        hires = pr
        canvas.hiRes = { a, b, c, d, e, f -> pr.render(a, b, c, d, e, f) }
    }

    private fun ke(what: String) {
        when (what) {
            "me" -> {
                val l = lastLoc
                val m = curMap
                when {
                    !host.hasLocationPermission() -> host.requestLocationPermission()
                    l == null -> toast("Posisi GPS belum didapat. Pastikan lokasi HP aktif dan di area terbuka.")
                    m != null && m.id == "online" && !m.contains(l.latitude, l.longitude) -> {
                        val src = onlineSrc
                        if (src != null) showOnline(src, l.latitude, l.longitude)
                    }
                    m != null && !m.contains(l.latitude, l.longitude) -> {
                        val other = MapStore.pick(context, l.latitude, l.longitude)
                        if (other != null) {
                            pending = { canvas.centerOn(l.latitude, l.longitude, zoomFor(other, 250.0)) }
                            showMap(other)
                        } else toast("Anda berada di luar area peta ini.")
                    }
                    m != null -> canvas.centerOn(l.latitude, l.longitude, zoomFor(m, 250.0))
                }
            }
        }
    }

    private fun updateInfo() {
        val sb = StringBuilder()
        val l = lastLoc
        when {
            !host.hasLocationPermission() -> sb.append("Izin lokasi belum diberikan (tekan ◎ untuk meminta).")
            l == null -> sb.append("Mencari sinyal GPS…")
            else -> {
                sb.append("Posisi saya: ").append(Fmt.koordinat(l.latitude, l.longitude))
                if (l.hasAccuracy()) {
                    sb.append(" (±").append(Fmt.n(l.accuracy.toDouble(), 0)).append(" m)")
                    sb.append(if (l.accuracy > ACC_OK) " ⚠ kurang akurat" else " ✓")
                }
                sb.append("\nUTM: ").append(Coord.utmText(l.latitude, l.longitude))
                if (azimuth >= 0f) sb.append(" • Arah: ").append(azimuth.toInt()).append("° ").append(arah8(azimuth))
                val m = curMap
                if (m != null && m.id != "plain" && !m.contains(l.latitude, l.longitude)) sb.append("\n⚠ Posisi di luar peta ini")
            }
        }
        val s = selected
        if (canvas.mode == MapCanvasView.Mode.NORMAL) {
            if (s != null) sb.append("\nTitik terpilih: ").append(Fmt.koordinat(s.first, s.second)).append("  UTM ").append(Coord.utmText(s.first, s.second)).append(" (ketuk teks untuk salin)")
        } else {
            sb.append("\n").append(measureText())
        }
        tvInfo.text = sb.toString()
    }

    private fun copySelected() {
        val s = selected ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("koordinat", Fmt.koordinat(s.first, s.second)))
        toast("Koordinat disalin")
    }

    // ---- MapCanvasView.Listener ----

    override fun onMapTap(lat: Double, lon: Double) {
        selected = Pair(lat, lon)
        updateInfo()
    }

    override fun onMapLongPress(lat: Double, lon: Double) {
        selected = Pair(lat, lon)
        updateInfo()
    }

    override fun onMeasureChanged() { updateInfo() }

    // ---- pengukuran ----

    private fun pilihUkur() {
        if (curMap == null) { toast("Peta belum siap"); return }
        MaterialAlertDialogBuilder(context).setTitle("Pengukuran")
            .setItems(arrayOf("📏 Ukur jarak (garis)", "⬛ Ukur luas area (poligon)")) { _, i ->
                canvas.clearMeasure()
                canvas.mode = if (i == 0) MapCanvasView.Mode.UKUR_JARAK else MapCanvasView.Mode.UKUR_LUAS
                barNormal.visibility = View.GONE
                barMeasure.visibility = View.VISIBLE
                toast(if (i == 0) "Ketuk titik-titik di peta untuk mengukur jarak" else "Ketuk sudut-sudut area (min. 3 titik) atau pakai \"+ Posisi Saya\" sambil berjalan di batas area")
                updateInfo()
            }.show()
    }

    private fun measureText(): String {
        val n = canvas.measurePoints.size
        return if (canvas.mode == MapCanvasView.Mode.UKUR_JARAK)
            "Ukur jarak: " + Geo.meter(canvas.measureLength()) + " • $n titik"
        else if (n < 3) "Ukur luas: tambahkan titik ($n/3 minimal)"
        else "Luas: " + Geo.luas(canvas.measureArea()) + " • Keliling: " + Geo.meter(canvas.measurePerimeter()) + " • $n titik"
    }

    private fun ukurPosisiSaya() {
        val l = lastLoc
        if (l == null) { toast("Posisi GPS belum didapat"); return }
        canvas.addMeasurePoint(l.latitude, l.longitude)
    }

    private fun selesaiUkur() {
        canvas.mode = MapCanvasView.Mode.NORMAL
        canvas.clearMeasure()
        barMeasure.visibility = View.GONE
        barNormal.visibility = View.VISIBLE
        updateInfo()
    }

    // ---- menu lainnya ----

    private fun menuLainnya() {
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        items.add("🔎 Cari koordinat…"); acts.add { cariKoordinat() }
        val s = selected
        if (s != null) {
            items.add("🧭 Navigasi ke titik terpilih"); acts.add { navigasi(s.first, s.second) }
            items.add("📤 Share titik terpilih (WA)"); acts.add {
                Fmt.bagikan(context, "Koordinat: ${Fmt.koordinat(s.first, s.second)}\n${Fmt.mapsUrl(s.first, s.second)}")
            }
            items.add("Lepas titik terpilih"); acts.add { selected = null; canvas.clearSelected(); updateInfo() }
        }
        items.add(if (canvas.showHistory) "Sembunyikan riwayat jalur" else "Tampilkan riwayat jalur")
        acts.add { canvas.showHistory = !canvas.showHistory }
        val cs = onlineSrc
        if (cs != null) {
            items.add("🎯 Pusatkan peta online di sini"); acts.add {
                val r = selected ?: lastLoc?.let { Pair(it.latitude, it.longitude) }
                if (r != null) showOnline(cs, r.first, r.second) else toast("Pilih titik atau tunggu GPS")
            }
        }
        items.add("🗺 Pilih peta…"); acts.add { pilihPeta() }
        items.add("📥 Import peta GeoPDF…"); acts.add { host.importMap() }
        items.add("📥 Import data jalur / file peta…"); acts.add { host.importData() }
        MaterialAlertDialogBuilder(context).setTitle("Menu peta")
            .setItems(items.toTypedArray()) { _, i -> acts[i]() }.show()
    }

    private fun cariKoordinat() {
        val (til, et) = inputField(context, "Koordinat (desimal, DMS, atau UTM)", InputType.TYPE_CLASS_TEXT)
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(til)
        box.addView(TextView(context).apply {
            text = "Contoh:\n• -0.746073,117.415342\n• 0°44'45.8\"LU 117°24'55.2\"BT\n• 50N 512345 82345 (UTM)"
            textSize = 12f
        })
        MaterialAlertDialogBuilder(context).setTitle("Cari koordinat").setView(box)
            .setPositiveButton("Cari") { _, _ ->
                val r = Coord.parse(et.text.toString())
                if (r == null) toast("Format tidak dikenali. Gunakan desimal, DMS, atau UTM.")
                else { toast("Terbaca sebagai ${r.kind}"); goTo(r.lat, r.lon) }
            }.setNegativeButton("Batal", null).show()
    }

    private fun goTo(lat: Double, lon: Double, zoomMeters: Double = 150.0) {
        selectPane(0)
        val cur = curMap
        val src = onlineSrc
        if (cur != null && cur.id == "online" && src != null) {
            if (!cur.contains(lat, lon)) showOnline(src, lat, lon)
            selected = Pair(lat, lon)
            canvas.setSelected(lat, lon)
            canvas.centerOn(lat, lon, zoomFor(curMap ?: cur, zoomMeters))
            updateInfo()
            return
        }
        val target = if (cur != null && cur.contains(lat, lon)) cur else MapStore.pick(context, lat, lon)
        val act = {
            selected = Pair(lat, lon)
            canvas.setSelected(lat, lon)
            val m = curMap
            if (m != null) canvas.centerOn(lat, lon, zoomFor(m, zoomMeters))
            updateInfo()
        }
        when {
            target == null && cur != null && cur.id != "plain" -> {
                pending = act
                showMap(MapCanvasView.plainMap(lat, lon))
            }
            target == null -> { pending = act; showMap(MapCanvasView.plainMap(lat, lon)) }
            cur == null || cur.id != target.id -> { pending = act; showMap(target) }
            else -> act()
        }
    }

    private fun navigasi(lat: Double, lon: Double) {
        val nav = Uri.parse("google.navigation:q=${Fmt.koordinat(lat, lon)}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, nav))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Fmt.mapsUrl(lat, lon))))
            } catch (e2: Exception) {
                toast("Tidak ada aplikasi peta untuk navigasi")
            }
        }
    }

    // =====================================================================
    // Pane 2: riwayat jalur tracking
    // =====================================================================

    private fun buildHistoryPane() {
        historyPane.orientation = VERTICAL
        val top = LinearLayout(context)
        top.orientation = HORIZONTAL
        top.setPadding(dp(12), dp(10), dp(12), dp(2))
        top.addView(btn("Import") { host.importData() })
        top.addView(btn("Share Database") { shareDatabase() })
        top.addView(btn("Share File Peta") { shareFilePeta() })
        historyPane.addView(top)
        val top2 = LinearLayout(context)
        top2.orientation = HORIZONTAL
        top2.setPadding(dp(12), dp(2), dp(12), dp(2))
        top2.addView(btn("Export GIS (KML, SHP, GPX, GeoJSON)") { exportGis(TrackStore.all(context), "semua tracking") })
        historyPane.addView(top2)
        historyPane.addView(TextView(context).apply {
            text = "Jalur tracking dan peta tersimpan di HP dan tidak ikut diupload. Share Database = cadangan jalur (file .json). Share File Peta = peta offline untuk HP lain. Import menerima keduanya."
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(16), dp(2), dp(16), dp(6))
        })
        historyList.orientation = VERTICAL
        historyList.setPadding(dp(16), dp(4), dp(16), dp(32))
        historyPane.addView(ScrollView(context).apply {
            addView(historyList)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
    }

    private fun exportDir() = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

    private fun shareDatabase() {
        val t = TrackStore.all(context)
        if (t.isEmpty()) { toast("Belum ada data tracking"); return }
        try {
            val f = File(exportDir(), "tkpanen_database_jalur_${stamp()}.json")
            f.writeText(TrackStore.exportJson(t))
            Share.file(context, f, "application/json", "Bagikan database jalur")
        } catch (e: Exception) {
            toast("Gagal membagikan: ${e.message}")
        }
    }

    private fun shareFilePeta() {
        val maps = MapStore.all(context)
        if (maps.isEmpty()) { toast("Belum ada peta offline"); return }
        fun kirim(m: MapInfo) {
            toast("Menyiapkan file peta…")
            val app = context
            Thread {
                val f = File(exportDir(), "peta_${m.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.ckpmap.zip")
                val ok = try { MapStore.exportZip(app, m.id, f) } catch (e: Exception) { false }
                post {
                    if (ok) Share.file(app, f, "application/zip", "Bagikan file peta")
                    else toast("Gagal menyiapkan file peta")
                }
            }.start()
        }
        if (maps.size == 1) kirim(maps[0])
        else MaterialAlertDialogBuilder(context).setTitle("Bagikan peta mana?")
            .setItems(maps.map { it.name }.toTypedArray()) { _, i -> kirim(maps[i]) }.show()
    }

    private fun judul(r: TrackRecord) =
        (if (r.isSensus) "Sensus AKP " else "Tracking ") + (if (r.blok.isNotBlank()) "Blok " + r.blok else "").trim()

    private fun ringkas(r: TrackRecord): String {
        val tgl = SimpleDateFormat("dd MMM yyyy HH:mm", idn).format(Date(r.startedAt))
        val mnt = r.minutes
        if (r.isSensus) return "$tgl" + (if (r.petugas.isNotBlank()) " • ${r.petugas}" else "") +
            "\n${r.jumlahPokok} pokok • ${r.jumlahJjg} janjang • %AKP ${Fmt.n(r.akpPersen, 2)}%"
        return "$tgl\nJarak ${Geo.km(r.distance)} • $mnt menit • ${r.points.size} titik"
    }

    fun refreshHistory() {
        historyList.removeAllViews()
        val items = TrackStore.all(context)
        if (items.isEmpty()) {
            historyList.addView(TextView(context).apply {
                text = "Belum ada riwayat sensus.\nTekan tombol merah \"Sensus AKP\" di tab Peta atau \"Mulai Sensus AKP\" di tab Kalkulator."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }
        for (r in items) historyList.addView(kartu(r))
    }

    private fun kartu(r: TrackRecord): MaterialCardView {
        val card = MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(0xFFE8F5E9.toInt())
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        col.addView(TextView(context).apply {
            text = judul(r); textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(0xFF1B5E20.toInt())
        })
        col.addView(TextView(context).apply { text = ringkas(r); textSize = 13f; setPadding(0, dp(4), 0, dp(6)) })
        val iv = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFDDE8DD.toInt())
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
            tag = r.id
        }
        col.addView(iv)
        Thread {
            val bmp = try { TrackRender.render(context, MapStore.get(context, r.mapId), r.points, 640, 300, null, r.isSensus) } catch (e: Throwable) { null }
            iv.post { if (iv.tag == r.id && bmp != null) iv.setImageBitmap(bmp) }
        }.start()
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        row.addView(btn("Lihat") { lihat(r) })
        row.addView(btn("Di Peta") { diPeta(r) })
        row.addView(btn("Share") { exportOne(r) })
        row.addView(btn("Hapus") { hapus(r) })
        col.addView(row)
        card.addView(col)
        return card
    }

    private fun diPeta(r: TrackRecord) {
        if (r.points.isEmpty()) return
        val mid = r.points[r.points.size / 2]
        val cur = curMap
        val target = MapStore.get(context, r.mapId) ?: MapStore.pick(context, mid.lat, mid.lon)
        selectPane(0)
        val act = { canvas.fitBounds(r.points) }
        val use = target ?: MapCanvasView.plainMap(mid.lat, mid.lon)
        if (cur == null || cur.id != use.id) { pending = act; showMap(use) } else act()
    }

    private fun lihat(r: TrackRecord) {
        val iv = ImageView(context).apply {
            adjustViewBounds = true
            setBackgroundColor(0xFFDDE8DD.toInt())
            minimumHeight = dp(260)
        }
        val luas = if (!r.isSensus && r.points.size >= 3) "\nPerkiraan luas jika jalur ditutup: " + Geo.luas(Geo.areaM2(r.points)) else ""
        val d = MaterialAlertDialogBuilder(context)
            .setTitle(judul(r))
            .setMessage(ringkas(r) + luas)
            .setView(iv)
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Share Gambar", null)
            .create()
        d.show()
        var shownBmp: Bitmap? = null
        Thread {
            val bmp = try {
                TrackRender.render(context, MapStore.get(context, r.mapId), r.points, 1280, 900, "${judul(r)} • " + (if (r.isSensus) "${r.jumlahPokok} pokok • %AKP ${Fmt.n(r.akpPersen, 2)}%" else Geo.km(r.distance)), r.isSensus)
            } catch (e: Throwable) { null }
            iv.post { shownBmp = bmp; if (bmp != null) iv.setImageBitmap(bmp) }
        }.start()
        d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            val b = shownBmp ?: return@setOnClickListener
            try {
                val f = File(exportDir(), "tracking_${r.blok.ifBlank { "blok" }}_${r.startedAt}.jpg")
                FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                Share.file(context, f, "image/jpeg", "Bagikan gambar tracking")
            } catch (e: Exception) {
                toast("Gagal membagikan: ${e.message}")
            }
        }
    }

    private fun exportOne(r: TrackRecord) {
        try {
            val f = File(exportDir(), "tracking_${r.blok.ifBlank { "blok" }}_${r.startedAt}.json")
            f.writeText(TrackStore.exportJson(listOf(r)))
            Share.file(context, f, "application/json", "Bagikan data tracking")
        } catch (e: Exception) {
            toast("Gagal membagikan: ${e.message}")
        }
    }

    private fun hapus(r: TrackRecord) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Hapus tracking?")
            .setMessage("Tracking ${judul(r)} (${ringkas(r).lines().first()}) akan dihapus dari HP. Pastikan sudah dicadangkan jika masih diperlukan.")
            .setPositiveButton("Hapus") { _, _ -> TrackStore.remove(context, r.id); refreshHistory(); reloadOverlays() }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Export ke format GIS (QGIS / ArcGIS / Google Earth / Avenza). */
    private fun exportGis(tracks: List<TrackRecord>, label: String) {
        val n = tracks.count { it.points.size >= 2 }
        if (n == 0) { toast("Belum ada tracking untuk diexport"); return }
        val fm = Gis.Format.values()
        MaterialAlertDialogBuilder(context)
            .setTitle("Export $label ($n tracking)")
            .setItems(fm.map { it.label }.toTypedArray()) { _, i ->
                val format = fm[i]
                val app = context
                Thread {
                    val f = try { Gis.export(format, tracks, exportDir()) } catch (e: Exception) { null }
                    post {
                        if (f != null) Share.file(app, f, format.mime, "Bagikan ${format.ext.uppercase()}")
                        else toast("Gagal membuat file export")
                    }
                }.start()
            }
            .setNegativeButton("Batal", null).show()
    }

    companion object {
        /** Batas akurasi GPS (meter) yang dianggap baik. */
        const val ACC_OK = 15.0
    }
}
