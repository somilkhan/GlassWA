package com.glasswa

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = if (HookEntry.isLoaded) "Hook engine loaded" else "Waiting for LSPosed"
        setContentView(TextView(this).apply {
            text = "GlassWA\n\n$status\n\nTarget: WhatsApp\nMode: Scanner (dev)\nNext: capture WhatsApp view mappings"
            textSize = 18f
            setPadding(48, 72, 48, 48)
        })
    }
}
