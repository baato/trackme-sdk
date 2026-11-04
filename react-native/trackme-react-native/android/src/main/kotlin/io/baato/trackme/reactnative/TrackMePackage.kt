package io.baato.trackme.reactnative

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** Registers [TrackMeModule] (autolinked by React Native and Expo). */
class TrackMePackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == TrackMeModule.NAME) TrackMeModule(reactContext) else null

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(
            // name, className, canOverrideExistingModule, needsEagerInit, isCxxModule, isTurboModule
            TrackMeModule.NAME to ReactModuleInfo(TrackMeModule.NAME, TrackMeModule.NAME, false, false, false, true),
        )
    }
}
