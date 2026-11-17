// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: how a dictation is tidied before it is typed in (his note of 16 Nov 2026).
package helium314.keyboard.tx

import kotlin.test.Test
import kotlin.test.assertEquals

class DictationCasingTest {
    private val none = emptyList<String>()

    @Test fun finalFullStopGoes() {
        assertEquals("Send it to Erin ", DictationCasing.dropFinalFullStop("Send it to Erin. "))
        assertEquals("Send it to Erin", DictationCasing.dropFinalFullStop("Send it to Erin."))
    }

    @Test fun questionsEllipsesAndAbbreviationsKeepTheirEnd() {
        assertEquals("Is it done? ", DictationCasing.dropFinalFullStop("Is it done? "))
        assertEquals("Well... ", DictationCasing.dropFinalFullStop("Well... "))
        assertEquals("Ask Dr. ", DictationCasing.dropFinalFullStop("Ask Dr. "))
        assertEquals("Apples, pears etc. ", DictationCasing.dropFinalFullStop("Apples, pears etc. "))
        assertEquals("", DictationCasing.dropFinalFullStop(""))
    }

    @Test fun stopAfterPrepositionIsAPause() {
        assertEquals("I want to go to the store ",
            DictationCasing.polish("I want to. Go to. The store. ", none))
        assertEquals("Periods after prepositions and the words that follow them ",
            DictationCasing.polish("Periods after prepositions and. The words that follow them. ", none))
    }

    @Test fun realSentenceEndsBecomeSemicolons() {
        assertEquals("I know that; the rest can wait ",
            DictationCasing.polish("I know that. The rest can wait. ", none))
        assertEquals("That is what it's about; the rest can wait ",
            DictationCasing.polish("That is what it's about. The rest can wait. ", none))
        assertEquals("Done (for now); next week ", DictationCasing.polish("Done (for now). Next week. ", none))
        assertEquals("Ask Erin; I think she knows ", DictationCasing.polish("Ask Erin. I think she knows. ", none))
    }

    @Test fun questionsAbbreviationsAndNumbersKeepTheirMark() {
        assertEquals("Is it done? Then send it ", DictationCasing.polish("Is it done? Then send it. ", none))
        assertEquals("Ask Dr. Smith about the U.S. Rules ", DictationCasing.polish("Ask Dr. Smith about the U.S. Rules. ", none))
        assertEquals("Chapter 3. Then the end ", DictationCasing.polish("Chapter 3. Then the end. ", none))
    }

    @Test fun contractionsAndINeverEndASentence() {
        assertEquals("I'm going to the shop and I need milk ",
            DictationCasing.polish("I'm. Going to the shop and I. Need milk. ", none))
    }

    @Test fun nextWordKeepsItsCapitalWhenItIsAName() {
        assertEquals("Talk to Erin about it ", DictationCasing.polish("Talk to. Erin about it. ", listOf("Erin")))
        assertEquals("I want to I think ", DictationCasing.polish("I want to. I think. ", none))
    }

    @Test fun acronymsGetTheirCapitalsBack() {
        assertEquals("This dictation should not have cleaned AI ",
            DictationCasing.polish("This dictation should not have cleaned Ai. ", none))
        assertEquals("ADHD and Ear", DictationCasing.restoreAcronyms("Adhd and Ear", none))
        assertEquals("ADHD and EAR", DictationCasing.restoreAcronyms("Adhd and Ear", listOf("EAR")))
        assertEquals("ai and AI", DictationCasing.restoreAcronyms("ai and AI", none))
    }

    @Test fun doubledMarksCollapse() {
        assertEquals("Firstly, when I go back ", DictationCasing.polish("Firstly,, when I go back. ", none))
    }

    @Test fun fitStillLowersMidSentence() {
        assertEquals(" and then home", DictationCasing.fit(DictationCasing.polish("And then home.", none), "I went to the shop", none))
    }
}
