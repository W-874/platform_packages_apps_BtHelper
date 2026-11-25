/*
 * SPDX-FileCopyrightText: Federico Dossena
 * SPDX-FileCopyrightText: The MoKee Open Source Project
 * SPDX-FileCopyrightText: Matthias Urhahn
 * SPDX-FileCopyrightText: LibrePods Contributors
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */

@file:OptIn(ExperimentalEncodingApi::class)

package com.android.bluetooth.bthelper.pods

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.OnMetadataChangedListener
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import com.android.bluetooth.bthelper.Constants
import com.android.bluetooth.bthelper.Constants.ACTION_ASI_UPDATE_BLUETOOTH_DATA
import com.android.bluetooth.bthelper.Constants.ACTION_BATTERY_LEVEL_CHANGED
import com.android.bluetooth.bthelper.Constants.ACTION_HF_INDICATORS_VALUE_CHANGED
import com.android.bluetooth.bthelper.Constants.COMPANION_TYPE_NONE
import com.android.bluetooth.bthelper.Constants.EXTRA_BATTERY_LEVEL
import com.android.bluetooth.bthelper.Constants.EXTRA_HF_INDICATORS_IND_ID
import com.android.bluetooth.bthelper.Constants.EXTRA_HF_INDICATORS_IND_VALUE
import com.android.bluetooth.bthelper.Constants.HF_INDICATOR_BATTERY_LEVEL_STATUS
import com.android.bluetooth.bthelper.Constants.PACKAGE_ASI
import com.android.bluetooth.bthelper.R
import com.android.bluetooth.bthelper.getSharedPreferences
import com.android.bluetooth.bthelper.getUriAllowlist
import com.android.bluetooth.bthelper.isLowLatencySupported
import com.android.bluetooth.bthelper.setSingleDevice
import com.android.bluetooth.bthelper.utils.A2dpReceiver
import com.android.bluetooth.bthelper.utils.AACPManager
import com.android.bluetooth.bthelper.utils.AACPManager.StemPressType
import com.android.bluetooth.bthelper.utils.AirPodsNotifications
import com.android.bluetooth.bthelper.utils.BLEManager
import com.android.bluetooth.bthelper.utils.BatteryStatus
import com.android.bluetooth.bthelper.utils.BluetoothConnectionManager
import com.android.bluetooth.bthelper.utils.BluetoothSocketManager
import com.android.bluetooth.bthelper.utils.CameraStateMonitor
import com.android.bluetooth.bthelper.utils.GestureDetector
import com.android.bluetooth.bthelper.utils.HeadTracking
import com.android.bluetooth.bthelper.utils.KeyStorageManager
import com.android.bluetooth.bthelper.utils.MediaController
import com.android.bluetooth.bthelper.utils.PodsA2dpListener
import com.android.bluetooth.bthelper.utils.PodsUuidListener
import com.android.bluetooth.bthelper.utils.StemAction
import com.android.bluetooth.bthelper.utils.UuidReceiver
import com.android.bluetooth.bthelper.utils.models.IPods
import com.android.bluetooth.bthelper.utils.models.RegularPods
import com.android.bluetooth.bthelper.utils.models.RegularPodsMetadata
import com.android.bluetooth.bthelper.utils.models.SinglePods
import com.android.bluetooth.bthelper.utils.models.SinglePodsMetadata
import com.android.bluetooth.bthelper.utils.setMetadataBoolean
import com.android.bluetooth.bthelper.utils.setMetadataInt
import com.android.bluetooth.bthelper.utils.setMetadataString
import com.android.bluetooth.bthelper.utils.setMetadataUri
import com.android.bluetooth.bthelper.utils.setMetadataValue
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * This is the class that does most of the work. It has 3 functions:
 * - Detect when AirPods are detected
 * - Receive beacons from AirPods and decode them
 */
