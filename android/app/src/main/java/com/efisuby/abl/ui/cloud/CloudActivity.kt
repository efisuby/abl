package com.efisuby.abl.ui.cloud

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.auth.AuthState
import com.efisuby.abl.databinding.ActivityCloudBinding
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import kotlinx.coroutines.launch

/**
 * The cloud section. Signed out it offers Yandex ID sign-in; once signed in it will host the
 * Disk browser (group 7).
 */
class CloudActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudBinding

    private val auth get() = appContainer.authRepository

    // Created in onCreate: YandexAuthOptions needs the base context, which a field initializer lacks.
    private lateinit var signInLauncher: ActivityResultLauncher<YandexAuthLoginOptions>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCloudBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        signInLauncher = registerForActivityResult(
            YandexAuthSdk.create(YandexAuthOptions(this)).contract,
        ) { result -> handle(result) }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.signIn.setOnClickListener { signInLauncher.launch(YandexAuthLoginOptions()) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                auth.state.collect(::render)
            }
        }
    }

    private fun render(state: AuthState) {
        binding.message.setText(
            when (state) {
                AuthState.SIGNED_IN -> R.string.cloud_signed_in
                AuthState.REAUTH_REQUIRED -> R.string.cloud_reauth_prompt
                AuthState.SIGNED_OUT -> R.string.cloud_sign_in_prompt
            },
        )
        binding.signIn.isVisible = state != AuthState.SIGNED_IN
    }

    private fun handle(result: YandexAuthResult) {
        when (result) {
            is YandexAuthResult.Success -> auth.signIn(result.token.value)
            is YandexAuthResult.Cancelled -> toast(R.string.cloud_sign_in_cancelled)
            // The exception text is deliberately not logged or shown: it may carry request data.
            is YandexAuthResult.Failure -> toast(R.string.cloud_sign_in_failed)
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        fun intent(context: Context): Intent = Intent(context, CloudActivity::class.java)
    }
}
