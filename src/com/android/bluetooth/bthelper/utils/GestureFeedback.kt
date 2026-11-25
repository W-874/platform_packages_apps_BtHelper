/*
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */

package com.android.bluetooth.bthelper.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.android.bluetooth.bthelper.R

class GestureFeedback(context: Context) {
    private var soundPool: SoundPool? = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
        )
        .build()

    private val directionSound: Int
    private val confirmYesSound: Int
    private val confirmNoSound: Int
    private var loadedSounds = 0
    private var horizontalStream = 0
    private var verticalStream = 0

    private var lastHorizontalTime = 0L
    private var lastLeftTime = 0L
    private var lastRightTime = 0L

    private var lastVerticalTime = 0L
    private var lastUpTime = 0L
    private var lastDownTime = 0L

    init {
        val pool = checkNotNull(soundPool)
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            synchronized(this) {
                if (status == 0) {
                    loadedSounds++
                } else {
                    Log.w(TAG, "Unable to load gesture audio sample $sampleId: $status")
                }
            }
        }
        directionSound = pool.load(context, R.raw.blip_no, 1)
        confirmYesSound = pool.load(context, R.raw.confirm_yes, 1)
        confirmNoSound = pool.load(context, R.raw.confirm_no, 1)
    }

    @Synchronized
    fun playDirectional(isVertical: Boolean, value: Double) {
        val pool = soundPool ?: return
        if (loadedSounds != 3) return
        val now = SystemClock.uptimeMillis()
        val positive = value > 0

        if (isVertical) {
            val lastDirectionTime = if (positive) lastUpTime else lastDownTime
            if (now - lastVerticalTime < MIN_TIME_BETWEEN_SOUNDS ||
                now - lastDirectionTime < MIN_TIME_BETWEEN_DIRECTION
            ) return

            if (verticalStream != 0) pool.stop(verticalStream)
            verticalStream = pool.play(directionSound, 1f, 1f, 1, 0, 1f)
            lastVerticalTime = now
            if (positive) lastUpTime = now else lastDownTime = now
        } else {
            val lastDirectionTime = if (positive) lastRightTime else lastLeftTime
            if (now - lastHorizontalTime < MIN_TIME_BETWEEN_SOUNDS ||
                now - lastDirectionTime < MIN_TIME_BETWEEN_DIRECTION
            ) return

            if (horizontalStream != 0) pool.stop(horizontalStream)
            val left = if (positive) 0f else 1f
            val right = if (positive) 1f else 0f
            horizontalStream = pool.play(directionSound, left, right, 1, 0, 1f)
            lastHorizontalTime = now
            if (positive) lastRightTime = now else lastLeftTime = now
        }
    }

    @Synchronized
    fun playConfirmation(isYes: Boolean) {
        val pool = soundPool ?: return
        if (loadedSounds != 3) return
        if (horizontalStream != 0) pool.stop(horizontalStream)
        if (verticalStream != 0) pool.stop(verticalStream)
        horizontalStream = 0
        verticalStream = 0
        val sound = if (isYes) confirmYesSound else confirmNoSound
        pool.play(sound, 1f, 1f, 1, 0, 1f)
    }

    @Synchronized
    fun release() {
        soundPool?.release()
        soundPool = null
    }

    companion object {
        const val TAG: String = "GestureFeedback"
        private const val MIN_TIME_BETWEEN_SOUNDS = 150L
        private const val MIN_TIME_BETWEEN_DIRECTION = 200L
    }
}
