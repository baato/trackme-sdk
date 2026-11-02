package io.baato.trackme

import platform.UIKit.UIDevice

internal actual fun platformName(): String =
    "${UIDevice.currentDevice.systemName()} ${UIDevice.currentDevice.systemVersion}"
