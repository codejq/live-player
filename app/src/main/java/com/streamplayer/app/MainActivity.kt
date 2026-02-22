package com.streamplayer.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.streamplayer.app.databinding.ActivityMainBinding
import com.streamplayer.app.service.AudioServiceConnection
import com.streamplayer.app.service.AudioStreamService
import com.streamplayer.app.ui.MainViewModel

/**
 * Single main screen: shows stream name, status (Playing / Connecting / Stopped),
 * Play and Stop buttons, and a Settings button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // Receives state broadcasts from the service
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val isPlaying = intent.getBooleanExtra(AudioStreamService.EXTRA_IS_PLAYING, false)
            val isConnecting = intent.getBooleanExtra(AudioStreamService.EXTRA_IS_CONNECTING, false)
            val name = intent.getStringExtra(AudioStreamService.EXTRA_STREAM_NAME)
            viewModel.updatePlayingState(isPlaying, isConnecting)
            name?.let { viewModel.updateStreamName(it) }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — playback still works */ }

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        requestBatteryOptimizationExemption()
        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadConfig()
        val filter = IntentFilter(AudioStreamService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        // Always start the service when the app is opened.
        // The service guards against interrupting an already-active stream (alreadyActive check),
        // so this is safe even if it is called multiple times.
        // This covers: cold launch, kill by Samsung, process death, return from background.
        AudioServiceConnection.startPlay(this)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────
    // UI Setup
    // ─────────────────────────────────────────────

    private fun setupUI() {
        binding.btnPlay.setOnClickListener {
            AudioServiceConnection.startPlay(this)
        }

        binding.btnStop.setOnClickListener {
            AudioServiceConnection.startStop(this)
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.isPlaying.observe(this) { _ -> refreshUI() }
        viewModel.isConnecting.observe(this) { _ -> refreshUI() }
        viewModel.streamName.observe(this) { name ->
            binding.tvStreamName.text = name
        }
    }

    private fun refreshUI() {
        val isPlaying = viewModel.isPlaying.value == true
        val isConnecting = viewModel.isConnecting.value == true
        binding.tvStatus.text = when {
            isPlaying    -> getString(R.string.status_playing)
            isConnecting -> getString(R.string.status_connecting)
            else         -> getString(R.string.status_stopped)
        }
        binding.statusIndicator.setBackgroundResource(
            if (isPlaying) R.drawable.indicator_active else R.drawable.indicator_inactive
        )
        binding.btnPlay.isEnabled = !isPlaying && !isConnecting
        binding.btnStop.isEnabled = isPlaying || isConnecting
    }

    // ─────────────────────────────────────────────
    // Permissions & Battery
    // ─────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.battery_dialog_title)
                    .setMessage(R.string.battery_dialog_message)
                    .setPositiveButton(R.string.battery_dialog_ok) { _, _ ->
                        try {
                            startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                            )
                        } catch (_: Exception) {
                            // Some devices don't support this intent; fall back to app settings
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            })
                        }
                    }
                    .setNegativeButton(R.string.battery_dialog_skip, null)
                    .show()
            }
        }
    }
}
