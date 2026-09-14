package com.meshlink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.meshlink.app.presentation.viewmodels.MeshViewModel
import com.meshlink.app.ui.screens.MainScreen
import com.meshlink.app.ui.theme.MeshLinkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels {
        val app = application as MeshLinkApp
        MeshViewModel.provideFactory(app.meshRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeshLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
