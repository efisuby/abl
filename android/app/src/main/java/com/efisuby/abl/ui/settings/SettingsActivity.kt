package com.efisuby.abl.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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
import com.efisuby.abl.databinding.ActivitySettingsBinding
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * "Wi-Fi only", storage with "Delete all downloads", synchronization state and the Yandex
 * account (sign-in lives in the cloud section, sign-out here).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val viewModel: SettingsViewModel by viewModels {
        viewModelFactory {
            initializer {
                with(appContainer) { SettingsViewModel(settingsRepository, libraryRepository, bookStorage, bookActions, authRepository) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.wifiOnly.setOnCheckedChangeListener { _, checked ->
            // render() sets the switch too; only a change by the user is written.
            if (checked != viewModel.state.value?.wifiOnly) viewModel.setWifiOnly(checked)
        }
        binding.removeDownloads.setOnClickListener { confirmRemoveDownloads() }
        binding.signOut.setOnClickListener { confirmSignOut() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.downloadsRemoved.collect { count ->
                        val text = resources.getQuantityString(R.plurals.settings_downloads_removed, count, count)
                        Toast.makeText(this@SettingsActivity, text, Toast.LENGTH_SHORT).show()
                    }
                }
                viewModel.state.collect { state -> state?.let(::render) }
            }
        }
    }

    private fun render(state: SettingsUiState) {
        binding.wifiOnly.isChecked = state.wifiOnly
        binding.storageUsed.text = getString(R.string.settings_storage_used, Formatter.formatShortFileSize(this, state.usedBytes))
        binding.account.setText(
            when (state.auth) {
                AuthState.SIGNED_IN -> R.string.settings_account_signed_in
                AuthState.REAUTH_REQUIRED -> R.string.settings_account_reauth
                AuthState.SIGNED_OUT -> R.string.settings_account_signed_out
            },
        )
        binding.signOut.isVisible = state.auth == AuthState.SIGNED_IN
        binding.removeDownloads.isEnabled = state.removableBooks > 0
        binding.lastSync.text = state.lastSyncAt?.let {
            getString(
                R.string.settings_sync_last,
                DateUtils.formatDateTime(this, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
            )
        } ?: getString(R.string.settings_sync_never)
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_sign_out_title)
            .setMessage(R.string.settings_sign_out_message)
            .setNegativeButton(R.string.bookmark_cancel, null)
            .setPositiveButton(R.string.settings_sign_out) { _, _ -> viewModel.signOut() }
            .show()
    }

    private fun confirmRemoveDownloads() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_remove_downloads_title)
            .setMessage(R.string.settings_remove_downloads_message)
            .setNegativeButton(R.string.bookmark_cancel, null)
            .setPositiveButton(R.string.settings_remove_downloads_confirm) { _, _ -> viewModel.removeDownloads() }
            .show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
