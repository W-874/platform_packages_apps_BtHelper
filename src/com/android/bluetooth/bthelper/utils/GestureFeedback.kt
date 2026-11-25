/*
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */

package com.android.bluetooth.bthelper.utils

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log

class GestureFeedback {
    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 80)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Unable to initialize gesture audio", e)
        null
    }

    private var lastHorizontalTime = 0L
    private var lastLeftTime = 0L
    private var lastRightTime = 0L

    private var lastVerticalTime = 0L
    private var lastUpTime = 0L
    private var lastDownTime = 0L

    @Synchronized
    fun playDirectional(isVertical: Boolean, value: Double) {
        val generator = toneGenerator ?: return
        val now = SystemClock.uptimeMillis()
        val positive = value > 0

        if (isVertical) {
            val lastDirectionTime = if (positive) lastUpTime else lastDownTime
            if (now - lastVerticalTime < MIN_TIME_BETWEEN_SOUNDS ||
                now - lastDirectionTime < MIN_TIME_BETWEEN_DIRECTION
            ) return

            generator.startTone(ToneGenerator.TONE_PROP_BEEP, DIRECTION_TONE_DURATION_MS)
            lastVerticalTime = now
            if (positive) lastUpTime = now else lastDownTime = now
        } else {
            val lastDirectionTime = if (positive) lastRightTime else lastLeftTime
            if (now - lastHorizontalTime < MIN_TIME_BETWEEN_SOUNDS ||
                now - lastDirectionTime < MIN_TIME_BETWEEN_DIRECTION
            ) return

            // ToneGenerator does not pan. Distinct tones keep left and right recognizable.
            val tone = if (positive) ToneGenerator.TONE_DTMF_3 else ToneGenerator.TONE_DTMF_1
            generator.startTone(tone, DIRECTION_TONE_DURATION_MS)
            lastHorizontalTime = now
            if (positive) lastRightTime = now else lastLeftTime = now
        }
    }

    @Synchronized
    fun playConfirmation(isYes: Boolean) {
        val tone = if (isYes) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK
        toneGenerator?.startTone(tone)
    }

    @Synchronized
    fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        const val TAG: String = "GestureFeedback"
        private const val MIN_TIME_BETWEEN_SOUNDS = 150L
        private const val MIN_TIME_BETWEEN_DIRECTION = 200L
        private const val DIRECTION_TONE_DURATION_MS = 55
    }
}
