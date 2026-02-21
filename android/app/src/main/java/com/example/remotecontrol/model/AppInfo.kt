package com.example.remotecontrol.model

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystemApp: Boolean
)
