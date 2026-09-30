package org.dattapool.capture.input

enum class CaptureTriggerSource(val sourceName: String) {
    SCREEN("screen"),
    GEAR_VR("gear_vr"),
    HARDWARE_BUTTON("hardware_button"),
    EXTERNAL_CONTROLLER("external_controller")
}
