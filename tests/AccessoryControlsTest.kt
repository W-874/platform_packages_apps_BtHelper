/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.android.bluetooth.bthelper.pods

import android.content.SharedPreferences
import com.android.bluetooth.bthelper.pods.AccessoryControls.Control
import com.android.bluetooth.bthelper.utils.AACPManager
import com.android.bluetooth.bthelper.utils.AACPManager.ControlCommandIdentifiers as Wire
import java.lang.reflect.Proxy

private inline fun <reified T> proxy(crossinline invoke: (T, String, Array<out Any?>) -> Any?): T =
    checkNotNull(T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { obj, method, args ->
        invoke(checkNotNull(T::class.java.cast(obj)), method.name, args ?: emptyArray())
    }))

private fun storage(): SharedPreferences {
    val saved = mutableMapOf<String, Any?>()
    return proxy<SharedPreferences> { _, name, args ->
        when (name) {
            "getInt", "getBoolean" -> saved.getOrDefault(args[0] as String, args[1])
            "edit" -> proxy<SharedPreferences.Editor> { editor, method, parameters ->
                when (method) {
                    "putInt" -> editor.also { saved[parameters[0] as String] = parameters[1] }
                    "remove" -> editor.also { saved.remove(parameters[0] as String) }
                    "apply" -> null
                    else -> error(method)
                }
            }
            else -> error(name)
        }
    }
}

private fun report(session: Long, wire: Wire, vararg values: Byte) {
    val packet = AACPManager.createDataPacket(AACPManager.createControlCommandPacket(wire.value, values))
    AccessoryControls.received(session, checkNotNull(AACPManager.ControlCommand.fromByteArray(packet)))
}

fun main() {
    val a = "AA:BB:CC:DD:EE:01"
    val b = "AA:BB:CC:DD:EE:02"
    val preferences = storage()
    val timers = ArrayDeque<Runnable>()
    val writes = mutableListOf<Pair<Control, List<Byte>>>()
    val repo = AccessoryControls
    fun snapshot() = repo.state.value
    fun begin(address: String) = repo.begin(address, preferences, false) { timer, _ -> timers.addLast(timer) }
    var allowWrites = true
    fun connect(address: String): Long = checkNotNull(repo.link(address) { control, value ->
        if (!allowWrites) false else {
            writes += control to checkNotNull(repo.encode(snapshot(), control, value)).toList()
            true
        }
    })

    begin(a)
    val first = connect(a)
    check(!snapshot().supports(Control.LISTENING_MODE))
    report(first, Wire.LISTENING_MODE_CONFIGS, 0x07)
    check(snapshot().supportsMode(2) && !snapshot().supportsMode(4))
    check(snapshot().value(Control.LISTENING_MODE) == null)
    check(!repo.request(b, first, Control.LISTENING_MODE, 2))
    check(!repo.request(a, first, Control.LISTENING_MODE, 4))
    check(repo.request(a, first, Control.LISTENING_MODE, 2))
    check(snapshot().pending(Control.LISTENING_MODE) == 2 && snapshot().value(Control.LISTENING_MODE) == null)
    check(!repo.request(a, first, Control.LISTENING_MODE, 3))
    report(first, Wire.LISTENING_MODE, 3)
    check(snapshot().pending(Control.LISTENING_MODE) == null && snapshot().value(Control.LISTENING_MODE) == 3)
    check(repo.request(a, first, Control.LISTENING_MODE, 2))
    timers.removeFirst().run()
    check(snapshot().pending(Control.LISTENING_MODE) == 2)
    timers.removeFirst().run()
    check(snapshot().pending(Control.LISTENING_MODE) == null && snapshot().value(Control.LISTENING_MODE) == 3)
    report(first, Wire.ALLOW_OFF_OPTION, 2)
    check(!snapshot().supportsMode(1) && !repo.request(a, first, Control.LISTENING_MODE_CYCLE, 7))

    report(first, Wire.DOUBLE_CLICK_INTERVAL, 0)
    check(snapshot().value(Control.PRESS_SPEED) == 0)
    check(repo.request(a, first, Control.PRESS_SPEED, 2) && writes.last().second == listOf<Byte>(2))
    report(first, Wire.DOUBLE_CLICK_INTERVAL, 3)
    check(snapshot().value(Control.PRESS_SPEED) == 0 && snapshot().pending(Control.PRESS_SPEED) == 2)
    report(first, Wire.CLICK_HOLD_MODE, 5, 1)
    check(snapshot().value(Control.LEFT_PRESS_HOLD_ACTION) == 0 && snapshot().value(Control.RIGHT_PRESS_HOLD_ACTION) == 1)
    check(repo.request(a, first, Control.LEFT_PRESS_HOLD_ACTION, 1) && writes.last().second == listOf<Byte>(5, 5))
    check(!repo.request(a, first, Control.RIGHT_PRESS_HOLD_ACTION, 0))
    allowWrites = false
    check(!repo.request(a, first, Control.LISTENING_MODE, 2) && snapshot().pending(Control.LISTENING_MODE) == null)
    allowWrites = true

    repo.observed(first, AccessoryControls.WEAR)
    check(repo.request(a, first, Control.AUTOMATIC_EAR_DETECTION, 0))
    repo.link(a, null)
    check(!snapshot().connected && snapshot().pending(Control.PRESS_SPEED) == null)
    val second = connect(a)
    report(first, Wire.LISTENING_MODE, 2)
    check(!repo.request(a, first, Control.LISTENING_MODE, 2))
    check(snapshot().value(Control.LISTENING_MODE) == null && snapshot().supportsMode(2))
    check(snapshot().value(Control.AUTOMATIC_EAR_DETECTION) == 0)

    begin(b)
    val third = connect(b)
    check(!snapshot().supportsMode(2) && snapshot().value(Control.AUTOMATIC_EAR_DETECTION) == 1)
    report(second, Wire.LISTENING_MODE, 2)
    check(snapshot().value(Control.LISTENING_MODE) == null && third != second)
    repo.forget(a, preferences)
    check(snapshot().address == b && snapshot().connected)
    begin(a)
    connect(a)
    check(!snapshot().supportsMode(2) && snapshot().value(Control.AUTOMATIC_EAR_DETECTION) == 1)
    println("Accessory control state regressions passed")
}
