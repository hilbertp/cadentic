package com.cadentic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cadentic.app.data.HttpMesocycleEngine
import com.cadentic.app.data.JsonArtifactRepository
import com.cadentic.app.ui.OnboardingApp
import java.io.File

class MainActivity : ComponentActivity() {

    /**
     * The artifacts live in app-private storage: on-device for MVP, no sync (Epic 1 scope).
     * The ViewModel hydrates from them on construction, so a restart never lands the athlete
     * back on an empty step 1.
     *
     * The engine is the one thing that *is* remote (Epic 2). Its base URL and shared secret
     * are BuildConfig values, set per build type — debug points at the dev machine. No
     * Anthropic credential appears here or anywhere else in the app: the backend holds the
     * token and the app never talks to a provider.
     */
    private val viewModel: OnboardingViewModel by viewModels {
        viewModelFactory {
            initializer {
                OnboardingViewModel(
                    repository = JsonArtifactRepository(File(filesDir, "artifacts")),
                    engine = HttpMesocycleEngine(
                        baseUrl = BuildConfig.ENGINE_BASE_URL,
                        sharedSecret = BuildConfig.ENGINE_SHARED_SECRET,
                    ),
                )
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
