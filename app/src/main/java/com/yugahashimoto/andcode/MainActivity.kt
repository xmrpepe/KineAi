package com.yugahashimoto.andcode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yugahashimoto.andcode.core.locale.AppLanguage
import com.yugahashimoto.andcode.core.notification.RuntimeNotificationHelper
import com.yugahashimoto.andcode.ui.AndCodeApp
import com.yugahashimoto.andcode.ui.ChatDeepLink

class MainActivity : ComponentActivity() {
    private var chatDeepLink by mutableStateOf<ChatDeepLink?>(null)
    private var deepLinkToken = 0L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            AndCodeApp(chatDeepLink = chatDeepLink, onChatDeepLinkConsumed = { chatDeepLink = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent ?: return
        val sessionId = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        deepLinkToken += 1
        chatDeepLink = ChatDeepLink(sessionId = sessionId, runtimeId = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID), token = deepLinkToken)
        intent.removeExtra(RuntimeNotificationHelper.EXTRA_TARGET_SESSION_ID)
        intent.removeExtra(RuntimeNotificationHelper.EXTRA_RUNTIME_ID)
    }
}
