package com.glasswa.core

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "GlassWA\n\nHook engine: installed\n\nTarget: WhatsApp\nMode: Conversation Glass\nBuild: cohesive conversation-page redesign"
            textSize = 18f
            setPadding(48, 72, 48, 48)
        })
    }
}
