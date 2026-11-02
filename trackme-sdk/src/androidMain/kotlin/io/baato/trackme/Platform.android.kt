package io.baato.trackme

import android.os.Build

internal actual fun platformName(): String = "Android ${Build.VERSION.RELEASE}"
