package com.efisuby.abl.data.importer

/**
 * Removes speech-synthesis markup that TTS tools (ebook2audiobook) leave in titles and tags:
 * `+` before a stressed Cyrillic vowel and the `[break]`, `[pause]`, `[pause:N]`, `[voice:…]`
 * control tags. Only these known forms are touched, so `C++` or `[Часть 1]` survive.
 */
object TtsMarkup {

    // A voice tag holds a file path and is often cut off by the title length limit.
    private val voiceTag = Regex("""\[voice:[^\]]*(?:]|$)""", RegexOption.IGNORE_CASE)
    private val controlTag = Regex("""\[(?:break|pause)(?::[^\]]*)?]""", RegexOption.IGNORE_CASE)
    private val stress = Regex("""(?<![\d+])\+(?=[аеёиоуыэюяАЕЁИОУЫЭЮЯ])""")
    private val spaces = Regex("""\s+""")
    private val spaceBeforePunctuation = Regex("""\s+(?=[.,;:!?…])""")
    private val leadingSeparators = Regex("""^[\s.,;:]+""")
    private val trailingSeparators = Regex("""[\s,;:]+$""")

    /** The cleaned text, or null when nothing but markup and separators was left. */
    fun strip(text: String?): String? {
        if (text == null) return null
        return text
            .replace(voiceTag, " ")
            .replace(controlTag, " ")
            .replace(stress, "")
            .replace(spaces, " ")
            .replace(spaceBeforePunctuation, "")
            .replace(leadingSeparators, "")
            .replace(trailingSeparators, "")
            .takeIf { it.isNotEmpty() }
    }
}
