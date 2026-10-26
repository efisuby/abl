package com.efisuby.abl.ui.cloud

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.efisuby.abl.R
import com.efisuby.abl.appContainer
import com.efisuby.abl.data.auth.AuthState
import com.efisuby.abl.data.disk.DiskApi
import com.efisuby.abl.databinding.ActivityCloudBinding
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.YandexAuthSdk
import kotlinx.coroutines.launch

/**
 * The cloud section. Signed out it offers Yandex ID sign-in; signed in it browses the books
 * folder on Disk one level at a time.
 */
class CloudActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudBinding

    private val auth get() = appContainer.authRepository

    private val viewModel: CloudBrowserViewModel by viewModels {
        viewModelFactory {
            initializer { CloudBrowserViewModel(appContainer.diskSource, DiskApi.BOOKS_ROOT) }
        }
    }

    // Created in onCreate: YandexAuthOptions needs the base context, which a field initializer lacks.
    private lateinit var signInLauncher: ActivityResultLauncher<YandexAuthLoginOptions>

    private val adapter = CloudAdapter { folder -> viewModel.open(folder) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCloudBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        signInLauncher = registerForActivityResult(
            YandexAuthSdk.create(YandexAuthOptions(this)).contract,
        ) { result -> handle(result) }

        binding.list.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { goUp() }
        binding.signIn.setOnClickListener { signInLauncher.launch(YandexAuthLoginOptions()) }
        binding.retry.setOnClickListener { viewModel.retry() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goUp()
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { auth.state.collect(::renderAuth) }
                launch { viewModel.state.collect(::renderBrowser) }
            }
        }
    }

    private fun goUp() {
        if (auth.state.value == AuthState.SIGNED_IN && viewModel.up()) return
        finish()
    }

    private fun renderAuth(state: AuthState) {
        val signedIn = state == AuthState.SIGNED_IN
        binding.signInGroup.isVisible = !signedIn
        binding.browserGroup.isVisible = signedIn
        binding.message.setText(
            if (state == AuthState.REAUTH_REQUIRED) R.string.cloud_reauth_prompt else R.string.cloud_sign_in_prompt,
        )
        if (!signedIn) binding.toolbar.setTitle(R.string.cloud_title)
        // A level that failed because the token was missing or rejected is requested again after signing in.
        if (signedIn && viewModel.state.value.failed) viewModel.retry()
    }

    private fun renderBrowser(state: CloudBrowserState) {
        if (auth.state.value == AuthState.SIGNED_IN) {
            binding.toolbar.title = if (state.isRoot) getString(R.string.cloud_title) else state.path.substringAfterLast('/')
        }
        adapter.submitList(state.items)
        binding.loading.isVisible = state.loading
        val showNotice = !state.loading && (state.failed || state.items.isEmpty())
        binding.notice.isVisible = showNotice
        binding.retry.isVisible = state.failed
        binding.noticeText.setText(if (state.failed) R.string.cloud_error else R.string.cloud_empty)
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
