package com.airachabanda.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.ui.AIRachabandaApp
import com.airachabanda.app.ui.ExternalLinks
import com.airachabanda.app.ui.theme.AIRachabandaTheme

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedText.value = ExternalLinks.sharedText(intent)
        val app = application as AIRachabandaApplication
        setContent {
            val appearance by app.container.preferences.appearance.collectAsState(AppearanceMode.SYSTEM)
            val width = calculateWindowSizeClass(this).widthSizeClass
            val shared by sharedText
            AIRachabandaTheme(appearance) {
                AIRachabandaApp(
                    application = app,
                    expanded = width == WindowWidthSizeClass.Expanded,
                    useRail = width != WindowWidthSizeClass.Compact,
                    sharedText = shared,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText.value = ExternalLinks.sharedText(intent)
    }
}
