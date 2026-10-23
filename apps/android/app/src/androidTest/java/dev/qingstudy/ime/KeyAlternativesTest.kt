//! 参考26键的大小写备选完整性，不把现有副符号当作大小写能力。
package dev.qingstudy.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyAlternativesTest {
    @Test fun everyLetterOffersBothCasesAndItsExistingSymbol() {
        for (letter in "qwertyuiopasdfghjklzxcvbnm") {
            val key = letter.toString()
            val choices = KeySymbols.choices(key)
            assertTrue("$key 缺少大写备选：$choices", choices.contains(key.uppercase()))
            assertTrue("$key 缺少小写备选：$choices", choices.contains(key))
            assertTrue("$key 不得丢失原副符号", choices.contains(KeySymbols.primary(key)))
            assertEquals("备选不得重复", choices.distinct(), choices)
        }
    }
}
