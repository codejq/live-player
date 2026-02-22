package com.streamplayer.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.streamplayer.app.model.StreamConfig
import com.streamplayer.app.repository.StreamRepository

/**
 * ViewModel for [com.streamplayer.app.SettingsActivity].
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StreamRepository(application)

    private val _config = MutableLiveData<StreamConfig>()
    val config: LiveData<StreamConfig> = _config

    private val _saved = MutableLiveData(false)
    val saved: LiveData<Boolean> = _saved

    private val _validationError = MutableLiveData<String?>()
    val validationError: LiveData<String?> = _validationError

    init {
        _config.value = repository.load()
    }

    fun save(
        url: String,
        name: String,
        autoStartOnBoot: Boolean,
        reconnectDelaySeconds: Int,
        maxRetries: Int
    ) {
        // Validate URL
        if (url.isBlank()) {
            _validationError.value = "Stream URL cannot be empty"
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _validationError.value = "Stream URL must start with http:// or https://"
            return
        }
        if (reconnectDelaySeconds < 1) {
            _validationError.value = "Reconnect delay must be at least 1 second"
            return
        }

        _validationError.value = null

        val newConfig = StreamConfig(
            url = url.trim(),
            name = name.trim().ifBlank { StreamConfig.DEFAULT_NAME },
            autoStartOnBoot = autoStartOnBoot,
            reconnectDelaySeconds = reconnectDelaySeconds,
            maxRetries = maxRetries
        )
        repository.save(newConfig)
        _config.value = newConfig
        _saved.value = true
    }

    fun resetSaved() {
        _saved.value = false
    }
}
