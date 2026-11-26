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
import android.bluetooth.BluetoothSocket
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
import android.os.ParcelUuid
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
import com.android.bluetooth.bthelper.pods.AccessoryControls.Control
import com.android.bluetooth.bthelper.utils.A2dpReceiver
import com.android.bluetooth.bthelper.utils.AACPManager
import com.android.bluetooth.bthelper.utils.AACPManager.StemPressType
import com.android.bluetooth.bthelper.utils.AirPodsNotifications
import com.android.bluetooth.bthelper.utils.AirPodsParser
import com.android.bluetooth.bthelper.utils.BLEManager
import com.android.bluetooth.bthelper.utils.BatteryStatus
import com.android.bluetooth.bthelper.utils.BluetoothConnectionManager
import com.android.bluetooth.bthelper.utils.BluetoothSocketManager
import com.android.bluetooth.bthelper.utils.CameraStateMonitor
import com.android.bluetooth.bthelper.utils.DeviceIdentificationSdp
import com.android.bluetooth.bthelper.utils.DeviceInformationGatt
import com.android.bluetooth.bthelper.utils.GestureDetector
import com.android.bluetooth.bthelper.utils.HeadTracking
import com.android.bluetooth.bthelper.utils.KeyStorageManager
import com.android.bluetooth.bthelper.utils.MediaController
import com.android.bluetooth.bthelper.utils.PodsBatteryMetadata
import com.android.bluetooth.bthelper.utils.PodsA2dpListener
import com.android.bluetooth.bthelper.utils.PodsUuidListener
import com.android.bluetooth.bthelper.utils.StemAction
import com.android.bluetooth.bthelper.utils.UuidReceiver
import com.android.bluetooth.bthelper.utils.isHeadTrackingData
import com.android.bluetooth.bthelper.utils.models.AirPodsPro2Clone
import com.android.bluetooth.bthelper.utils.models.IPods
import com.android.bluetooth.bthelper.utils.models.RegularPods
import com.android.bluetooth.bthelper.utils.models.RegularPodsMetadata
import com.android.bluetooth.bthelper.utils.models.SinglePods
import com.android.bluetooth.bthelper.utils.models.SinglePodsMetadata
import com.android.bluetooth.bthelper.utils.publishDeviceSettingsDiscovery
import com.android.bluetooth.bthelper.utils.setBatteryThresholdMetadata
import com.android.bluetooth.bthelper.utils.setMetadataBoolean
import com.android.bluetooth.bthelper.utils.setMetadataString
import com.android.bluetooth.bthelper.utils.setPodsIconMetadata
import com.android.bluetooth.bthelper.utils.setMetadataValue
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintWriter
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
    private val availableDevices = linkedMapOf<String, BluetoothDevice>()
    private var activeSocket: BluetoothSocket? = null
    private var activeSession = 0L
    private var reconnectJob: Job? = null
    private var discoveryPublished = false
    private val batteryMetadata = PodsBatteryMetadata()

    private var currentDevice: BluetoothDevice? = null

    @Volatile private var isSliceSet = false
    private var publishedModel: IPods? = null
    private var reportedModel: IPods? = null
    private var accessoryModel: IPods? = null
    private var sdpModel: IPods? = null
    private var gattModel: IPods? = null
    private var deviceInformationGatt: DeviceInformationGatt? = null
    private val sdpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DeviceIdentificationSdp.ACTION_RECORD ||
                intent.getParcelableExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java) !=
                    DeviceIdentificationSdp.uuid
            ) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return
            acceptSdpRecord(device, intent)
        }
    }
    private var hasSplitBattery = false
    private var batterySourceSocket: BluetoothSocket? = null
    private var airohaService = false
    private val airohaEarbuds = object : RegularPods() {
        override val model = "AirPods-compatible earbuds (Airoha)"
    }
    @Volatile private var hasVerifiedModel = false
    private val hasStandardModel: Boolean
        get() = profileModel != null && BluetoothConnectionManager.isConnected
    @Volatile private var profileModel: IPods? = null
    private val genericEarbuds = RegularPods()
    private val genericHeadphones = SinglePods()
    private var publishedAsVerified = false

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

    private var earDetectNotif = AirPodsNotifications.EarDetection()
    private var batteryNotif = AirPodsNotifications.BatteryNotification()
    private var conversAwareNotif = AirPodsNotifications.ConversationalAwarenessNotification()
    val handler = Handler(Looper.getMainLooper())

    @Volatile var isHeadTrackingActive = false

    private var headTrackingUsesAlternatePackets = false
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
        if (!status.shortFormat && status.model != null && !hasVerifiedModel) {
            reportedModel = status.model
            hasVerifiedModel = true
        } else {
            status.model?.let { reportedModel = it }
        }
        hasSplitBattery = hasSplitBattery || status.model?.isSingle == false ||
            status.leftBattery >= 0 || status.rightBattery >= 0 || status.caseBattery >= 0
        if (batterySourceSocket == null || batterySourceSocket !== BluetoothConnectionManager.currentSocket) {
            batteryNotif.setBatteryDirect(
                leftLevel = status.leftBattery,
                leftCharging = status.isLeftCharging,
                rightLevel = status.rightBattery,
                rightCharging = status.isRightCharging,
                caseLevel = status.caseBattery,
                caseCharging = status.isCaseCharging,
            )
        }
        updatePodsStatus(device, publishBattery = true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @Synchronized
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        val source = when {
            hasVerifiedModel -> "verified BLE"
            sdpModel != null -> "SDP DIP"
            accessoryModel != null -> "AACP device information"
            gattModel != null -> "GATT DIS"
            reportedModel != null -> "identity-bound short BLE"
            publishedModel === airohaEarbuds -> "paired service UUIDs + AACP split battery"
            else -> "unknown"
        }
        val controls = AccessoryControls.state.value
        writer.println("Model: ${publishedModel?.model} (source=$source, Airoha service=$airohaService)")
        writer.println("AACP connected=${controls.connected}, battery=${batterySourceSocket != null}, declared=${controls.declared}, learned=${controls.learned}, mode=${controls.mode}, verified BLE=$hasVerifiedModel")
    }

    private fun handleCallStateChange(state: Int) {
        incomingCallJob?.cancel()

        incomingCallJob =
            serviceScope.launch(Dispatchers.Main) {
                delay(100)
                handleIncomingCall(state)
            }
    }

    private fun handleIncomingCall(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (AccessoryControls.state.value.supports(Control.HEAD_GESTURES) &&
                    AccessoryControls.state.value.value(Control.HEAD_GESTURES) == 1 && startHeadTracking()
                ) {
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
        val socket = BluetoothConnectionManager.currentSocket ?: return
        if (socket.remoteDevice != device) return
        if (!batteryNotif.setBattery(batteryInfo)) return
        batterySourceSocket = socket
        hasSplitBattery = hasSplitBattery || batteryNotif.hasSplitBattery
        updatePodsStatus(device, publishBattery = true)

        // Battery reports are not evidence that this firmware supports audio routing.
        if (!hasStandardModel) return
        val isHeadset = profileModel?.isSingle == true

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

    private fun observeControl(feature: Int): Boolean {
        AccessoryControls.observed(activeSession, feature)
        return AccessoryControls.state.value.allows(feature)
    }

    override fun onEarDetectionReceived(earDetection: ByteArray) {
        processEarDetectionChange(earDetection)
    }

    override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
        if (!conversAwareNotif.isConversationalAwarenessData(conversationAwareness)) return
        if (!observeControl(AccessoryControls.CONVERSATION_EVENTS)) return
        conversAwareNotif.setData(conversationAwareness)
        if (conversAwareNotif.status == 1.toByte() || conversAwareNotif.status == 2.toByte()) {
            val state = AccessoryControls.state.value
            MediaController.startSpeaking(
                pause = state.value(Control.PAUSE_MUSIC_WHEN_SPEAKING) == 1,
                relative = state.value(Control.RELATIVE_CONVERSATION_VOLUME) == 1,
                percent = state.value(Control.CONVERSATION_VOLUME) ?: Control.CONVERSATION_VOLUME.default,
            )
        } else if (
            conversAwareNotif.status == 8.toByte() || conversAwareNotif.status == 9.toByte()
        ) {
            MediaController.stopSpeaking()
        }
    }

    override fun onControlCommandReceived(controlCommand: ByteArray) {
        val command = AACPManager.ControlCommand.fromByteArray(controlCommand) ?: return
        AccessoryControls.received(activeSession, command)
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

    override fun onShowNearbyUI(sender: String) = Unit

    override fun onHeadTrackingReceived(headTracking: ByteArray) {
        if (isHeadTrackingData(headTracking)) {
            observeControl(AccessoryControls.HEAD_GESTURE_REPORTS)
        }
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

    @Synchronized
    override fun onUnknownPacketReceived(packet: ByteArray) {
        val device = currentDevice ?: return
        if (hasVerifiedModel || accessoryModel != null) return
        accessoryModel = AirPodsParser.parseDeviceInformation(packet) ?: return
        updatePodsStatus(device)
    }

    @Synchronized
    private fun acceptSdpRecord(device: BluetoothDevice, intent: Intent) {
        if (currentDevice != device || device.bondState != BluetoothDevice.BOND_BONDED ||
            hasVerifiedModel || sdpModel != null
        ) return
        val model = DeviceIdentificationSdp.model(intent)
        if (model == null) {
            maybeProbeGatt(device)
            return
        }
        sdpModel = model
        deviceInformationGatt?.close()
        updatePodsStatus(device)
    }

    @Synchronized
    private fun maybeProbeGatt(device: BluetoothDevice) {
        if (deviceInformationGatt != null || hasVerifiedModel || sdpModel != null || accessoryModel != null) return
        val probe = DeviceInformationGatt(this, handler, ::acceptGattModel)
        deviceInformationGatt = probe
        probe.probe(device)
    }

    @Synchronized
    private fun acceptGattModel(device: BluetoothDevice, model: IPods) {
        if (currentDevice != device || device.bondState != BluetoothDevice.BOND_BONDED ||
            hasVerifiedModel || sdpModel != null || accessoryModel != null
        ) return
        gattModel = model
        updatePodsStatus(device)
    }

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
                cycleListeningMode()
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
        val firstReport = AccessoryControls.state.value.sessionReports and AccessoryControls.WEAR == 0
        if (!observeControl(AccessoryControls.WEAR)) return
        val previous = earDetectNotif.status.count { it == 0.toByte() }
        val worn = (if (earDetection[6] == 0.toByte()) 1 else 0) + (if (earDetection[7] == 0.toByte()) 1 else 0)
        earDetectNotif.setStatus(earDetection)
        if (AccessoryControls.state.value.value(Control.AUTOMATIC_EAR_DETECTION) != 1 || previous == worn || (firstReport && !hasStandardModel)) return
        if (previous == 0 && worn > 0) {
            connectAudio()
            registerA2dpConnectionReceiver()
            if (MediaController.getMusicActive() == true) {
                MediaController.userPlayedTheMedia = true
            }
        } else if (worn == 0) {
            MediaController.sendPause(force = true)
            if (AccessoryControls.state.value.value(Control.DISCONNECT_WHEN_NOT_WEARING) == 1) {
                disconnectAudio()
            }
        }

        if (previous == 2 || worn == 2) MediaController.userPlayedTheMedia = false
        if (worn == 2 && previous != 0) {
            MediaController.sendPlay()
            MediaController.userPausedTheMedia = false
        } else if (worn == 1) {
            MediaController.sendPause()
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
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    availableDevices.clear()
                    detachDevice()
                    stopSelf()
                }
            }
        }
    }

    private fun cycleListeningMode() {
        val state = AccessoryControls.state.value
        val address = state.address ?: return
        val mode = (1..4).asSequence().map { (state.mode + it - 1) % 4 + 1 }
            .firstOrNull(state::supportsMode) ?: return
        AccessoryControls.request(address, state.session, Control.LISTENING_MODE, mode)
    }

    private fun writeControl(socket: BluetoothSocket, device: BluetoothDevice, control: Control, value: Int): Boolean {
        if (activeSocket !== socket || BluetoothConnectionManager.currentSocket !== socket ||
            currentDevice != device || device.bondState != BluetoothDevice.BOND_BONDED ||
            bluetoothAdapter?.isEnabled != true
        ) return false
        if (control == Control.LOW_LATENCY_AUDIO) return device.setLowLatencyAudioAllowed(value == 1)
        val wire = control.wire ?: return true // Other host controls are applied from the shared state.
        val payload = AccessoryControls.encode(AccessoryControls.state.value, control, value) ?: return false
        val packet = AACPManager.createDataPacket(AACPManager.createControlCommandPacket(wire.value, payload))
        if (!AACPManager.sendPacket(packet, socket)) return false
        AACPManager.sendPacket(AACPManager.createDataPacket(AACPManager.createRequestNotificationPacket()), socket)
        return true
    }

    private fun onAacpLink(socket: BluetoothSocket, connected: Boolean) {
        val device = currentDevice ?: return
        if (socket.remoteDevice != device) return
        if (connected) {
            if (BluetoothConnectionManager.currentSocket !== socket || device.bondState != BluetoothDevice.BOND_BONDED) return
            reconnectJob?.cancel()
            activeSocket = socket
            activeSession = AccessoryControls.link(device.address) { control, value -> writeControl(socket, device, control, value) } ?: return
            val state = AccessoryControls.state.value
            if (state.supports(Control.LOW_LATENCY_AUDIO)) {
                device.setLowLatencyAudioAllowed(state.value(Control.LOW_LATENCY_AUDIO) == 1)
            }
            setupStemActions()
        } else if (activeSocket === socket) {
            activeSocket = null
            activeSession = 0L
            AccessoryControls.link(device.address, null)
            AACPManager.disconnected()
            batterySourceSocket = null
            isHeadTrackingActive = false
            gestureDetector?.stopDetection()
            MediaController.stopSpeaking(immediate = true)
            earDetectNotif = AirPodsNotifications.EarDetection()
            val manager = bluetoothSocketManager ?: return
            reconnectJob = serviceScope.launch(Dispatchers.Main) {
                delay(750)
                if (bluetoothSocketManager === manager && bluetoothAdapter?.isEnabled == true && device.isConnected) {
                    manager.connectToSocket(device)
                }
            }
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


        registerReceiver(adapterStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_EXPORTED)
        registerReceiver(shutdownReceiver, shutdownFilter, RECEIVER_NOT_EXPORTED)
        registerReceiver(sdpReceiver, IntentFilter(DeviceIdentificationSdp.ACTION_RECORD), RECEIVER_EXPORTED)


        cameraStateMonitor = CameraStateMonitor(this)
        cameraStateMonitor?.onCreate()
        cameraStateMonitor?.registerListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val device =
            intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                ?: return START_NOT_STICKY

        when (intent.action) {
            Constants.ACTION_CONNECTED -> {
                if (device.bondState != BluetoothDevice.BOND_BONDED || bluetoothAdapter?.isEnabled != true) return START_NOT_STICKY
                availableDevices[device.address] = device
                if (currentDevice == null) attachDevice(device)
                else if (currentDevice == device) bluetoothSocketManager?.connectToSocket(device)
            }
            Constants.ACTION_DISCONNECTED -> {
                availableDevices.remove(device.address)
                if (currentDevice == device) {
                    detachDevice()
                    val replacement = availableDevices.values.firstOrNull { it.bondState == BluetoothDevice.BOND_BONDED && it.isConnected }
                    if (replacement == null) stopSelfResult(startId) else attachDevice(replacement)
                }
            }
            Constants.ACTION_NAME_CHANGED -> if (currentDevice == device && hasStandardModel) {
                setName(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME))
            }
        }
        return START_NOT_STICKY
    }

    private fun attachDevice(device: BluetoothDevice) {
        currentDevice = device
        val services = device.uuids
        airohaService = services != null && Constants.PODS_UUID in services && AIROHA_SERVICE in services
        AccessoryControls.begin(device.address, getSharedPreferences(), isLowLatencySupported()) { timer, delay ->
            handler.postDelayed(timer, delay)
        }
        updatePodsStatus(device)
        bluetoothAdapter?.addOnMetadataChangedListener(device, mainExecutor, this)
        bluetoothSocketManager = BluetoothSocketManager(
            serviceScope,
            { socket, connected -> handler.post { onAacpLink(socket, connected) } },
            { socket, packet ->
                handler.post {
                    if (activeSocket === socket && BluetoothConnectionManager.currentSocket === socket &&
                        currentDevice?.bondState == BluetoothDevice.BOND_BONDED
                    ) AACPManager.receivePacket(packet)
                }
            },
        )
        bleManager = BLEManager(this, keyStorageManager!!).also { it.setAirPodsStatusListener(this) }
        bluetoothSocketManager?.connectToSocket(device)
        connect()
        bleManager?.startScanning(device)
        if (!DeviceIdentificationSdp.request(device)) maybeProbeGatt(device)
    }

    private fun detachDevice() {
        val device = currentDevice ?: return
        currentDevice = null
        AccessoryControls.end(device.address)
        activeSocket = null
        activeSession = 0L
        reconnectJob?.cancel()
        reconnectJob = null
        bluetoothSocketManager?.disconnect()
        bluetoothSocketManager = null
        AACPManager.disconnected()
        bleManager?.stopScanning()
        bleManager = null
        bluetoothAdapter?.removeOnMetadataChangedListener(device, this)
        deviceInformationGatt?.close()
        deviceInformationGatt = null
        uuidReceiver?.onDestroy()
        a2dpReceiver?.onDestroy()
        incomingCallJob?.cancel()
        isHeadTrackingActive = false
        gestureDetector?.stopDetection()
        MediaController.stopSpeaking(immediate = true)
        discoveryPublished = false
        isSliceSet = false
        publishedModel = null
        reportedModel = null
        accessoryModel = null
        sdpModel = null
        gattModel = null
        profileModel = null
        hasVerifiedModel = false
        publishedAsVerified = false
        hasSplitBattery = false
        batterySourceSocket = null
        disconnectedBecauseReversed = false
        earDetectNotif = AirPodsNotifications.EarDetection()
        batteryNotif = AirPodsNotifications.BatteryNotification()
        conversAwareNotif = AirPodsNotifications.ConversationalAwarenessNotification()
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
        val socketManager = bluetoothSocketManager ?: return

        bluetoothAdapter.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP && bluetoothSocketManager === socketManager &&
                        device in proxy.connectedDevices
                    ) socketManager.connectToSocket(device)
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            },
            BluetoothProfile.A2DP,
        )
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
        if (!AccessoryControls.state.value.hasStemConfig()) return
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
        try {
            device.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting audio profiles", e)
        }
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
        detachDevice()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)

        cameraStateMonitor?.unregisterListener(this)
        cameraStateMonitor?.onDestroy()
        cameraStateMonitor = null

        unregisterReceiver(shutdownReceiver)
        unregisterReceiver(adapterStateReceiver)
        AACPManager.setPacketCallback(null)
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(sdpReceiver)
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
        telecomManager = null
        bluetoothAdapter = null
        uuidReceiver?.onDestroy()
        uuidReceiver = null
        a2dpReceiver?.onDestroy()
        a2dpReceiver = null
        keyStorageManager = null

        serviceScope.cancel()

        super.onDestroy()
    }

    private fun startHeadTracking(): Boolean {
        val state = AccessoryControls.state.value
        if (!state.supports(Control.HEAD_GESTURES)) return false
        if (isHeadTrackingActive) return true
        val socket = activeSocket ?: return false
        val alternate = state.value(Control.ALTERNATE_HEAD_TRACKING_PACKETS) == 1
        val packet = AACPManager.createDataPacket(AACPManager.createStartHeadTrackingPacket(alternate))
        if (!AACPManager.sendPacket(packet, socket)) return false
        headTrackingUsesAlternatePackets = alternate
        isHeadTrackingActive = true
        HeadTracking.reset()
        return true
    }

    private fun stopHeadTracking() {
        if (!isHeadTrackingActive) return
        isHeadTrackingActive = false
        val socket = activeSocket ?: return
        val packet = AACPManager.createDataPacket(AACPManager.createStopHeadTrackingPacket(headTrackingUsesAlternatePackets))
        AACPManager.sendPacket(packet, socket)
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
            BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI -> {
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
        ret = device.setMetadataString(BluetoothDevice.METADATA_SOFTWARE_VERSION, COMPANION_TYPE_NONE) && ret
        sendSliceSetupIntent(device, BluetoothDevice.METADATA_ENHANCED_SETTINGS_UI_URI)

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
        ret = device.setMetadataString(BluetoothDevice.METADATA_MODEL_NAME, metadata.model) && ret
        ret =
            device.setMetadataValue(
                BluetoothDevice.METADATA_DEVICE_TYPE,
                BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray(),
            ) && ret
        ret = device.setMetadataBoolean(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, true) && ret
        ret =
            device.setBatteryThresholdMetadata(
                BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            ) && ret
        ret =
            device.setBatteryThresholdMetadata(
                BluetoothDevice.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            ) && ret
        ret =
            device.setBatteryThresholdMetadata(
                BluetoothDevice.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            ) && ret
        ret =
            device.setBatteryThresholdMetadata(
                BluetoothDevice.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            ) && ret
        ret =
            setRegularPodsIconMetadata(
                device,
                metadata.drawable,
                metadata.leftDrawable,
                metadata.rightDrawable,
                metadata.caseDrawable,
            ) && ret
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
        val uri = if (drawable == 0) null else getIconUri(drawable) ?: return false
        return device.setPodsIconMetadata(key, uri)
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
                BluetoothDevice.DEVICE_TYPE_HEADSET.toByteArray(),
            ) && ret
        ret = device.setMetadataBoolean(BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET, false) && ret
        ret = device.setMetadataString(BluetoothDevice.METADATA_MODEL_NAME, metadata.model) && ret
        ret =
            device.setBatteryThresholdMetadata(
                BluetoothDevice.METADATA_MAIN_LOW_BATTERY_THRESHOLD,
                metadata.lowBattThreshold,
            ) && ret
        return setRegularPodsIconMetadata(device, metadata.drawable, 0, 0, 0) && ret
    }

    override fun onMetadataChanged(device: BluetoothDevice, key: Int, value: ByteArray?) {
        if (currentDevice != device) return
        if (key == Constants.METADATA_FAST_PAIR_CUSTOMIZED_FIELDS && profileModel != null) {
            discoveryPublished = device.publishDeviceSettingsDiscovery()
            return
        }
        if (isSliceSet) {
            restoreInitialMetadata(device, key)
        }
    }

    // Set metadata (icon, battery, charging status, etc.) for current device
    // and send broadcast that device status has changed
    @Synchronized
    private fun updatePodsStatus(device: BluetoothDevice, publishBattery: Boolean = false) {
        val model = if (hasVerifiedModel) {
            reportedModel
        } else {
            sdpModel ?: accessoryModel ?: gattModel ?: reportedModel
        }
        if (model !== profileModel) {
            profileModel = model
            AccessoryControls.declare(device.address, AccessoryControls.profile(model))
            if (hasStandardModel) setupStemActions()
        }
        val presentation = model ?: when {
            airohaService && batterySourceSocket != null && hasSplitBattery -> airohaEarbuds
            hasSplitBattery -> genericEarbuds
            batterySourceSocket != null -> genericHeadphones
            // Remove artwork left by the retired manual override when no model is known.
            publishedModel == null && device.getMetadata(BluetoothDevice.METADATA_MODEL_NAME)
                ?.decodeToString() == "AirPods Pro 2 Compatible" -> genericEarbuds
            else -> null
        }
        if (!isSliceSet) isSliceSet = setInitialMetadata(device)
        if (model != null && !discoveryPublished) discoveryPublished = device.publishDeviceSettingsDiscovery()
        if (presentation == null) return

        val verified = hasVerifiedModel && !airohaService
        if (publishedModel !== presentation || publishedAsVerified != verified) {
            val manufacturer = if (verified) presentation.manufacturer else genericEarbuds.manufacturer
            val published = when (presentation) {
                is RegularPods -> setRegularPodsMetadata(device, RegularPodsMetadata(
                    manufacturer = manufacturer,
                    model = if (airohaService && model != null && presentation !is AirPodsPro2Clone) {
                        "${presentation.model} (compatible)"
                    } else presentation.model,
                    lowBattThreshold = presentation.lowBattThreshold,
                    drawable = presentation.drawable,
                    leftDrawable = presentation.leftDrawable,
                    rightDrawable = presentation.rightDrawable,
                    caseDrawable = presentation.caseDrawable,
                ))
                is SinglePods -> setSinglePodsMetadata(device, SinglePodsMetadata(
                    manufacturer = manufacturer,
                    model = presentation.model,
                    lowBattThreshold = presentation.lowBattThreshold,
                    drawable = if (model == null) 0 else presentation.drawable,
                ))
                else -> false
            }
            if (published) {
                publishedModel = presentation
                publishedAsVerified = verified
                getSharedPreferences().setSingleDevice(presentation.isSingle)
            }
        }
        if (!publishBattery) return

        val batteries = batteryNotif.getBattery()
        val splitBattery = if (batterySourceSocket != null && batterySourceSocket === BluetoothConnectionManager.currentSocket) {
            batteryNotif.hasSplitBattery
        } else {
            model?.let { !it.isSingle } ?: hasSplitBattery
        }
        val batteryUnified = batteryMetadata.publish(device, batteries, splitBattery)
        if (batteryUnified in 0..100) broadcastHfIndicatorEventIntent(batteryUnified, device)
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
        private val AIROHA_SERVICE = ParcelUuid.fromString("00000000-0000-0000-0099-aabbccddeeff")
        const val TAG: String = "PodsService"
    }
}
