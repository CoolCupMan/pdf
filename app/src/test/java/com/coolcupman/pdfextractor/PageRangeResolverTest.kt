package com.coolcupman.pdfextractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeResolverTest {

    // 1000 Seiten, Seite 56 enthält "Kapitel 2", Seite 98 "Kapitel 3"
    private val texts = (1..1000).associateWith { "Seite $it Inhalt" }.toMutableMap().apply {
        this[56] = "Einleitung\nKAPITEL   2 – Grundlagen"
        this[98] = "Kapitel 3"
    }
    private val resolver = PageRangeResolver(1000, { texts.getValue(it) })

    private fun resolve(
        start: String = "",
        end: String = "",
        fraction: String = "",
        toEnd: Boolean = false,
        include: Boolean = true,
    ) = resolver.resolve(start, end, fraction, toEnd, include)

    @Test fun startPageToEnd() {
        val r = resolve(start = "56")
        assertEquals(56, r.start); assertEquals(1000, r.end)
    }

    @Test fun pageToPage() {
        val r = resolve(start = "56", end = "98")
        assertEquals(56 to 98, r.start to r.end)
    }

    @Test fun textToText() {
        val r = resolve(start = "kapitel 2", end = "Kapitel 3")
        assertEquals(56 to 98, r.start to r.end)
    }

    @Test fun textEndExcludingMatchPage() {
        val r = resolve(start = "Kapitel 2", end = "Kapitel 3", include = false)
        assertEquals(56 to 97, r.start to r.end)
    }

    @Test fun fractionFields() {
        assertEquals(250, resolve(fraction = "25/100").end)
        assertEquals(500, resolve(fraction = "50/100").end)
        assertEquals(750, resolve(fraction = "75/100").end)
        assertEquals(1000, resolve(fraction = "100/100").end)
        assertEquals(125, resolve(fraction = "25/200").end)
        assertEquals(250, resolve(fraction = "25%").end)
        assertEquals(250, resolve(end = "25/100").end)
    }

    @Test fun fractionOverridesEndField() {
        assertEquals(250, resolve(start = "56", end = "98", fraction = "25/100").end)
    }

    @Test fun toEndOverridesEverything() {
        assertEquals(1000, resolve(start = "56", end = "98", fraction = "25/100", toEnd = true).end)
    }

    @Test fun quotedNumberIsText() {
        val r = PageRangeResolver(3, { if (it == 2) "Nummer 56" else "x" })
            .resolve("\"56\"", "", "", false, true)
        assertEquals(2, r.start)
    }

    @Test fun errors() {
        assertFails { resolve(start = "1001") }
        assertFails { resolve(start = "98", end = "56") }
        assertFails { resolve(start = "gibt es nicht") }
        assertFails { resolve(fraction = "150/100") }
        assertFails { resolve(fraction = "abc") }
        assertFails { resolve(start = "900", fraction = "25/100") }
    }

    private fun assertFails(block: () -> Unit) {
        val failed = try { block(); false } catch (e: RangeException) { true }
        assertTrue("RangeException erwartet", failed)
    }
}
