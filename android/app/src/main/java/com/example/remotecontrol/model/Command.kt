package com.example.remotecontrol.model

import com.google.gson.JsonObject

data class DeviceCommand(
    val requestId: String,
    val command: String,
    val params: JsonObject? = null
)
