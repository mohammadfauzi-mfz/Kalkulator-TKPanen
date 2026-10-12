package id.dsngroup.kalkulatortkpanen

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorTest {
    @Test fun samaDenganExcel() {
        val h = Calculator.hitung(Input())
        assertEquals(3600.0, h.janjang, 1e-6)
        assertEquals(50.4, h.tonase, 1e-9)
        assertEquals(42.857142857, h.kebPanenLuasan, 1e-6)
        assertEquals(45.818181818, h.kebPanenProduksi, 1e-6)
        assertEquals(11.0, h.kebGandengLuasan, 1e-6)
        assertEquals(23.8, h.kebGandengProduksi, 1e-6)
    }
}
