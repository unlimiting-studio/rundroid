package com.example.remotecontrol.command

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.example.remotecontrol.model.DeviceCommand
import com.example.remotecontrol.service.RemoteControlAccessibilityService
import com.example.remotecontrol.service.ScreenCaptureService
import com.example.remotecontrol.util.A11yTreeUtil
import com.example.remotecontrol.util.PackageUtil
import com.example.remotecontrol.websocket.WebSocketManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommandRouter(
    private val context: Context,
    private val webSocketManager: WebSocketManager
) {
    companion object {
        private const val TAG = "CommandRouter"
    }

    private val gson = Gson()
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val handlers: Map<String, (DeviceCommand) -> Unit> = mapOf(
        "screenshot" to ::handleScreenshot,
        "a11y-tree" to ::handleA11yTree,
        "action/tap" to ::handleActionTap,
        "action/tap-a11y" to ::handleActionTapA11y,
        "action/swipe" to ::handleActionSwipe,
        "action/back" to ::handleActionBack,
        "action/home" to ::handleActionHome,
        "action/recent" to ::handleActionRecent,
        "action/type" to ::handleActionType,
        "action/key" to ::handleActionKey,
        "action/clear-input" to ::handleActionClearInput,
        "app/install" to ::handleAppInstall,
        "app/list" to ::handleAppList,
        "app/launch" to ::handleAppLaunch,
        "app/stop" to ::handleAppStop,
        "app/uninstall" to ::handleAppUninstall
    )

    fun destroy() {
        commandScope.cancel()
    }

    fun route(command: DeviceCommand) {
        commandScope.launch {
            val handler = handlers[command.command]
            if (handler == null) {
                Log.w(TAG, "Unknown command: ${command.command}")
                sendNotImplemented(command.requestId, command.command)
            } else {
                handler(command)
            }
        }
    }

    private fun handleScreenshot(command: DeviceCommand) {
        ScreenCaptureService.takeScreenshot { pngBytes ->
            if (pngBytes == null) {
                sendErrorResponse(command.requestId, "Screenshot failed")
                return@takeScreenshot
            }
            webSocketManager.sendBinary(command.requestId, pngBytes)
        }
    }

    private fun handleA11yTree(command: DeviceCommand) {
        val service = requireA11yService(command.requestId) ?: return
        val rootNode = service.getA11yTree()
        val tree = A11yTreeUtil.buildTree(rootNode)

        if (tree == null) {
            sendErrorResponse(command.requestId, "No accessibility tree available")
            return
        }

        val jsonTree = gson.toJsonTree(tree)
        sendJsonResponse(command.requestId, true, jsonTree)
    }

    private fun handleActionTap(command: DeviceCommand) {
        val x = getFloatParam(command.params, "x")
        val y = getFloatParam(command.params, "y")

        if (x == null || y == null) {
            sendErrorResponse(command.requestId, "Missing x or y parameter")
            return
        }

        val service = requireA11yService(command.requestId) ?: return
        service.performTap(x, y, createGestureCallback(command.requestId))
    }

    private fun handleActionTapA11y(command: DeviceCommand) {
        val path = getStringParam(command.params, "path")
        val text = getStringParam(command.params, "text")

        if (path == null && text == null) {
            sendErrorResponse(command.requestId, "Missing path or text parameter")
            return
        }

        val service = requireA11yService(command.requestId) ?: return
        val node = when {
            path != null -> service.findNodeByPath(path)
            text != null -> service.findNodeByText(text)
            else -> null
        }

        if (node == null) {
            sendErrorResponse(command.requestId, "Node not found")
            return
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        service.performTap(
            bounds.exactCenterX(),
            bounds.exactCenterY(),
            createGestureCallback(command.requestId)
        )
    }

    private fun handleActionSwipe(command: DeviceCommand) {
        val startX = getFloatParam(command.params, "startX")
        val startY = getFloatParam(command.params, "startY")
        val endX = getFloatParam(command.params, "endX")
        val endY = getFloatParam(command.params, "endY")
        val duration = getLongParam(command.params, "duration") ?: 300L

        if (startX == null || startY == null || endX == null || endY == null) {
            sendErrorResponse(
                command.requestId,
                "Missing startX, startY, endX, or endY parameter"
            )
            return
        }

        val service = requireA11yService(command.requestId) ?: return
        service.performSwipe(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration,
            callback = createGestureCallback(command.requestId)
        )
    }

    private fun handleActionBack(command: DeviceCommand) {
        val service = requireA11yService(command.requestId) ?: return
        val result = service.performBack()
        sendJsonResponse(command.requestId, result, performedData(result))
    }

    private fun handleActionHome(command: DeviceCommand) {
        val service = requireA11yService(command.requestId) ?: return
        val result = service.performHome()
        sendJsonResponse(command.requestId, result, performedData(result))
    }

    private fun handleActionRecent(command: DeviceCommand) {
        val service = requireA11yService(command.requestId) ?: return
        val result = service.performRecent()
        sendJsonResponse(command.requestId, result, performedData(result))
    }

    private fun handleActionType(command: DeviceCommand) {
        val text = getStringParam(command.params, "text")
        if (text == null) {
            sendErrorResponse(command.requestId, "Missing text parameter")
            return
        }

        val service = requireA11yService(command.requestId) ?: return
        val result = service.typeText(text)
        sendJsonResponse(command.requestId, result, performedData(result))
    }

    private fun handleActionKey(command: DeviceCommand) {
        val keyCode = getIntParam(command.params, "keyCode")
        if (keyCode == null) {
            sendErrorResponse(command.requestId, "Missing keyCode parameter")
            return
        }

        val service = requireA11yService(command.requestId) ?: return
        commandScope.launch {
            val result = service.performKeyEvent(keyCode)
            val data = JsonObject().apply {
                addProperty("performed", result)
                addProperty("note", "requires ADB or shell permissions for full support")
            }
            sendJsonResponse(command.requestId, result, data)
        }
    }

    private fun handleActionClearInput(command: DeviceCommand) {
        val service = requireA11yService(command.requestId) ?: return
        val result = service.clearInput()
        sendJsonResponse(command.requestId, result, performedData(result))
    }

    private fun handleAppInstall(command: DeviceCommand) {
        val url = getStringParam(command.params, "url")
        if (url == null) {
            sendErrorResponse(command.requestId, "Missing url parameter")
            return
        }

        commandScope.launch {
            val result = withContext(Dispatchers.IO) {
                PackageUtil.installApkFromUrl(context, url)
            }
            sendJsonResponse(command.requestId, result, performedData(result))
        }
    }

    private fun handleAppList(command: DeviceCommand) {
        val apps = PackageUtil.getInstalledApps(context)
        val data = gson.toJsonTree(apps)
        sendJsonResponse(command.requestId, true, data)
    }

    private fun handleAppLaunch(command: DeviceCommand) {
        val packageName = getStringParam(command.params, "packageName")
        if (packageName == null) {
            sendErrorResponse(command.requestId, "Missing packageName parameter")
            return
        }

        val result = PackageUtil.launchApp(context, packageName)
        sendJsonResponse(command.requestId, result, performedData(result))
    }

    private fun handleAppStop(command: DeviceCommand) {
        val packageName = getStringParam(command.params, "packageName")
        if (packageName == null) {
            sendErrorResponse(command.requestId, "Missing packageName parameter")
            return
        }

        val result = PackageUtil.stopApp(context, packageName)
        val data = JsonObject().apply {
            addProperty("performed", result)
            addProperty("note", "background processes killed, full force-stop requires ADB")
        }
        sendJsonResponse(command.requestId, result, data)
    }

    private fun handleAppUninstall(command: DeviceCommand) {
        val packageName = getStringParam(command.params, "packageName")
        if (packageName == null) {
            sendErrorResponse(command.requestId, "Missing packageName parameter")
            return
        }

        val result = PackageUtil.uninstallApp(context, packageName)
        sendJsonResponse(command.requestId, result, performedData(result))
    }

    private fun sendJsonResponse(requestId: String, success: Boolean, data: JsonElement?) {
        val response = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("success", success)
            if (data != null) {
                add("data", data)
            }
        }
        webSocketManager.sendText(gson.toJson(response))
    }

    private fun sendErrorResponse(requestId: String, errorMessage: String) {
        val response = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("success", false)
            addProperty("error", errorMessage)
        }
        webSocketManager.sendText(gson.toJson(response))
    }

    private fun sendNotImplemented(requestId: String, command: String) {
        sendErrorResponse(requestId, "Command not implemented: $command")
    }

    private fun getA11yService(): RemoteControlAccessibilityService? {
        return RemoteControlAccessibilityService.instance
    }

    private fun requireA11yService(requestId: String): RemoteControlAccessibilityService? {
        val service = getA11yService()
        if (service == null) {
            sendErrorResponse(requestId, "Accessibility service not running")
        }
        return service
    }

    private fun createGestureCallback(requestId: String): GestureResultCallback {
        return object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                sendJsonResponse(requestId, true, performedData(true))
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                sendErrorResponse(requestId, "Gesture cancelled")
            }
        }
    }

    private fun performedData(performed: Boolean): JsonObject {
        return JsonObject().apply {
            addProperty("performed", performed)
        }
    }

    private fun getFloatParam(params: JsonObject?, key: String): Float? {
        return params?.get(key)
            ?.takeUnless { it.isJsonNull }
            ?.let { runCatching { it.asFloat }.getOrNull() }
    }

    private fun getIntParam(params: JsonObject?, key: String): Int? {
        return params?.get(key)
            ?.takeUnless { it.isJsonNull }
            ?.let { runCatching { it.asInt }.getOrNull() }
    }

    private fun getLongParam(params: JsonObject?, key: String): Long? {
        return params?.get(key)
            ?.takeUnless { it.isJsonNull }
            ?.let { runCatching { it.asLong }.getOrNull() }
    }

    private fun getStringParam(params: JsonObject?, key: String): String? {
        return params?.get(key)
            ?.takeUnless { it.isJsonNull }
            ?.let { runCatching { it.asString }.getOrNull() }
    }
}
