package com.github.libretube.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.ActivityNointernetBinding
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.ui.base.BaseActivity

class NoInternetActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityNointernetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // manually apply additional padding for edge-to-edge compatibility
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.getBooleanExtra(IntentData.maximizePlayer, false)) {
            NavigationHelper.openAudioPlayerFragment(this, offlinePlayer = true)
        }
    }
}
