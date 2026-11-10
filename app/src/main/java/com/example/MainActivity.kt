package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.shell.ConversationShellRoute
import com.example.ui.shell.ConversationShellViewModel
import com.example.ui.theme.OmniVoiceRebuildTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ConversationShellViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
    }
}
