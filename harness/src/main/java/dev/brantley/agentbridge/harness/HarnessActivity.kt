package dev.brantley.agentbridge.harness

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Sideload fixture for the emulator script. The product app is on its own denylist,
 * so a password field has to live in a different package.
 */
class HarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Bridge Harness"
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        layout.addView(
            TextView(this).apply {
                text = "Harness"
            },
        )
        layout.addView(
            EditText(this).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                contentDescription = "password"
            },
        )
        layout.addView(
            Button(this).apply {
                text = "Send"
                setOnClickListener { }
            },
        )
        setContentView(layout)
    }
}
