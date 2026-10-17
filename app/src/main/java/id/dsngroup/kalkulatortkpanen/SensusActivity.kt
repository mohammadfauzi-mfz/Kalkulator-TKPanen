package id.dsngroup.kalkulatortkpanen

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

data class PokokAkp(
    val noPokok: Int,
    val latitude: Double,
    val longitude: Double,
    val lux: Float,
    val janjangMatang: Int,
    val keteranganFoto: String? = null
)

class SensusActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var tvPokokHeader: TextView
    private lateinit var tvGpsLuxInfo: TextView
    private lateinit var etJanjang: EditText
    private lateinit var etKeteranganFoto: EditText
    private lateinit var btnNext: Button
    private lateinit var btnFinish: Button

    private var currentPokokNo = 1
    private val listPokok = mutableListOf<PokokAkp>()

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null
    private var currentLux: Float = 0f

    // Koordinat simulasi/real-time GPS (Dapat disesuaikan dengan LocationManager)
    private var currentLat: Double = -0.746570
    private var currentLng: Double = 117.415227

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensus)

        val blok = intent.getStringExtra("BLOK_NAME") ?: "H34"
        supportActionBar?.title = "Sensus AKP"
        supportActionBar?.subtitle = "Blok $blok"

        tvPokokHeader = findViewById(R.id.tvPokokHeader)
        tvGpsLuxInfo = findViewById(R.id.tvGpsLuxInfo)
        etJanjang = findViewById(R.id.etJanjang)
        etKeteranganFoto = findViewById(R.id.etKeteranganFoto)
        btnNext = findViewById(R.id.btnNext)
        btnFinish = findViewById(R.id.btnFinish)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        updatePokokUi()

        btnNext.setOnClickListener {
            saveCurrentPokok()
            currentPokokNo++
            etJanjang.text.clear()
            etKeteranganFoto.text.clear()
            updatePokokUi()
        }

        btnFinish.setOnClickListener {
            showFinishDialog()
        }
    }

    private fun updatePokokUi() {
        tvPokokHeader.text = "Pokok No. $currentPokokNo"
        tvGpsLuxInfo.text = String.format("📍 %.6f, %.6f (±3.3 m)\n☀️ %.1f lux\nKoordinat & lux terus diperbarui. Nilai akhir diambil saat menekan NEXT / FINISH.", currentLat, currentLng, currentLux)
    }

    private fun saveCurrentPokok() {
        val janjang = etJanjang.text.toString().toIntOrNull() ?: 0
        val ket = etKeteranganFoto.text.toString()
        listPokok.add(
            PokokAkp(
                noPokok = currentPokokNo,
                latitude = currentLat,
                longitude = currentLng,
                lux = currentLux,
                janjangMatang = janjang,
                keteranganFoto = ket
            )
        )
    }

    private fun showFinishDialog() {
        val janjangSaatIni = etJanjang.text.toString().toIntOrNull() ?: 0
        AlertDialog.Builder(this)
            .setTitle("Selesaikan sensus? Pokok No. $currentPokokNo sedang tampil.")
            .setCancelable(true)
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Ya, simpan juga yang sedang tampil") { _, _ ->
                saveCurrentPokok()
                finishAndCalculate()
            }
            .setNeutralButton("Ya, jangan simpan yang sedang tampil") { _, _ ->
                finishAndCalculate()
            }
            .show()
    }

    private fun finishAndCalculate() {
        val totalPokok = listPokok.size
        val totalJanjang = listPokok.sumOf { it.janjangMatang }
        val percentAkp = if (totalPokok > 0) (totalJanjang.toDouble() / totalPokok.toDouble()) * 100.0 else 0.0

        val intentResult = Intent().apply {
            putExtra("RESULT_PERCENT_AKP", percentAkp)
            putExtra("TOTAL_POKOK", totalPokok)
            putExtra("TOTAL_JANJANG", totalJanjang)
        }
        setResult(RESULT_OK, intentResult)
        finish()
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]
            updatePokokUi()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
