/*
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */

@file:OptIn(ExperimentalEncodingApi::class)

package com.android.bluetooth.bthelper.utils

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {

    @Volatile private var isRegistered: Boolean = false

    private var initialVolume: Int? = null
        @Synchronized get
        @Synchronized set

    var userPausedTheMedia = false
        @Synchronized get
        @Synchronized set

    var userPlayedTheMedia = false
        @Synchronized get
        @Synchronized set

    private var conversationalAwarenessPauseMusic: Boolean = false
        @Synchronized get
        @Synchronized set

    private var handler: Handler? = null
    private val cb: AudioManager.AudioPlaybackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(
                configs: MutableList<AudioPlaybackConfiguration>?
            ) {
                super.onPlaybackConfigChanged(configs)
                val playbackHandler = handler ?: return
                val am = audioManager ?: return
                if (configs != null && !userPausedTheMedia) {
                    playbackHandler.postDelayed({ userPlayedTheMedia = am.isMusicActive }, 7)
                }
            }
        }
    private var audioManager: AudioManager? = null

    @Synchronized
    fun onCreate(context: Context) {
        if (isRegistered) return

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        handler = Handler(Looper.getMainLooper())
        val audioManager = this.audioManager ?: return
        val handler = this.handler ?: return

        audioManager.registerAudioPlaybackCallback(cb, handler)

        isRegistered = true
    }

    @Synchronized
    fun onDestroy() {
        if (!isRegistered) return

        audioManager?.unregisterAudioPlaybackCallback(cb)
        audioManager = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        isRegistered = false
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return audioManager?.isMusicActive ?: false
    }

    @Synchronized
    fun sendPlayPause() {
        val audioManager = this.audioManager ?: return
        if (audioManager.isMusicActive) {
            sendPause()
        } else {
            sendPlay()
        }
    }

    @Synchronized
    fun sendPreviousTrack() {
        val audioManager = this.audioManager ?: return
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        )
    }

    @Synchronized
    fun sendNextTrack() {
        val audioManager = this.audioManager ?: return
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
        )
    }

    @Synchronized
    fun sendPause(force: Boolean = false) {
        val audioManager = this.audioManager ?: return
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
            userPausedTheMedia = if (force) audioManager.isMusicActive else true
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            )
        }
    }

    @Synchronized
    fun sendPlay() {
        val audioManager = this.audioManager ?: return
        if (userPausedTheMedia) {
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)
            )
        }
        if (!audioManager.isMusicActive) {
            userPausedTheMedia = false
        }
    }

    @Synchronized
    fun startSpeaking(pause: Boolean, relative: Boolean, percent: Int) {
        val audioManager = this.audioManager ?: return
        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val targetVolume =
                if (relative) {
                    ((initialVolume ?: 0) * percent / 100)
                } else if (
                    (initialVolume ?: 0) >
                        (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) *
                            percent / 100)
                ) {
                    (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) *
                        percent / 100)
                } else {
                    initialVolume
                }
            targetVolume?.let { smoothVolumeTransition((initialVolume ?: 0), it) }
            conversationalAwarenessPauseMusic = pause
            if (pause) {
                sendPause(force = true)
            }
        }
    }

    private var volumeTransition: Runnable? = null
    private var restoringVolume: Int? = null

    @Synchronized
    fun stopSpeaking(immediate: Boolean = false) {
        val audio = audioManager ?: return
        val previous = initialVolume ?: restoringVolume ?: return
        if (initialVolume == null && !immediate) return
        if (immediate) {
            volumeTransition?.let { handler?.removeCallbacks(it) }
            volumeTransition = null
            restoringVolume = null
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0)
        } else {
            restoringVolume = previous
            smoothVolumeTransition(audio.getStreamVolume(AudioManager.STREAM_MUSIC), previous)
        }
        if (initialVolume != null && conversationalAwarenessPauseMusic) sendPlay()
        initialVolume = null
    }

    @Synchronized
    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume
        val audioManager = this.audioManager ?: return
        val handler = this.handler ?: return

        volumeTransition?.let { handler.removeCallbacks(it) }
        val transition = object : Runnable {
            override fun run() {
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                }
                if (currentVolume != toVolume) handler.postDelayed(this, delay) else {
                    volumeTransition = null
                    restoringVolume = null
                }
            }
        }
        volumeTransition = transition
        handler.post(transition)
    }
}
