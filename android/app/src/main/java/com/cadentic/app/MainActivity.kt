package com.cadentic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cadentic.app.data.JsonArtifactRepository
import com.cadentic.app.ui.OnboardingApp
import java.io.File

class MainActivity : ComponentActivity() {

    /**
     * The artifacts live in app-private storage: on-device for MVP, no backend or sync
     * (Epic 1 scope). The ViewModel hydrates from them on construction, so a restart never
     * lands the athlete back on an empty step 1.
     */
    private val viewModel: OnboardingViewModel by viewModels {
        viewModelFactory {
            initializer {
                OnboardingViewModel(JsonArtifactRepository(File(filesDir, "artifacts")))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OnboardingApp(viewModel)
        }
    }
}
