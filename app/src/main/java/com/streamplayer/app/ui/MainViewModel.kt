package com.streamplayer.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.streamplayer.app.model.StreamConfig
import com.streamplayer.app.repository.StreamRepository

/**
 * ViewModel for [com.streamplayer.app.MainActivity].
 * Holds UI state that survives configuration changes.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StreamRepository(application)

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _streamName = MutableLiveData<String>()
    val streamName: LiveData<String> = _streamName

    private val _config = MutableLiveData<StreamConfig>()
    val config: LiveData<StreamConfig> = _config

    init {
        loadConfig()
    }

    fun loadConfig() {
        val cfg = repository.load()
        _config.value = cfg
        _streamName.value = cfg.name
    }

    fun updatePlayingState(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun updateStreamName(name: String) {
        _streamName.value = name
    }
}
