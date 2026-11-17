// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: fits a dictation to the text already before the cursor. Dictating into the middle of a
// sentence shouldn't start with a capital ("I went to the Store" style), and needs a space after the
// previous word. Only a mid-sentence flag ever goes to the server; the text itself stays on the phone.
package helium314.keyboard.tx

object DictationCasing {
    /** How much text before the cursor is looked at. */
    const val CONTEXT_CHARS = 200

    private val SENTENCE_END = setOf('.', '?', '!', '…')
    /** Closing quotes / brackets that may follow a sentence end: "Done." or (yes!) */
    private val CLOSERS = setOf('"', '\'', ')', ']', '}', '”', '’', '»')
    private val I_WORDS = setOf("I", "I'm", "I'll", "I've", "I'd", "I’m", "I’ll", "I’ve", "I’d")
    /** Punctuation a dictation may start with that must stick to the previous word (no space before). */
    private val NO_SPACE_BEFORE = setOf('.', ',', '?', '!', ';', ':', '…', ')', ']', '}', '%', '\'', '’', '”')

    /**
     * True when [before] (text before the cursor; null = unknown) ends inside a sentence: its last
     * non-space character is not a sentence end, and there is no line break after it.
     * Empty field, start of field, a new line, or after . ? ! … → a new sentence.
     */
    fun isMidSentence(before: String?): Boolean {
        if (before.isNullOrEmpty()) return false
        var i = before.length - 1
        while (i >= 0 && before[i].isWhitespace()) {
            if (before[i] == '\n' || before[i] == '\r') return false
            i--
        }
        if (i < 0) return false
        // skip closing quotes / brackets after a sentence end
        var j = i
        while (j >= 0 && before[j] in CLOSERS) j--
        if (j >= 0 && before[j] in SENTENCE_END) return false
        return true
    }

    /**
     * [text] as it should be typed after [before]: first letter lower-cased mid-sentence (unless "I"-words,
     * ALL CAPS, or a word the user's lists spell with a capital), and a single space in front when the
     * previous character isn't whitespace and the dictation doesn't start with punctuation.
     */
    fun fit(text: String, before: String?, knownWords: Collection<String>): String {
        var t = text
        if (isMidSentence(before)) t = lowerFirst(t, knownWords)
        if (!before.isNullOrEmpty()) {
            val prev = before.last()
            if (prev.isWhitespace()) {
                t = t.trimStart(' ', '\t') // don't double spaces
            } else {
                val first = t.firstOrNull()
                if (first != null && !first.isWhitespace() && first !in NO_SPACE_BEFORE) t = " $t"
            }
        }
        return t
    }

    /**
     * The server's cleanup, tidied for how he actually dictates (his note, 16 Nov 2026):
     * - a full stop after a word no sentence ends on ("to", "of", "and", "I'm") was a pause, not an
     *   end: the stop goes and the next word loses its capital ([mend]);
     * - the full stops left between phrases become semicolons, his default (17 Nov 2026: "Yes, instead
     *   of stops"), the next word lower-cased ([semicolons]); ? and ! stay;
     * - the full stop at the very end goes; he rarely wants it ([dropFinalFullStop]);
     * - "Ai", "Adhd": known acronyms get their capitals back ([restoreAcronyms]);
     * - ",," and the like collapse to one mark.
     */
    fun polish(text: String, knownWords: Collection<String>): String =
        restoreAcronyms(dropFinalFullStop(semicolons(mend(DOUBLED_MARK.replace(text, "$1"), knownWords), knownWords)), knownWords)

    /** "one phrase. The next" → "one phrase; the next". Abbreviations ("Dr."), initials and numbers keep their stop. */
    fun semicolons(text: String, knownWords: Collection<String>): String {
        var out = text
        var from = 0
        while (true) {
            val m = STOP_BETWEEN_WORDS.find(out, from) ?: break
            val word = m.groupValues[1]
            val gap = m.groupValues[2]
            if (!isPhraseEnd(word)) {
                from = m.range.last + 1
                continue
            }
            val rest = lowerFirst(out.substring(m.range.last + 1), knownWords)
            out = out.substring(0, m.range.first) + word + ";" + gap + rest
            from = m.range.first + word.length + 1 + gap.length
        }
        return out
    }

    private fun isPhraseEnd(rawWord: String): Boolean {
        val w = rawWord.trimEnd { !it.isLetterOrDigit() }.trimStart { !it.isLetterOrDigit() }
        if (w.isEmpty() || w.contains('.')) return false // "U.S.", "e.g."
        if (w.length == 1 || w.all { it.isDigit() }) return false // an initial, "3."
        return w.lowercase() !in ABBREVIATIONS
    }

    private val DOUBLED_MARK = Regex("""([,;:!?])\1+""")

    /** "word." followed by whitespace and more text. */
    private val STOP_BETWEEN_WORDS = Regex("""(\S+)\.(\s+)(?=\S)""")

