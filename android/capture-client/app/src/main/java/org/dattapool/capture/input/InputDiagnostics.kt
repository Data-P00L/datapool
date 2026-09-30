package org.dattapool.capture.input

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

object InputDiagnostics {

    private const val TAG_INPUT = "DATTA_INPUT"
    private const val TAG_USB = "DATTA_USB"

    fun logInputDevices() {
        val ids = InputDevice.getDeviceIds()
        Log.d(TAG_INPUT, "=== ENUMERATING ANDROID INPUT DEVICES (Total: ${ids.size}) ===")
        for (id in ids) {
            val device = InputDevice.getDevice(id) ?: continue
            Log.d(
                TAG_INPUT,
                """
                InputDevice:
                  id=${device.id}
                  name=${device.name}
                  descriptor=${device.descriptor}
                  vendorId=${device.vendorId}
                  productId=${device.productId}
                  sources=${device.sources} (0x${Integer.toHexString(device.sources)})
                  keyboardType=${device.keyboardType}
                  isExternal=${device.isExternal}
                  isVirtual=${device.isVirtual}
                """.trimIndent()
            )
        }
        Log.d(TAG_INPUT, "==========================================================")
    }

    fun logUsbDevices(context: Context) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager == null) {
            Log.w(TAG_USB, "UsbManager not available")
            return
        }
        val deviceList = usbManager.deviceList
        Log.d(TAG_USB, "=== ENUMERATING USB DEVICES (Total: ${deviceList.size}) ===")
        for ((_, device) in deviceList) {
            Log.d(
                TAG_USB,
                """
                USB Device:
                  deviceName=${device.deviceName}
                  vendorId=${device.vendorId} (0x${Integer.toHexString(device.vendorId)})
                  productId=${device.productId} (0x${Integer.toHexString(device.productId)})
                  deviceClass=${device.deviceClass}
                  deviceSubclass=${device.deviceSubclass}
                  interfaceCount=${device.interfaceCount}
                  manufacturerName=${device.manufacturerName}
                  productName=${device.productName}
                """.trimIndent()
            )
        }
        Log.d(TAG_USB, "==========================================================")
    }

    fun logKeyEvent(event: KeyEvent) {
        Log.d(
            TAG_INPUT,
            "KeyEvent action=${event.action} " +
            "keyCode=${event.keyCode} " +
            "scanCode=${event.scanCode} " +
            "deviceId=${event.deviceId} " +
            "source=${event.source} " +
            "repeat=${event.repeatCount}"
        )
    }

    fun logMotionEvent(tag: String, event: MotionEvent) {
        Log.d(
            TAG_INPUT,
            "$tag action=${event.actionMasked} " +
            "deviceId=${event.deviceId} " +
            "source=${event.source} " +
            "x=${event.x} " +
            "y=${event.y} " +
            "buttonState=${event.buttonState}"
        )
    }
}
