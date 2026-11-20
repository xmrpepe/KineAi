package com.kineai.local

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.kineai.local.ui.AppRoot
import com.kineai.local.ui.theme.KineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KineApp
        setContent { KineTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) { AppRoot(app) } } }
    }
}
