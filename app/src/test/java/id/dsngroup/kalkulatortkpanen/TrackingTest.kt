package id.dsngroup.kalkulatortkpanen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTest {
    private fun pk(no: Int, jjg: Int) = Pokok(no, 0.70 + no * 0.0001, 117.4, 3.5f, 120.0, jjg, "", "", 1_700_000_000_000L + no * 20_000L)

    private fun sensus(jjg: List<Int>) = TrackRecord(
        "s1", 1_700_000_000_000L, 1_700_001_200_000L, "", "", "H34", "",
        Sensus.titik(jjg.mapIndexed { i, j -> pk(i + 1, j) }), 0L, jjg.mapIndexed { i, j -> pk(i + 1, j) }, "Joko",
    )

    @Test fun akpJanjangDibagiPokok() {
        assertEquals(24.0, Sensus.akp(50, 12), 1e-9)
        assertEquals(0.0, Sensus.akp(0, 0), 1e-9)
        val r = sensus(listOf(1, 0, 2, 0, 1))
        assertEquals(5, r.jumlahPokok)
        assertEquals(4, r.jumlahJjg)
        assertEquals(80.0, r.akpPersen, 1e-9)
        assertTrue(r.isSensus)
    }

    @Test fun pokokBolehBalikDariJson() {
        val r = sensus(listOf(3, 0, 1))
        val back = TrackStore.fromJson(TrackStore.toJson(r))
        assertEquals(3, back.jumlahPokok)
        assertEquals(4, back.jumlahJjg)
        assertEquals("Joko", back.petugas)
        assertEquals(r.pokok[1].lat, back.pokok[1].lat, 1e-12)
        assertEquals(120.0, back.pokok[0].lux, 1e-9)
    }

    @Test fun trackLamaTanpaPokokTetapTerbaca() {
        val lama = TrackRecord("t0", 1L, 2L, "", "", "A1", "", listOf(TPoint(0.7, 117.4, 1L)))
        val back = TrackStore.fromJson(TrackStore.toJson(lama))
        assertFalse(back.isSensus)
        assertEquals(1, back.points.size)
    }

    @Test fun pesanSensus() {
        val rec = sensus(listOf(1, 0, 2, 0, 1))
        val e = Entri(1L, "17/10/2026", Input(akp = 0.8), rec.id)
        assertFalse(Fmt.pesan(e).contains("Bukti Sensus"))
        val t = Fmt.pesan(e, rec)
        assertTrue(t.contains("*Bukti Sensus %AKP per Pokok*"))
        assertTrue(t.contains("Blok : H34"))
        assertTrue(t.contains("Jumlah Pokok : 5"))
        assertTrue(t.contains("Jumlah Janjang : 4"))
        assertTrue(t.contains("%AKP : 80,00%"))
        assertEquals("0.746073,117.415342", Fmt.koordinat(0.746073, 117.415342))
    }

    @Test fun pesanTrackingLamaMasihAda() {
        val rec = TrackRecord("t1", 1_700_000_000_000L, 1_700_001_200_000L, "", "", "A12", "",
            listOf(TPoint(0.70, 117.4, 1L), TPoint(0.7001, 117.4, 2L)), 0L)
        val t = Fmt.pesan(Entri(1L, "17/10/2026", Input(), rec.id), rec)
        assertTrue(t.contains("*Bukti Tracking Pengambilan %AKP*"))
        assertTrue(t.contains("Durasi : 20 menit"))
    }
}
