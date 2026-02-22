package com.streamplayer.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.streamplayer.app.databinding.ActivitySettingsBinding
import com.streamplayer.app.ui.SettingsViewModel

/**
 * Configuration screen — allows the user to change stream URL, name,
 * auto-boot setting, reconnect delay, and max retry count.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }

        observeViewModel()
        setupSaveButton()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun observeViewModel() {
        viewModel.config.observe(this) { config ->
            // Only populate fields once (when config first loads)
            if (binding.etStreamUrl.text.isNullOrEmpty()) {
                binding.etStreamUrl.setText(config.url)
                binding.etStreamName.setText(config.name)
                binding.switchAutoBoot.isChecked = config.autoStartOnBoot
                binding.etReconnectDelay.setText(config.reconnectDelaySeconds.toString())
                binding.etMaxRetries.setText(config.maxRetries.toString())
            }
        }

        viewModel.saved.observe(this) { saved ->
            if (saved) {
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                viewModel.resetSaved()
                finish()
            }
        }

        viewModel.validationError.observe(this) { error ->
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val delay = binding.etReconnectDelay.text.toString().toIntOrNull() ?: 5
            val retries = binding.etMaxRetries.text.toString().toIntOrNull() ?: -1
            viewModel.save(
                url = binding.etStreamUrl.text.toString(),
                name = binding.etStreamName.text.toString(),
                autoStartOnBoot = binding.switchAutoBoot.isChecked,
                reconnectDelaySeconds = delay,
                maxRetries = retries
            )
        }
    }
}
