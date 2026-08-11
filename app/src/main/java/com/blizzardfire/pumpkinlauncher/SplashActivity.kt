package com.blizzardfire.pumpkinlauncher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        findViewById<android.widget.Button>(R.id.websiteButton).setOnClickListener {
            openUrl("https://pumpkinmc.org")
        }

        findViewById<android.widget.Button>(R.id.githubButton).setOnClickListener {
            openUrl("https://github.com/Pumpkin-MC/Pumpkin")
        }

        findViewById<android.widget.Button>(R.id.discordButton).setOnClickListener {
            openUrl("https://discord.com/invite/wT8XjrjKkf")
        }

        findViewById<android.widget.Button>(R.id.continueButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}