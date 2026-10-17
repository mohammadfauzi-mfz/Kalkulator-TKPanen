package id.dsngroup.kalkulatortkpanen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingTest {
    private fun pt(i: Int) = TPoint(0.70 + i * 0.0001, 117.4, 1_700_000_000_000L + i * 60_000L)

    @Test fun jedaMemutusGarisDanJarak() {
        Tracker.start(null, "", "", "A12")
        for (i in 0..4) Tracker.add(pt(i))
        Tracker.pause(null)
        assertTrue(Tracker.paused)
        Tracker.unpause(null)
        assertFalse(Tracker.paused)
        Tracker.add(TPoint(1.70, 117.4, 5000L))      // pindah ~110 km saat dijeda
        Tracker.add(TPoint(1.7002, 117.4, 5001L))
        val p = Tracker.snapshot()
        assertEquals(2, Geo.segments(p).size)
        assertTrue(Geo.length(p) < 100.0)
        Tracker.stop(null)
    }

    @Test fun pesanDenganBuktiTracking() {
        val rec = TrackRecord("t1", 1_700_000_000_000L, 1_700_001_200_000L, "", "", "A12", "",
            listOf(pt(0), pt(1), pt(2)), 0L)
        val e = Entri(1L, "17/10/2026", Input(), rec.id)
        assertFalse(Fmt.pesan(e).contains("Bukti Tracking"))
        val t = Fmt.pesan(e, rec)
        assertTrue(t.contains("*Bukti Tracking Pengambilan %AKP*"))
        assertTrue(t.contains("Blok : A12"))
        assertTrue(t.contains("Durasi : 20 menit"))
        assertEquals("0.746073,117.415342", Fmt.koordinat(0.746073, 117.415342))
    }
}