class PodsService :
    Service(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    PodsUuidListener,
    AACPManager.PacketCallback,
    BLEManager.AirPodsStatusListener,
    PodsA2dpListener,
    OnMetadataChangedListener,
    CameraStateMonitor.Listener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ServiceConfig(
        var earDetectionEnabled: Boolean = true,
        var conversationalAwareness: Boolean = false,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationalAwarenessVolume: Int = 43,
        var useAlternatePackets: Boolean = false,
        var leftSinglePressAction: StemAction =
            StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var rightSinglePressAction: StemAction =
            StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var leftDoublePressAction: StemAction =
            StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var rightDoublePressAction: StemAction =
            StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var leftTriplePressAction: StemAction =
            StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var rightTriplePressAction: StemAction =
            StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var leftLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var rightLongPressAction: StemAction =
            StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var cameraAction: StemPressType? = null,
    )

    private var config: ServiceConfig? = null
    private var bluetoothSocketManager: BluetoothSocketManager? = null

    private var currentDevice: BluetoothDevice? = null

    @Volatile private var isSliceSet = false
    private var publishedModel: IPods? = null
    private var reportedModel: IPods? = null
    private var hasSplitBattery = false
    private var hasAacpBattery = false
    private var shortFormat = false
    @Volatile private var hasVerifiedModel = false
    private val hasStandardModel: Boolean
        get() = hasVerifiedModel && BluetoothConnectionManager.isConnected
    private val genericEarbuds = RegularPods()
    private val genericHeadphones = SinglePods()
    private var publishedAsShort = false

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var sharedPreferences: SharedPreferences? = null

    private var inputManager: InputManager? = null

    private var telecomManager: TelecomManager? = null
    private var gestureDetector: GestureDetector? = null
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener =
        object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                super.onCallStateChanged(state, phoneNumber)
                handleCallStateChange(state)
            }
        }
    private var cameraStateMonitor: CameraStateMonitor? = null

    private var incomingCallJob: Job? = null
    var cameraActive = false
    private var disconnectedBecauseReversed = false

    val earDetectNotif = AirPodsNotifications.EarDetection()
    val ancNotif = AirPodsNotifications.ANC()
    val batteryNotif = AirPodsNotifications.BatteryNotification()
    val conversAwareNotif = AirPodsNotifications.ConversationalAwarenessNotification()
    val handler = Handler(Looper.getMainLooper())

    @Volatile var isHeadTrackingActive = false

    var uuidReceiver: UuidReceiver? = null
    var a2dpReceiver: A2dpReceiver? = null
    var bleManager: BLEManager? = null
    var keyStorageManager: KeyStorageManager? = null

    val shutdownFilter =
        IntentFilter().apply {
            addAction(Intent.ACTION_SHUTDOWN)
            setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY)
        }
    val shutdownReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SHUTDOWN) {
                    stopSelf()
                }
            }
        }

    override fun onDeviceStatusChanged(
        currStatus: BLEManager.AirPodsStatus,
        prevStatus: BLEManager.AirPodsStatus?,
    ) {
        if (!currStatus.shortFormat && currStatus.connectionState == Constants.STATE_DISCONNECTED) {
            bluetoothSocketManager?.connectToSocket(currentDevice)
        }
        publishBleStatus(currStatus)
    }

    override fun onBroadcastFromNewAddress(status: BLEManager.AirPodsStatus) {}

    override fun onLidStateChanged(status: BLEManager.AirPodsStatus?, lidOpen: Boolean) {}

    override fun onEarStateChanged(
        status: BLEManager.AirPodsStatus,
        leftInEar: Boolean,
        rightInEar: Boolean,
    ) {
        // processEarDetectionChange(earDetectNotif.createEarDetectionData(leftInEar, rightInEar))
    }

    override fun onBatteryChanged(status: BLEManager.AirPodsStatus) {}

    @Synchronized
    private fun publishBleStatus(status: BLEManager.AirPodsStatus) {
        val device = currentDevice ?: return
        if (status.shortFormat && hasVerifiedModel) return
        if (status.model != null || !status.shortFormat) shortFormat = status.shortFormat
        if (!status.shortFormat && status.model != null && !hasVerifiedModel) {
            reportedModel = status.model
            hasVerifiedModel = true
            sharedPreferences?.edit { putBoolean("${device.address}_verified_proximity", true) }
            setupStemActions()
        } else {
            status.model?.let { reportedModel = it }
        }
        hasSplitBattery = hasSplitBattery || status.model?.isSingle == false ||
            status.leftBattery >= 0 || status.rightBattery >= 0 || status.caseBattery >= 0
        if (!hasAacpBattery) {
            batteryNotif.setBatteryDirect(
                leftLevel = status.leftBattery,
                leftCharging = status.isLeftCharging,
                rightLevel = status.rightBattery,
                rightCharging = status.isRightCharging,
                caseLevel = status.caseBattery,
                caseCharging = status.isCaseCharging,
            )
        }
        updatePodsStatus(device)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun handleCallStateChange(state: Int) {
        incomingCallJob?.cancel()

        incomingCallJob =
            serviceScope.launch {
                delay(100)
                handleIncomingCall(state)
            }
    }

    suspend fun handleIncomingCall(state: Int) {
        val bleManager = bleManager ?: return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (config?.headGestures == true) {
                    startHeadTracking()
                    gestureDetector?.startDetection { accepted ->
                        if (accepted) {
                            answerCall()
                        } else {
                            rejectCall()
                        }
                    }
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK,
            TelephonyManager.CALL_STATE_IDLE -> {
                gestureDetector?.stopDetection()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testHeadGestures(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            gestureDetector?.startDetection(doNotStop = true) { accepted ->
                if (continuation.isActive) {
                    continuation.resume(accepted) { gestureDetector?.stopDetection() }
                }
            }
        }
    }

    private fun answerCall() {
        try {
            if (
                checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                telecomManager?.acceptRingingCall()
            }

            sendToast(getString(R.string.head_gesture_call_answered_toast))
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call", e)
        }
    }

    private fun rejectCall() {
        try {
            if (
                checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                telecomManager?.endCall()
            }
            sendToast(getString(R.string.head_gesture_call_rejected_toast))
        } catch (e: Exception) {
            Log.e(TAG, "Error rejecting call", e)
        }
    }

    fun sendToast(message: String) {
        handler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    @Synchronized
    override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
        val device = currentDevice ?: return
        if (!batteryNotif.setBattery(batteryInfo)) return
        hasAacpBattery = true
        hasSplitBattery = hasSplitBattery || batteryNotif.hasSplitBattery
        updatePodsStatus(device)

        // Battery reports are not evidence that this firmware supports audio routing.
        if (!hasStandardModel) return
        val isHeadset = reportedModel?.isSingle == true

        if (
            (batteryNotif.getBattery()[0].status == BatteryStatus.CHARGING &&
                batteryNotif.getBattery()[1].status == BatteryStatus.CHARGING) ||
                (isHeadset && batteryNotif.getBattery()[3].status == BatteryStatus.CHARGING)
        ) {
            disconnectAudio()
        } else {
            connectAudio()
        }
    }

    override fun onEarDetectionReceived(earDetection: ByteArray) {
        processEarDetectionChange(earDetection)
    }

    override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
        if (!hasStandardModel) return
        conversAwareNotif.setData(conversationAwareness)
        if (conversAwareNotif.status == 1.toByte() || conversAwareNotif.status == 2.toByte()) {
            MediaController.startSpeaking()
        } else if (
            conversAwareNotif.status == 8.toByte() || conversAwareNotif.status == 9.toByte()
        ) {
            MediaController.stopSpeaking()
        }
    }

    override fun onControlCommandReceived(controlCommand: ByteArray) {
        val command = AACPManager.ControlCommand.fromByteArray(controlCommand) ?: return
        if (command.identifier == AACPManager.ControlCommandIdentifiers.LISTENING_MODE.value) {
            ancNotif.setStatus(
                byteArrayOf(command.value.takeIf { it.isNotEmpty() }?.get(0) ?: 0x00.toByte())
            )
        }
    }

    override fun onOwnershipChangeReceived(owns: Boolean) {
        if (!hasStandardModel) return
        if (!owns) {
            MediaController.sendPause(true)
            disconnectAudio()
        }
    }

    override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) {
        if (!hasStandardModel) return
        val senderName =
            AACPManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
        AACPManager.sendControlCommand(
            AACPManager.ControlCommandIdentifiers.OWNS_CONNECTION.value,
            byteArrayOf(0x00),
        )
        disconnectAudio()
        if (reasonReverseTapped) {
            disconnectedBecauseReversed = true
            disconnectAudio()
        }
        MediaController.sendPause(true)
    }

    override fun onShowNearbyUI(sender: String) {
        val senderName =
            AACPManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
    }

    override fun onHeadTrackingReceived(headTracking: ByteArray) {
        if (isHeadTrackingActive) {
            HeadTracking.processPacket(headTracking)
            processHeadTrackingData(headTracking)
        }
    }

    override fun onProximityKeysReceived(proximityKeys: ByteArray) {
        val address = currentDevice?.address ?: return
        val keys = AACPManager.parseProximityKeysResponse(proximityKeys)
        val keyStorageManager = this.keyStorageManager ?: return
        sharedPreferences?.edit {
            for (key in keys) {
                if (key.value.size != 16) continue
                val (iv, encryptedValue) = keyStorageManager.encrypt(key.value)
                val prefix = "${address}_${key.key.name}"
                putString("${prefix}_encrypted", Base64.encode(encryptedValue))
                putString("${prefix}_iv", Base64.encode(iv))
            }
            apply()
        }
    }

    override fun onStemPressReceived(stemPress: ByteArray) {
        if (!hasStandardModel) return
        val (stemPressType, bud) = AACPManager.parseStemPressResponse(stemPress) ?: return

        val action = getActionFor(bud, stemPressType)

        if (cameraActive && stemPressType != null && config?.cameraAction != stemPressType) {
            executeStemAction(StemAction.CAMERA_SHUTTER)
            return
        }

        action?.let { executeStemAction(it) }
    }

    private fun getLocalBluetoothAddress(): String? =
        // The adapter returns this redacted address when access is denied.
        bluetoothAdapter?.address?.takeIf { it != "02:00:00:00:00:00" }

    override fun onAudioSourceReceived(audioSource: ByteArray) {
        if (!hasStandardModel) return
        val localMac = getLocalBluetoothAddress() ?: return
        val source = AACPManager.audioSource ?: return
        if (source.type != AACPManager.AudioSourceType.NONE && source.mac != localMac) {
            AACPManager.sendControlCommand(
                AACPManager.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                byteArrayOf(0x00),
            )
        }
    }

    override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.ConnectedDevice>) {
        if (!hasStandardModel) return
        val localMac = getLocalBluetoothAddress() ?: return
        val newDevices =
            connectedDevices.filter { newDevice ->
                val notInOld =
                    AACPManager.oldConnectedDevices.none { oldDevice ->
                        oldDevice.mac == newDevice.mac
                    }
                val notLocal = newDevice.mac != localMac
                notInOld && notLocal
            }

        for (device in newDevices) {
            AACPManager.sendMediaInformationNewDevice(
                selfMacAddress = localMac,
                targetMacAddress = device.mac,
            )
            AACPManager.sendAddTiPiDevice(selfMacAddress = localMac, targetMacAddress = device.mac)
        }
    }

    override fun onUnknownPacketReceived(packet: ByteArray) {}

    private fun getActionFor(bud: AACPManager.StemPressBudType, type: StemPressType): StemAction? {
        val config = config ?: return null
        return when (type) {
            StemPressType.SINGLE_PRESS ->
                if (bud == AACPManager.StemPressBudType.LEFT) config.leftSinglePressAction
                else config.rightSinglePressAction
            StemPressType.DOUBLE_PRESS ->
                if (bud == AACPManager.StemPressBudType.LEFT) config.leftDoublePressAction
                else config.rightDoublePressAction
            StemPressType.TRIPLE_PRESS ->
                if (bud == AACPManager.StemPressBudType.LEFT) config.leftTriplePressAction
                else config.rightTriplePressAction
            StemPressType.LONG_PRESS ->
                if (bud == AACPManager.StemPressBudType.LEFT) config.leftLongPressAction
                else config.rightLongPressAction
        }
    }

    private fun executeStemAction(action: StemAction) {
        when (action) {
            StemAction.defaultActions[StemPressType.SINGLE_PRESS] -> {
                // Default single press action: Play/Pause, not taking action.
            }
            StemAction.PLAY_PAUSE -> MediaController.sendPlayPause()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.CAMERA_SHUTTER -> triggerVirtualKeypress(KeyEvent.KEYCODE_CAMERA)
            StemAction.DIGITAL_ASSISTANT -> {
                val intent =
                    Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                startActivity(intent)
            }
            StemAction.CYCLE_NOISE_CONTROL_MODES -> {
                sendBroadcast(Intent(Constants.ACTION_SET_ANC_MODE))
            }
        }
    }

    private fun triggerVirtualKeypress(keyCode: Int) {
        val now: Long = SystemClock.uptimeMillis()
        val downEvent: KeyEvent =
            KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0,
                0,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD,
            )
        val upEvent: KeyEvent = KeyEvent.changeAction(downEvent, KeyEvent.ACTION_UP)

        inputManager?.let {
            it.injectInputEvent(downEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
            it.injectInputEvent(upEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        }
    }

    @Synchronized
    private fun processEarDetectionChange(earDetection: ByteArray) {
        if (!earDetectNotif.isEarDetectionData(earDetection)) return
        if (earDetection[6].toInt() !in 0..2 || earDetection[7].toInt() !in 0..2) return
        if (!hasStandardModel) return
        var inEar = false
        var inEarData =
            listOf(
                earDetectNotif.status[0] == 0x00.toByte(),
                earDetectNotif.status[1] == 0x00.toByte(),
            )
        var justEnabledA2dp = false
        earDetectNotif.setStatus(earDetection)
        if (config?.earDetectionEnabled != true) return

        val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
        inEar = data[0] == 0x00.toByte() && data[1] == 0x00.toByte()

        val newInEarData = listOf(data[0] == 0x00.toByte(), data[1] == 0x00.toByte())

        if (newInEarData.contains(true) && inEarData == listOf(false, false)) {
            connectAudio()
            justEnabledA2dp = true
            registerA2dpConnectionReceiver()
            if (MediaController.getMusicActive() == true) {
                MediaController.userPlayedTheMedia = true
            }
        } else if (newInEarData == listOf(false, false)) {
            MediaController.sendPause(force = true)
            if (config?.disconnectWhenNotWearing == true) {
                disconnectAudio()
            }
        }

        if (inEarData.contains(false) && newInEarData == listOf(true, true)) {
            MediaController.userPlayedTheMedia = false
        }

        if (newInEarData.contains(false) && inEarData == listOf(true, true)) {
            MediaController.userPlayedTheMedia = false
        }

        if (newInEarData.sorted() != inEarData.sorted()) {
            inEarData = newInEarData
            if (inEar == true) {
                if (!justEnabledA2dp) {
                    justEnabledA2dp = false
                    MediaController.sendPlay()
                    MediaController.userPausedTheMedia = false
                }
            } else {
                MediaController.sendPause()
            }
        }
    }

    @Synchronized
    override fun onPodsA2dpDetected(device: BluetoothDevice?) {
        if (currentDevice != null && currentDevice?.equals(device) == true) {
            MediaController.sendPlay()
            MediaController.userPausedTheMedia = false
        }
    }

    @Synchronized
    private fun registerA2dpConnectionReceiver() {
        currentDevice?.let { a2dpReceiver?.onCreate(this, it) }
    }

    @Synchronized
    fun processHeadTrackingData(data: ByteArray) {
        val horizontal = ByteBuffer.wrap(data, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vertical = ByteBuffer.wrap(data, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        gestureDetector?.processHeadOrientation(horizontal, vertical)
    }

    private fun initializeConfig() {
        val sharedPreferences = this.sharedPreferences ?: return
        config =
            ServiceConfig(
                earDetectionEnabled =
                    sharedPreferences.getBoolean(Constants.KEY_AUTOMATIC_EAR_DETECTION, true),
                conversationalAwarenessPauseMusic =
                    sharedPreferences.getBoolean(
                        Constants.KEY_CONVERSATIONAL_AWARENESS_PAUSE_MUSIC,
                        false,
                    ),
                relativeConversationalAwarenessVolume =
                    sharedPreferences.getBoolean(
                        Constants.KEY_RELATIVE_CONVERSATIONAL_AWARENESS_VOLUME,
                        true,
                    ),
                headGestures = sharedPreferences.getBoolean(Constants.KEY_HEAD_GESTURES, true),
                disconnectWhenNotWearing =
                    sharedPreferences.getBoolean(Constants.KEY_DISCONNECT_WHEN_NOT_WEARING, false),
                conversationalAwarenessVolume =
                    sharedPreferences.getInt(Constants.KEY_CONVERSATIONAL_AWARENESS_VOLUME, 43),
                useAlternatePackets =
                    sharedPreferences.getBoolean(
                        Constants.KEY_USE_ALTERNATE_HEAD_TRACKING_PACKETS,
                        false,
                    ),
                leftSinglePressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_LEFT_SINGLE_PRESS_ACTION,
                            StemAction.PLAY_PAUSE.name,
                        ) ?: StemAction.PLAY_PAUSE.name
                    )!!,
                rightSinglePressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_RIGHT_SINGLE_PRESS_ACTION,
                            StemAction.PLAY_PAUSE.name,
                        ) ?: StemAction.PLAY_PAUSE.name
                    )!!,
                leftDoublePressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_LEFT_DOUBLE_PRESS_ACTION,
                            StemAction.PREVIOUS_TRACK.name,
                        ) ?: StemAction.PREVIOUS_TRACK.name
                    )!!,
                rightDoublePressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_RIGHT_DOUBLE_PRESS_ACTION,
                            StemAction.NEXT_TRACK.name,
                        ) ?: StemAction.NEXT_TRACK.name
                    )!!,
                leftTriplePressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_LEFT_TRIPLE_PRESS_ACTION,
                            StemAction.PREVIOUS_TRACK.name,
                        ) ?: StemAction.PREVIOUS_TRACK.name
                    )!!,
                rightTriplePressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_RIGHT_TRIPLE_PRESS_ACTION,
                            StemAction.NEXT_TRACK.name,
                        ) ?: StemAction.NEXT_TRACK.name
                    )!!,
                leftLongPressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_LEFT_LONG_PRESS_ACTION,
                            StemAction.CYCLE_NOISE_CONTROL_MODES.name,
                        ) ?: StemAction.CYCLE_NOISE_CONTROL_MODES.name
                    )!!,
                rightLongPressAction =
                    StemAction.fromString(
                        sharedPreferences.getString(
                            Constants.KEY_RIGHT_LONG_PRESS_ACTION,
                            StemAction.DIGITAL_ASSISTANT.name,
                        ) ?: StemAction.DIGITAL_ASSISTANT.name
                    )!!,
                cameraAction =
                    StemPressType.valueOf(
                        sharedPreferences.getString(
                            Constants.KEY_CAMERA_ACTION,
                            StemPressType.SINGLE_PRESS.name,
                        ) ?: StemPressType.SINGLE_PRESS.name
                    )!!,
            )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        val config = config ?: return
        when (key) {
            Constants.KEY_PERSONALIZED_VOLUME -> {
                if (hasStandardModel) AACPManager.sendControlCommand(
                    AACPManager.ControlCommandIdentifiers.ADAPTIVE_VOLUME_CONFIG.value,
                    preferences.getBoolean(key, false),
                )
            }
            Constants.KEY_CONVERSATIONAL_AWARENESS -> {
                if (hasStandardModel) AACPManager.sendControlCommand(
                    AACPManager.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                    preferences.getBoolean(key, false),
                )
            }
            Constants.KEY_AUTOMATIC_EAR_DETECTION ->
                config.earDetectionEnabled = preferences.getBoolean(key, true)
            Constants.KEY_CONVERSATIONAL_AWARENESS_PAUSE_MUSIC ->
                config.conversationalAwarenessPauseMusic = preferences.getBoolean(key, false)
            Constants.KEY_RELATIVE_CONVERSATIONAL_AWARENESS_VOLUME ->
                config.relativeConversationalAwarenessVolume = preferences.getBoolean(key, true)
            Constants.KEY_HEAD_GESTURES -> config.headGestures = preferences.getBoolean(key, true)
            Constants.KEY_DISCONNECT_WHEN_NOT_WEARING ->
                config.disconnectWhenNotWearing = preferences.getBoolean(key, false)
            Constants.KEY_CONVERSATIONAL_AWARENESS_VOLUME ->
                config.conversationalAwarenessVolume = preferences.getInt(key, 43)
            Constants.KEY_USE_ALTERNATE_HEAD_TRACKING_PACKETS ->
                config.useAlternatePackets = preferences.getBoolean(key, false)
            Constants.KEY_LEFT_SINGLE_PRESS_ACTION -> {
                config.leftSinglePressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.PLAY_PAUSE.name)
                            ?: StemAction.PLAY_PAUSE.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_RIGHT_SINGLE_PRESS_ACTION -> {
                config.rightSinglePressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.PLAY_PAUSE.name)
                            ?: StemAction.PLAY_PAUSE.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_LEFT_DOUBLE_PRESS_ACTION -> {
                config.leftDoublePressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.PREVIOUS_TRACK.name)
                            ?: StemAction.PREVIOUS_TRACK.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_RIGHT_DOUBLE_PRESS_ACTION -> {
                config.rightDoublePressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.NEXT_TRACK.name)
                            ?: StemAction.NEXT_TRACK.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_LEFT_TRIPLE_PRESS_ACTION -> {
                config.leftTriplePressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.PREVIOUS_TRACK.name)
                            ?: StemAction.PREVIOUS_TRACK.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_RIGHT_TRIPLE_PRESS_ACTION -> {
                config.rightTriplePressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.NEXT_TRACK.name)
                            ?: StemAction.NEXT_TRACK.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_LEFT_LONG_PRESS_ACTION -> {
                config.leftLongPressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.CYCLE_NOISE_CONTROL_MODES.name)
                            ?: StemAction.CYCLE_NOISE_CONTROL_MODES.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_RIGHT_LONG_PRESS_ACTION -> {
                config.rightLongPressAction =
                    StemAction.fromString(
                        preferences.getString(key, StemAction.DIGITAL_ASSISTANT.name)
                            ?: StemAction.DIGITAL_ASSISTANT.name
                    )!!
                setupStemActions()
            }
            Constants.KEY_CAMERA_ACTION -> {
                config.cameraAction =
                    StemPressType.valueOf(
                        preferences.getString(key, StemPressType.SINGLE_PRESS.name)
                            ?: StemPressType.SINGLE_PRESS.name
                    )!!
            }
            Constants.KEY_LOW_LATENCY_AUDIO -> {
                if (isLowLatencySupported()) {
                    currentDevice?.setLowLatencyAudioAllowed(preferences.getBoolean(key, false))
                }
            }
        }
    }

    val ancModeFilter = IntentFilter(Constants.ACTION_SET_ANC_MODE)
    val ancModeReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Constants.ACTION_SET_ANC_MODE) return
                if (!hasStandardModel) return
                if (intent.hasExtra(Constants.EXTRA_MODE)) {
                    val mode = intent.getIntExtra(Constants.EXTRA_MODE, -1)
                    if (mode in 1..4) {
                        AACPManager.sendControlCommand(
                            AACPManager.ControlCommandIdentifiers.LISTENING_MODE.value,
                            mode,
                        )
                    }
                    return
                }
                val currentMode = ancNotif.status
                val allowOffModeValue =
                    AACPManager.controlCommandStatusList?.find {
                        it.identifier == AACPManager.ControlCommandIdentifiers.ALLOW_OFF_OPTION
                    }
                val allowOffMode =
                    allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte()

                val nextMode =
                    if (allowOffMode) {
                        when (currentMode) {
                            1 -> 2
                            2 -> 3
                            3 -> 4
                            4 -> 1
                            else -> 1
                        }
                    } else {
                        when (currentMode) {
                            1 -> 2
                            2 -> 3
                            3 -> 4
                            4 -> 2
                            else -> 2
                        }
                    }

                AACPManager.sendControlCommand(
                    AACPManager.ControlCommandIdentifiers.LISTENING_MODE.value,
                    nextMode,
                )
            }
        }

    override fun onCreate() {
        super.onCreate()

        sharedPreferences = getSharedPreferences()
        initializeConfig()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(this@PodsService)

        keyStorageManager = KeyStorageManager()
        uuidReceiver = UuidReceiver(this)
        a2dpReceiver = A2dpReceiver(this)

        MediaController.onCreate(this)

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        gestureDetector = GestureDetector(this) { stopHeadTracking() }

        telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)

        bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        AACPManager.setPacketCallback(this)

        bluetoothSocketManager =
            BluetoothSocketManager(
                serviceScope,
                { startHeadTracking() },
                { stopHeadTracking() },
                { setupStemActions() },
            )

        registerReceiver(ancModeReceiver, ancModeFilter, RECEIVER_NOT_EXPORTED)
        registerReceiver(shutdownReceiver, shutdownFilter, RECEIVER_NOT_EXPORTED)

        bleManager = BLEManager(this, keyStorageManager!!)
        bleManager?.setAirPodsStatusListener(this)

        cameraStateMonitor = CameraStateMonitor(this)
        cameraStateMonitor?.onCreate()
        cameraStateMonitor?.registerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device =
            intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return START_STICKY

        if (currentDevice == null && intent?.action == Constants.ACTION_CONNECTED) {
            currentDevice = device
            updatePodsStatus(device)
            bluetoothAdapter?.addOnMetadataChangedListener(device, mainExecutor, this)
            serviceScope.launch { bluetoothSocketManager?.connectToSocket(device) }
            connect()
            bleManager?.startScanning(device)
            return START_STICKY
        }

        if (
            currentDevice != null &&
                currentDevice?.equals(device) == true &&
                intent?.action == Constants.ACTION_NAME_CHANGED &&
                hasStandardModel
        ) {
            val name: String? = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
            setName(device, name)
            return START_STICKY
        }

        if (
            currentDevice != null &&
                currentDevice?.equals(device) == true &&
                intent?.action == Constants.ACTION_DISCONNECTED
        ) {
            stopSelf()
            return START_STICKY
        }

        return START_STICKY
    }

    fun connect() {
        currentDevice?.let { uuidReceiver?.onCreate(this, it) }
    }

    override fun onPodsDetected(device: BluetoothDevice?) {
        if (device == null) return
        connectToA2dpProfile(device)
    }

    private fun connectToA2dpProfile(device: BluetoothDevice) {
        val bluetoothAdapter = bluetoothAdapter ?: return

        bluetoothAdapter.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.contains(device)) {
                            serviceScope.launch { bluetoothSocketManager?.connectToSocket(device) }
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            },
            BluetoothProfile.A2DP,
        )
    }

    fun setEarDetection(enabled: Boolean) {
        val config = config ?: return

        if (config.earDetectionEnabled == enabled) return
        config.earDetectionEnabled = enabled
        sharedPreferences?.edit {
            putBoolean(Constants.KEY_AUTOMATIC_EAR_DETECTION, enabled)
            apply()
        }
    }

    override fun onCameraOpened() {
        cameraActive = true
        setupStemActions()
    }

    override fun onCameraClosed() {
        cameraActive = false
        setupStemActions()
    }

    fun isCustomAction(action: StemAction?, default: StemAction?): Boolean =
        action != null && default != null && action != default

    fun setupStemActions() {
        if (!hasStandardModel) return
        val config = config ?: return

        val singlePressDefault = StemAction.defaultActions[StemPressType.SINGLE_PRESS]
        val doublePressDefault = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]
        val triplePressDefault = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]
        val longPressDefault = StemAction.defaultActions[StemPressType.LONG_PRESS]

        val singlePressCustomized =
            isCustomAction(config.leftSinglePressAction, singlePressDefault) ||
                isCustomAction(config.rightSinglePressAction, singlePressDefault) ||
                (cameraActive && config.cameraAction == StemPressType.SINGLE_PRESS)
        val doublePressCustomized =
            isCustomAction(config.leftDoublePressAction, doublePressDefault) ||
                isCustomAction(config.rightDoublePressAction, doublePressDefault)
        val triplePressCustomized =
            isCustomAction(config.leftTriplePressAction, triplePressDefault) ||
                isCustomAction(config.rightTriplePressAction, triplePressDefault)
        val longPressCustomized =
            isCustomAction(config.leftLongPressAction, longPressDefault) ||
                isCustomAction(config.rightLongPressAction, longPressDefault) ||
                (cameraActive && config.cameraAction == StemPressType.LONG_PRESS)

        AACPManager.sendStemConfigPacket(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
        )
    }

    private fun disconnectAudio() {
        val device = currentDevice ?: return
        try {
            device.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting audio profiles", e)
        }
    }

    @Synchronized
    fun connectAudio() {
        val device = currentDevice ?: return
        val bluetoothAdapter = bluetoothAdapter ?: return

        bluetoothAdapter?.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        try {
                            device?.connect()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error connecting A2DP", e)
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                            MediaController.sendPlay()
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    Log.d(TAG, "A2DP service disconnected")
                }
            },
            BluetoothProfile.A2DP,
        )

        bluetoothAdapter?.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        try {
                            device?.connect()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error connecting HEADSET", e)
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    Log.d(TAG, "HEADSET service disconnected")
                }
            },
            BluetoothProfile.HEADSET,
        )
    }

    @Synchronized
    fun setName(device: BluetoothDevice?, name: String?) {
        if (device == null || name == null) {
            return
        }

        if (!device.equals(currentDevice)) {
            return
        }

        AACPManager.sendRename(name)
    }

    override fun onDestroy() {
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)

        cameraStateMonitor?.unregisterListener(this)
        cameraStateMonitor?.onDestroy()
        cameraStateMonitor = null

        unregisterReceiver(shutdownReceiver)
        unregisterReceiver(ancModeReceiver)

        bleManager?.stopScanning()

        bluetoothSocketManager?.disconnect()
        gestureDetector?.release()
        inputManager = null

        config = null
        bluetoothSocketManager = null
        sharedPreferences = null
        bleManager = null
        MediaController.onDestroy()
        gestureDetector = null
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        telephonyManager = null
        currentDevice?.let { bluetoothAdapter?.removeOnMetadataChangedListener(it, this) }
        currentDevice = null
        telecomManager = null
        bluetoothAdapter = null
        uuidReceiver?.onDestroy()
        uuidReceiver = null
        a2dpReceiver?.onDestroy()
        a2dpReceiver = null
        keyStorageManager = null

        AACPManager.disconnected()

        serviceScope.cancel()

        super.onDestroy()
    }

    @Synchronized
    fun startHeadTracking() {
        if (!hasStandardModel) return
        val config = config ?: return
        isHeadTrackingActive = true
        AACPManager.sendStartHeadTracking(config.useAlternatePackets)
        HeadTracking.reset()
    }

    @Synchronized
    fun stopHeadTracking() {
        if (!isHeadTrackingActive) return
        val config = config ?: return
        AACPManager.sendStopHeadTracking(config.useAlternatePackets)
        isHeadTrackingActive = false
    }

    private fun resToFile(resId: Int): File? {
        val drawable = ContextCompat.getDrawable(this, resId) ?: return null

        val iconDir = File(filesDir, "icons")
        if (!iconDir.exists()) {
            iconDir.mkdirs()
        }

        val iconFile = File(iconDir, resources.getResourceEntryName(resId) + ".png")
        if (iconFile.exists()) {
            return iconFile
        }

        try {
            val drawableWidth = drawable.intrinsicWidth
            val drawableHeight = drawable.intrinsicHeight
            if (drawableWidth <= 0 || drawableHeight <= 0) {
                Log.e(TAG, "Drawable has invalid intrinsic dimensions.")
                FileOutputStream(iconFile).use { out ->
                    drawable.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                return iconFile
            }

            val squareSize = maxOf(drawableWidth, drawableHeight)

            val paddedBitmap = Bitmap.createBitmap(squareSize, squareSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(paddedBitmap)

            canvas.drawColor(Color.TRANSPARENT)

            val offsetX = (squareSize - drawableWidth) / 2f
            val offsetY = (squareSize - drawableHeight) / 2f

            drawable.setBounds(
                offsetX.toInt(),
                offsetY.toInt(),
                offsetX.toInt() + drawableWidth,
                offsetY.toInt() + drawableHeight,
            )
            drawable.draw(canvas)

            FileOutputStream(iconFile).use { out ->
                paddedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            paddedBitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing icon bitmap", e)
            return null
        }

        return iconFile
    }

    // Convert internal resource address to URI
    private fun getIconUri(resId: Int): Uri? {
        val iconFile = resToFile(resId) ?: return null

        val uri = FileProvider.getUriForFile(this, Constants.AUTHORITY_FILE, iconFile)

        getUriAllowlist().forEach { pkg ->
            grantUriPermission(
                pkg,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

        return uri
    }

    private fun restoreInitialMetadata(device: BluetoothDevice, key: Int) {
        when (key) {
            BluetoothDevice.METADATA_COMPANION_APP -> {
                device.setMetadataString(
                    BluetoothDevice.METADATA_COMPANION_APP,
                    Constants.AUTHORITY_BTHELPER,
                )
            }
            BluetoothDevice.METADATA_SOFTWARE_VERSION -> {
                device.setMetadataString(
                    BluetoothDevice.METADATA_SOFTWARE_VERSION,
                    COMPANION_TYPE_NONE,
                )
            }
            BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI,
            Constants.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS -> {
                sendSliceSetupIntent(device, key)
            }
        }
    }

    private fun setInitialMetadata(device: BluetoothDevice): Boolean {
        var ret =
            device.setMetadataString(
                BluetoothDevice.METADATA_COMPANION_APP,
                Constants.AUTHORITY_BTHELPER,
            )
        ret =
            device.setMetadataString(BluetoothDevice.METADATA_SOFTWARE_VERSION, COMPANION_TYPE_NONE)
        sendSliceSetupIntent(device, BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI)
        sendSliceSetupIntent(device, Constants.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS)

        return ret
    }

    private fun sendSliceSetupIntent(device: BluetoothDevice, key: Int) {
        val sliceIntent: Intent =
            Intent(Constants.ACTION_SET_SLICE).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra(Constants.EXTRA_METADATA_KEY, key)
                setPackage(Constants.PACKAGE_BTHELPER_ADAPTER)
            }
        sendBroadcast(sliceIntent)
    }

    private fun setRegularPodsMetadata(
        device: BluetoothDevice,
        metadata: RegularPodsMetadata,
    ): Boolean {
        var ret =
            device.setMetadataString(
                BluetoothDevice.METADATA_MANUFACTURER_NAME,
                metadata.manufacturer,
            )
        ret = device.setMetadataString(BluetoothDevice.METADATA_MODEL_NAME, metadata.model)
        ret =
            device.setMetadataValue(
                BluetoothDevice.METADATA_DEVICE_TYPE,
                BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray(),
            )
        ret = device.setMetadataBoolean(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, true)
        ret =
            device.setMetadataInt(
                BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            )
        ret =
            device.setMetadataInt(
                BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            )
        ret =
            device.setMetadataInt(
                BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            )
        ret =
            device.setMetadataInt(
                BluetoothDevice.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            )
        ret =
            setRegularPodsIconMetadata(
                device,
                metadata.drawable,
                metadata.leftDrawable,
                metadata.rightDrawable,
                metadata.caseDrawable,
            )
        return ret
    }

    private fun setRegularPodsIconMetadata(
        device: BluetoothDevice,
        drawable: Int,
        leftDrawable: Int,
        rightDrawable: Int,
        caseDrawable: Int,
    ): Boolean {
        val main = setRegularPodsIcon(device, BluetoothDevice.METADATA_MAIN_ICON, drawable)
        val left = setRegularPodsIcon(device, BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON, leftDrawable)
        val right = setRegularPodsIcon(device, BluetoothDevice.METADATA_UNTETHERED_RIGHT_ICON, rightDrawable)
        val case = setRegularPodsIcon(device, BluetoothDevice.METADATA_UNTETHERED_CASE_ICON, caseDrawable)
        return main && left && right && case
    }

    private fun setRegularPodsIcon(device: BluetoothDevice, key: Int, drawable: Int): Boolean {
        if (drawable != 0) {
            val uri = getIconUri(drawable) ?: return false
            return device.setMetadataUri(key, uri)
        }

        // Leave other providers' artwork alone; an empty value removes our old override.
        val previous = device.getMetadata(key) ?: return true
        if (Uri.parse(previous.decodeToString()).authority != Constants.AUTHORITY_FILE) return true
        return device.setMetadataValue(key, byteArrayOf())
    }

    private fun setSinglePodsMetadata(
        device: BluetoothDevice,
        metadata: SinglePodsMetadata,
    ): Boolean {
        var ret =
            device.setMetadataString(
                BluetoothDevice.METADATA_MANUFACTURER_NAME,
                metadata.manufacturer,
            )
        ret =
            device.setMetadataValue(
                BluetoothDevice.METADATA_DEVICE_TYPE,
                BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray(),
            )
        ret = device.setMetadataBoolean(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, true)
        ret = device.setMetadataString(BluetoothDevice.METADATA_MODEL_NAME, metadata.model)
        ret =
            device.setMetadataInt(
                BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            )
        ret = setSinglePodsIconMetadata(device, metadata.drawable)
        return ret
    }

    private fun setSinglePodsIconMetadata(device: BluetoothDevice, drawable: Int): Boolean {
        var ret = device.setMetadataUri(BluetoothDevice.METADATA_MAIN_ICON, getIconUri(drawable))
        return ret
    }

    override fun onMetadataChanged(device: BluetoothDevice, key: Int, value: ByteArray?) {
        if (currentDevice?.equals(device) != true) return
        if (isSliceSet) {
            restoreInitialMetadata(device, key)
        }
    }

    // Set metadata (icon, battery, charging status, etc.) for current device
    // and send broadcast that device status has changed
    @Synchronized
    private fun updatePodsStatus(device: BluetoothDevice) {
        val model = reportedModel ?: when {
            hasSplitBattery -> genericEarbuds
            hasAacpBattery -> genericHeadphones
            else -> null
        }
        if (!isSliceSet) isSliceSet = setInitialMetadata(device)
        if (model == null) return

        if (publishedModel !== model || publishedAsShort != shortFormat) {
            val manufacturer = if (shortFormat) genericEarbuds.manufacturer else model.manufacturer
            val published = when (model) {
                is RegularPods -> setRegularPodsMetadata(device, RegularPodsMetadata(
                    manufacturer = manufacturer,
                    model = model.model,
                    lowBattThreshold = model.lowBattThreshold,
                    drawable = model.drawable,
                    leftDrawable = model.leftDrawable,
                    rightDrawable = model.rightDrawable,
                    caseDrawable = model.caseDrawable,
                ))
                is SinglePods -> setSinglePodsMetadata(device, SinglePodsMetadata(
                    manufacturer = manufacturer,
                    model = model.model,
                    lowBattThreshold = model.lowBattThreshold,
                    drawable = model.drawable,
                ))
                else -> false
            }
            if (published) {
                publishedModel = model
                publishedAsShort = shortFormat
                getSharedPreferences().setSingleDevice(model.isSingle)
            }
        }

        val batteries = batteryNotif.getBattery()
        val batteryUnified: Int
        val chargingMain: Boolean
        if (!model.isSingle) {
            val (left, right, case) = batteries
            val leftCharging = left.status == BatteryStatus.CHARGING
            val rightCharging = right.status == BatteryStatus.CHARGING
            val caseCharging = case.status == BatteryStatus.CHARGING
            val leftBattery = left.level
            val rightBattery = right.level
            val caseBattery = case.level

            device.setMetadataBoolean(
                BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING,
                leftCharging,
            )
            device.setMetadataBoolean(
                BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING,
                rightCharging,
            )
            device.setMetadataBoolean(
                BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING,
                caseCharging,
            )
            device.setMetadataInt(BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY, leftBattery)
            device.setMetadataInt(BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY, rightBattery)
            device.setMetadataInt(BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY, caseBattery)

            chargingMain = (leftBattery >= 0 || rightBattery >= 0) &&
                (leftBattery < 0 || leftCharging) && (rightBattery < 0 || rightCharging)
            batteryUnified = when {
                leftBattery < 0 -> rightBattery
                rightBattery < 0 -> leftBattery
                else -> minOf(leftBattery, rightBattery)
            }
        } else {
            val headset = batteries[3]
            chargingMain = headset.status == BatteryStatus.CHARGING
            batteryUnified = headset.level
        }

        device.setMetadataBoolean(BluetoothDevice.METADATA_MAIN_CHARGING, chargingMain)
        device.setMetadataInt(BluetoothDevice.METADATA_MAIN_BATTERY, batteryUnified)

        if (batteryUnified >= 0) broadcastHfIndicatorEventIntent(batteryUnified, device)
    }

    // Send broadcasts to Android Settings Intelligence, Bluetooth app, System Settings
    // to reflect current device status changes
    private fun broadcastHfIndicatorEventIntent(batteryUnified: Int, device: BluetoothDevice) {
        // Update battery status for this device
        val intent: Intent =
            Intent(ACTION_HF_INDICATORS_VALUE_CHANGED).apply {
                putExtra(EXTRA_HF_INDICATORS_IND_ID, HF_INDICATOR_BATTERY_LEVEL_STATUS)
                putExtra(EXTRA_HF_INDICATORS_IND_VALUE, batteryUnified)
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            }
        sendBroadcastAsUser(intent, UserHandle.ALL, Manifest.permission.BLUETOOTH_CONNECT)

        // Broadcast battery level changes
        val batteryIntent: Intent =
            Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
                putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
            }
        sendBroadcastAsUser(batteryIntent, UserHandle.ALL, Manifest.permission.BLUETOOTH_CONNECT)

        // Update Android Settings Intelligence's battery widget
        val statusIntent: Intent =
            Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
                setPackage(PACKAGE_ASI)
                putExtra(ACTION_BATTERY_LEVEL_CHANGED, intent)
            }
        sendBroadcastAsUser(statusIntent, UserHandle.ALL)
    }

    companion object {
        const val TAG: String = "PodsService"
    }
}
