package dev.brantley.agentbridge

import dev.brantley.agentbridge.a11y.existingFieldText
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldTextTest {
    @Test
    fun hintAndPlaceholderAreEmptyUntilTheUserTypes() {
        assertEquals("", existingFieldText(clear = false, shown = "RCS message", hint = "RCS message", showingHint = true))
        assertEquals("", existingFieldText(clear = false, shown = "RCS message", hint = "RCS message", showingHint = false))
        assertEquals("", existingFieldText(clear = false, shown = "RCS message", hint = null, showingHint = true))
        assertEquals("", existingFieldText(clear = true, shown = "hello", hint = "RCS message", showingHint = false))
        assertEquals("", existingFieldText(clear = false, shown = null, hint = "RCS message", showingHint = false))
        assertEquals("", existingFieldText(clear = false, shown = "", hint = "", showingHint = false))
    }

    @Test
    fun realTextIsKeptWhenClearIsFalse() {
        assertEquals("hello", existingFieldText(clear = false, shown = "hello", hint = "RCS message", showingHint = false))
        assertEquals("secret", existingFieldText(clear = false, shown = "secret", hint = "Password", showingHint = false))
    }
}
