package com.efisuby.abl.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.efisuby.abl.R
import com.efisuby.abl.databinding.ActivityAboutBinding
import com.efisuby.abl.ui.common.applySystemBarInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Version, privacy policy, open-source licenses and a way to reach the developer. */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        binding.aboutVersion.text = getString(R.string.about_version, version)
        binding.aboutPolicy.setOnClickListener { open(getString(R.string.about_policy_url)) }
        binding.aboutContact.setOnClickListener { open(getString(R.string.about_contact_url)) }
        binding.aboutLicenses.setOnClickListener { showLicenses() }
    }

    private fun showLicenses() {
        val text = assets.open(LICENSES_ASSET).bufferedReader().use { it.readText() }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_licenses_title)
            .setMessage(text)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val LICENSES_ASSET = "licenses.txt"

        fun intent(context: Context): Intent = Intent(context, AboutActivity::class.java)
    }
}
