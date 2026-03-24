package com.meta.pixelandtexel.scanner.executorch

import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateParser {

    fun parse(rawText: String): String? {
        val clean = rawText.uppercase()
            .replace(Regex("EXP\\.?|BB\\.?|BEST BEFORE|USE BY"), "")
            .replace(Regex("[:;,.\\-/]"), " ")
            .replace(Regex("[^A-Z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), " ")

        val tokens = clean.split(" ")
        val candidates = mutableListOf<String>()

        when (tokens.size) {
            3 -> collectThreeTokenCandidates(tokens, candidates)
            2 -> collectTwoTokenCandidates(tokens, candidates)
        }

        return selectClosestToToday(candidates)
    }

    private fun collectThreeTokenCandidates(tokens: List<String>, candidates: MutableList<String>) {
        val t1 = tokens[0]; val t2 = tokens[1]; val t3 = tokens[2]
        val isAlpha = isMonthName(t1) || isMonthName(t2) || isMonthName(t3)

        if (isAlpha) {
            // Case: Month name is present (e.g., 13 FEB 2026, FEB 13 26, 2026 FEB 13)
            val month = if (isMonthName(t1)) parseMonth(t1) else if (isMonthName(t2)) parseMonth(t2) else parseMonth(t3)
            val digits = tokens.filter { !isMonthName(it) && isDigit(it) }

            if (digits.size == 2) {
                val d1 = digits[0]; val d2 = digits[1]
                // Try (Year=d1, Day=d2) and (Year=d2, Day=d1)
                addIfValid(normalizeYear(d1), month, d2, candidates)
                addIfValid(normalizeYear(d2), month, d1, candidates)
            }
        } else if (tokens.all { isDigit(it) }) {
            // All numeric (e.g., 02 13 26) - Generate all 6 permutations
            val perms = listOf(
                Triple(t1, t2, t3), Triple(t1, t3, t2),
                Triple(t2, t1, t3), Triple(t2, t3, t1),
                Triple(t3, t1, t2), Triple(t3, t2, t1)
            )
            for (p in perms) {
                addIfValid(normalizeYear(p.first), p.second, p.third, candidates) // Y-M-D
            }
        }
    }

    private fun collectTwoTokenCandidates(tokens: List<String>, candidates: MutableList<String>) {
        val t1 = tokens[0]; val t2 = tokens[1]
        val currentYear = Calendar.getInstance().get(Calendar.YEAR).toString()

        if (isMonthName(t1) && isDigit(t2)) {
            addIfValid(normalizeYear(t2), parseMonth(t1), "01", candidates) // FEB 2026
        } else if (isDigit(t1) && isMonthName(t2)) {
            addIfValid(normalizeYear(t1), parseMonth(t2), "01", candidates) // 2026 FEB
        } else if (isDigit(t1) && isDigit(t2)) {
            // Could be M-Y, Y-M, or M-D (injecting current year)
            addIfValid(normalizeYear(t1), t2, "01", candidates)
            addIfValid(normalizeYear(t2), t1, "01", candidates)
            addIfValid(currentYear, t1, t2, candidates)
            addIfValid(currentYear, t2, t1, candidates)
        }
    }

    private fun selectClosestToToday(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val now = System.currentTimeMillis()

        return candidates.distinct().mapNotNull { dateStr ->
            try {
                val parts = dateStr.split("-")
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, parts[0].toInt())
                    set(Calendar.MONTH, parts[1].toInt() - 1)
                    set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                }
                dateStr to abs(cal.timeInMillis - now)
            } catch (e: Exception) { null }
        }.minByOrNull { it.second }?.first
    }

    private fun addIfValid(y: String, m: String, d: String, list: MutableList<String>) {
        val yN = normalizeYear(y).toIntOrNull() ?: 0
        val mN = m.toIntOrNull() ?: 0
        val dN = d.toIntOrNull() ?: 0

        // Basic calendar validation
        if (yN in 2000..2099 && mN in 1..12 && dN in 1..31) {
            // Strict check for days in month (e.g., no Feb 31st)
            val cal = Calendar.getInstance()
            cal.set(Calendar.YEAR, yN)
            cal.set(Calendar.MONTH, mN - 1)
            if (dN <= cal.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                list.add("$yN-${m.padStart(2, '0')}-${d.padStart(2, '0')}")
            }
        }
    }

    private fun normalizeYear(y: String) = if (y.length == 2) "20$y" else y
    private fun isDigit(s: String) = s.all { it.isDigit() }
    private fun isMonthName(s: String) = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC").any { s.startsWith(it) }
    private fun parseMonth(s: String): String {
        val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        return (months.indexOfFirst { s.startsWith(it) } + 1).toString().padStart(2, '0')
    }

    /**
     * Converts a "YYYY-MM-DD" string into a natural spoken format.
     * Example: "2025-02-16" -> "February 16, 2025"
     */
    fun formatForSpeech(dateString: String): String {
        return try {
            val date = LocalDate.parse(dateString)
            val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
            date.format(formatter)
        } catch (e: Exception) {
            // Safe fallback if it fails to parse
            dateString.replace("-", " ")
        }
    }
}