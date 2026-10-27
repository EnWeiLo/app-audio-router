package com.enweilo.appaudiorouter

import android.media.AudioDeviceInfo

fun deviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手機喇叭"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "聽筒"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有線耳機（含麥克風）"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有線耳機"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "藍牙音訊"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "藍牙通話"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳機"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音訊裝置"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_TELEPHONY -> "電話線路"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "內部混音（Remote Submix）"
    AudioDeviceInfo.TYPE_HEARING_AID -> "助聽器"
    26 -> "藍牙 LE 耳機"
    27 -> "藍牙 LE 喇叭"
    30 -> "藍牙 LE 廣播"
    else -> "其他（類型 $type）"
}