    /** Words a sentence does not end on: a full stop after one of them is a mis-heard pause. */
    private val NOT_SENTENCE_ENDERS = setOf(
        "of", "to", "in", "on", "at", "for", "with", "from", "into", "onto", "than", "as", "via", "per",
        "toward", "towards", "against", "among", "between", "during", "despite", "except", "until", "upon",
        "the", "a", "an", "and", "or", "but", "nor", "because", "whether", "unless", "although",
        "my", "your", "his", "our", "their", "its",
    )
    private val CONTRACTION_TAILS = listOf("'m", "'re", "'ve", "'ll", "'d", "’m", "’re", "’ve", "’ll", "’d")

    /** Removes a full stop after a word no sentence ends on, lower-casing the word after it. */
    fun mend(text: String, knownWords: Collection<String>): String {
        var out = text
        var from = 0
        while (true) {
            val m = STOP_BETWEEN_WORDS.find(out, from) ?: break
            val word = m.groupValues[1]
            val gap = m.groupValues[2]
            if (!cannotEndSentence(word)) {
                from = m.range.last + 1
                continue
            }
            val rest = lowerFirst(out.substring(m.range.last + 1), knownWords)
            out = out.substring(0, m.range.first) + word + gap + rest
            from = m.range.first + word.length + gap.length
        }
        return out
    }

    private fun cannotEndSentence(rawWord: String): Boolean {
        val w = rawWord.trimStart { !it.isLetter() }
        if (w.isEmpty() || !w.last().isLetter()) return false // ends in ) or ": a real end
        if (w == "I") return true
        val lower = w.lowercase()
        return lower in NOT_SENTENCE_ENDERS || CONTRACTION_TAILS.any { lower.endsWith(it) }
    }

    private val ABBREVIATIONS = setOf("etc", "vs", "dr", "mr", "mrs", "ms", "st", "jr", "sr")

    /** Removes a single full stop at the very end (whitespace after it kept); ? ! … and abbreviations stay. */
    fun dropFinalFullStop(text: String): String {
        val end = text.indexOfLast { !it.isWhitespace() }
        if (end < 0 || text[end] != '.') return text
        if (end > 0 && !text[end - 1].isLetterOrDigit()) return text // "..", ")." and such: leave alone
        val wordStart = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), end) + 1
        val word = text.substring(wordStart, end)
        if (word.length == 1 || word.contains('.') || word.lowercase() in ABBREVIATIONS) return text
        return text.removeRange(end, end + 1)
    }

    /** Short forms the cleanup tends to write as a name ("Ai"); plus any ALL-CAPS entry in the user's own words. */
    private val ACRONYMS = setOf(
        "AI", "ADHD", "PTSD", "OCD", "CBT", "DBT", "EMDR", "LCSW", "NHS", "EU", "UK", "USA", "API", "URL",
        "PDF", "PIN", "VPN", "SMS", "GPS", "LLM", "CEO", "HR", "FAQ", "ASAP", "FYI", "IRS", "LLC", "APK", "TV",
    )
    private val WORD = Regex("""\p{L}+""")

    /** "Ai" → "AI", "Adhd" → "ADHD": only a capitalised-then-lower-case spelling of a known acronym is touched. */
    fun restoreAcronyms(text: String, knownWords: Collection<String>): String {
        val known = ACRONYMS + knownWords.map { it.trim() }
            .filter { it.length >= 2 && it.all { c -> c.isLetter() } && it.all { c -> c.isUpperCase() } }
        return WORD.replace(text) { m ->
            val w = m.value
            if (w.length >= 2 && w[0].isUpperCase() && w.drop(1).all { it.isLowerCase() } && w.uppercase() in known) w.uppercase() else w
        }
    }

    private fun lowerFirst(text: String, knownWords: Collection<String>): String {
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return text
        val end = text.indexOfFirst(start) { it.isWhitespace() }.let { if (it < 0) text.length else it }
        val rawWord = text.substring(start, end)
        val word = rawWord.trimEnd { !it.isLetterOrDigit() }.trimStart { !it.isLetterOrDigit() }
        if (word.isEmpty() || !word[0].isUpperCase()) return text
        if (word in I_WORDS || word.trimEnd('.', ',') == "I") return text
        val letters = word.filter { it.isLetter() }
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return text // acronym: NHS, LCSW
        if (knownWords.any { it.isNotEmpty() && it[0].isUpperCase() && startsWithWord(it, word) }) return text
        val at = text.indexOf(word[0], start)
        return text.substring(0, at) + word[0].lowercaseChar() + text.substring(at + 1)
    }

    /** [entry] (a list line, maybe several words like "New York") begins with [word]. */
    private fun startsWithWord(entry: String, word: String): Boolean {
        val firstWord = entry.trim().split(' ', '\t').first().trimEnd { !it.isLetterOrDigit() }
        return firstWord == word
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (k in from until length) if (predicate(this[k])) return k
        return -1
    }
}
