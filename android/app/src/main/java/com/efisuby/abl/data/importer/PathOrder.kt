package com.efisuby.abl.data.importer

/**
 * Chapter order for a multi-file book: paths are compared folder by folder, and inside each
 * name digit runs compare as numbers, so `2.mp3` comes before `10.mp3` and `part-1/…` before
 * `part-2/…`. Letters compare case-insensitively.
 */
object PathOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val left = a.split('/')
        val right = b.split('/')
        for (i in 0 until minOf(left.size, right.size)) {
            val result = compareNatural(left[i], right[i])
            if (result != 0) return result
        }
        return left.size.compareTo(right.size)
    }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val numberA = a.substring(i, endA).trimStart('0')
                val numberB = b.substring(j, endB).trimStart('0')
                val byValue = numberA.length.compareTo(numberB.length)
                    .takeIf { it != 0 } ?: numberA.compareTo(numberB)
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byRest = (a.length - i).compareTo(b.length - j)
        // Fully equal ignoring case and leading zeros: fall back to a strict order.
        return if (byRest != 0) byRest else a.compareTo(b)
    }

    private fun digitRunEnd(s: String, start: Int): Int {
        var end = start
        while (end < s.length && s[end].isDigit()) end++
        return end
    }
}
