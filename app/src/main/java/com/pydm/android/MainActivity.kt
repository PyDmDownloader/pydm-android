package com.pydm.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)
        setContent { PyDMTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) {
                val m = Regex("https?://\\S+").find(t)
                Shared.sharedUrl.value = m?.value ?: t.trim()
            }
        }
    }
}

object Shared {
    val sharedUrl = mutableStateOf<String?>(null)
    val ytUrl = mutableStateOf("")
}

@Composable
fun PyDMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1F3A5F), onPrimary = Color.White,
            secondary = Color(0xFF2E7D32), tertiary = Color(0xFFEF6C00),
            background = Color(0xFFF4F6F9), surface = Color.White,
            surfaceVariant = Color(0xFFE6EBF2), secondaryContainer = Color(0xFFD5E3F5)
        ),
        content = content
    )
}

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val shared = Shared.sharedUrl.value
    LaunchedEffect(shared) {
        if (shared != null) {
            if (Yt.isYoutubeUrl(shared)) { Shared.ytUrl.value = shared; tab = 1 }
            else {
                try { Manager.addDirect(shared); tab = 0 } catch (_: Exception) {}
            }
            Shared.sharedUrl.value = null
        }
    }
    val tabs = listOf("⬇" to "Downloads", "▶" to "YouTube", "📺" to "Channel", "📊" to "Analysis", "⚙" to "Settings")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Text(icon) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            0 -> DownloadsScreen(m) { u -> Shared.ytUrl.value = u; tab = 1 }
            1 -> YoutubeScreen(m) { tab = 0 }
            2 -> ChannelScreen(m) { tab = 0 }
            3 -> AnalysisScreen(m)
            else -> SettingsScreen(m)
        }
    }
}
