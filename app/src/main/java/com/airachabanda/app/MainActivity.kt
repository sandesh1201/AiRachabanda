package com.airachabanda.app

import android.content.Intent
import android.net.Uri
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
import com.airachabanda.app.documents.AttachmentShares
import com.airachabanda.app.core.model.AppearanceMode
import com.airachabanda.app.ui.AIRachabandaApp
import com.airachabanda.app.ui.ExternalLinks
import com.airachabanda.app.ui.theme.AIRachabandaTheme

class MainActivity : ComponentActivity() {
    private val sharedText = mutableStateOf<String?>(null)
    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptShare(intent)
        val app = application as AIRachabandaApplication
        setContent {
            val appearance by app.container.preferences.appearance.collectAsState(AppearanceMode.SYSTEM)
            val width = calculateWindowSizeClass(this).widthSizeClass
            val shared by sharedText
            val streams by sharedUris
            AIRachabandaTheme(appearance) {
                AIRachabandaApp(
                    application = app,
                    expanded = width == WindowWidthSizeClass.Expanded,
                    useRail = width != WindowWidthSizeClass.Compact,
                    sharedText = shared,
                    sharedUris = streams,
                    onSharedUrisConsumed = { sharedUris.value = emptyList() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    private fun acceptShare(intent: Intent?) {
        val streams = AttachmentShares.streams(intent)
        sharedUris.value = streams
        sharedText.value = if (streams.isEmpty()) ExternalLinks.sharedText(intent) else null
    }
}
