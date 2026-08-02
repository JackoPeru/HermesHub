package com.nemoclaw.chat.jarvis.meta

import androidx.activity.ComponentActivity

internal interface MetaWearablesSetupBridge {
    fun initialize(activity: ComponentActivity, onStatus: (String) -> Unit)
    fun startRegistration(activity: ComponentActivity)
    fun requestCameraPermission()
    fun openDatGlassesAppUpdate(activity: ComponentActivity)
    fun enableMockPhoneCamera(activity: ComponentActivity)
    fun close()
}
