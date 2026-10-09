package com.coolcupman.pdfextractor

import java.util.Locale
import kotlin.math.ceil

/** Fehler bei der Bereichsermittlung, Meldung wird direkt angezeigt. */
class RangeException(message: String) : Exception(message)

/** Ergebnis: Seiten sind 1-basiert und inklusive. */
data class PageRange(
    val start: Int,
    val end: Int,
    val total: Int,
    val startInfo: String,
    val endInfo: String,
) {
    val count: Int get() = end - start + 1
}

/** Was ein Eingabefeld beschreibt. */
sealed interface PageSpec {
    data class Page(val number: Int) : PageSpec
    data class Fraction(val numerator: Double, val denominator: Double) : PageSpec
    data class Text(val query: String) : PageSpec
    data object End : PageSpec
}

object PageSpecParser {
    private val number = """(\d+(?:[.,]\d+)?)"""
    private val fraction = Regex("""^$number\s*/\s*$number$""")
    private val percent = Regex("""^$number\s*%$""")
    private val page = Regex("""^(?:s\.?|seite)?\s*(\d+)$""", RegexOption.IGNORE_CASE)
    private val endWords = setOf("ende", "end", "*", "letzte", "last")

    /** Gibt null zurück, wenn das Feld leer ist. */
    fun parse(raw: String): PageSpec? {
        val input = raw.trim()
        if (input.isEmpty()) return null

        // In Anführungszeichen immer als Suchtext, z. B. "56" sucht den Text 56.
        if (input.length >= 2 && input.first() in QUOTES && input.last() in QUOTES) {
            val q = input.substring(1, input.length - 1).trim()
            if (q.isEmpty()) throw RangeException("Leerer Suchtext in Anführungszeichen.")
            return PageSpec.Text(q)
        }
        if (input.lowercase(Locale.ROOT) in endWords) return PageSpec.End

        page.matchEntire(input)?.let { return PageSpec.Page(it.groupValues[1].toInt()) }
        fraction.matchEntire(input)?.let {
            return PageSpec.Fraction(it.groupValues[1].toNum(), it.groupValues[2].toNum())
        }
        percent.matchEntire(input)?.let {
            return PageSpec.Fraction(it.groupValues[1].toNum(), 100.0)
        }
        return PageSpec.Text(input)
    }

    private fun String.toNum() = replace(',', '.').toDouble()
    private val QUOTES = setOf('"', '\'', '„', '“', '”', '»', '«')
}

object TextMatcher {
    private val whitespace = Regex("""\s+""")

    fun normalize(s: String): String =
        s.replace("­", "")
            .replace(' ', ' ')
            .lowercase(Locale.GERMAN)
            .replace(whitespace, " ")
            .trim()

    fun contains(pageText: String, query: String): Boolean =
        normalize(pageText).contains(normalize(query))
}

/**
 * Ermittelt den Seitenbereich aus den Eingaben.
 *
 * @param pageText liefert den Text einer Seite (1-basiert)
 * @param progress (aktuelle Seite, Gesamtseiten) während einer Textsuche
 */
class PageRangeResolver(
    private val total: Int,
    private val pageText: (Int) -> String,
    private val progress: (Int, Int) -> Unit = { _, _ -> },
) {
    init {
        if (total < 1) throw RangeException("Das PDF enthält keine Seiten.")
    }

    fun resolve(
        startInput: String,
        endInput: String,
        fractionInput: String,
        toEnd: Boolean,
        includeEndMatchPage: Boolean,
    ): PageRange {
        val (start, startInfo) = resolveStart(startInput)

        val (end, endInfo) = when {
            toEnd -> total to "bis zum Ende"
            fractionInput.isNotBlank() -> {
                when (val spec = PageSpecParser.parse(fractionInput)) {
                    is PageSpec.Fraction -> fractionPage(spec) to "prozentual ${fractionInput.trim()}"
                    is PageSpec.End -> total to "bis zum Ende"
                    else -> throw RangeException(
                        "Prozent-Feld: bitte z. B. 25/100 oder 25% eingeben."
                    )
                }
            }
            else -> resolveEnd(endInput, start, includeEndMatchPage)
        }

        if (end < start) {
            throw RangeException("Endseite $end liegt vor der Startseite $start.")
        }
        return PageRange(start, end, total, startInfo, endInfo)
    }

    private fun resolveStart(input: String): Pair<Int, String> =
        when (val spec = PageSpecParser.parse(input)) {
            null -> 1 to "ab Seite 1"
            is PageSpec.End -> total to "letzte Seite"
            is PageSpec.Page -> checkPage(spec.number, "Startseite") to "ab Seite ${spec.number}"
            is PageSpec.Fraction -> fractionPage(spec).let { it to "ab ${input.trim()} (Seite $it)" }
            is PageSpec.Text -> {
                val p = findText(spec.query, 1)
                    ?: throw RangeException("Starttext „${spec.query}“ wurde nicht gefunden.")
                p to "Starttext „${spec.query}“ auf Seite $p"
            }
        }

    private fun resolveEnd(input: String, start: Int, includeMatch: Boolean): Pair<Int, String> =
        when (val spec = PageSpecParser.parse(input)) {
            null, is PageSpec.End -> total to "bis zum Ende"
            is PageSpec.Page -> checkPage(spec.number, "Endseite") to "bis Seite ${spec.number}"
            is PageSpec.Fraction -> fractionPage(spec).let { it to "bis ${input.trim()} (Seite $it)" }
            is PageSpec.Text -> {
                // Gesucht wird erst nach der Startseite.
                val p = findText(spec.query, start + 1)
                    ?: throw RangeException(
                        "Endtext „${spec.query}“ wurde nach Seite $start nicht gefunden."
                    )
                val end = if (includeMatch) p else maxOf(start, p - 1)
                end to "Endtext „${spec.query}“ auf Seite $p" +
                    if (includeMatch) "" else " (Seite davor als Ende)"
            }
        }

    private fun checkPage(n: Int, label: String): Int {
        if (n < 1 || n > total) {
            throw RangeException("$label $n existiert nicht (Dokument hat $total Seiten).")
        }
        return n
    }

    /** z. B. 25/100 bei 1000 Seiten -> Seite 250. */
    private fun fractionPage(f: PageSpec.Fraction): Int {
        if (f.denominator <= 0.0) throw RangeException("Nenner muss größer als 0 sein.")
        if (f.numerator < 0.0) throw RangeException("Prozentwert darf nicht negativ sein.")
        val ratio = f.numerator / f.denominator
        if (ratio > 1.0) throw RangeException("Mehr als 100 % ist nicht möglich.")
        // kleine Toleranz gegen Rundungsfehler (z. B. 1000 * 0.29)
        val page = ceil(total * ratio - 1e-9).toInt()
        return page.coerceIn(1, total)
    }

    private fun findText(query: String, from: Int): Int? {
        for (p in from..total) {
            progress(p, total)
            if (TextMatcher.contains(pageText(p), query)) return p
        }
        return null
    }
}
