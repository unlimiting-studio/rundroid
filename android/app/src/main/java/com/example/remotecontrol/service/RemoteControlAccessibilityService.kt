package com.example.remotecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RemoteControlAccessibilityService : AccessibilityService() {
    private data class PathSegment(
        val className: String,
        val text: String?,
        val desc: String?,
        val id: String?,
        val index: Int?
    )

    companion object {
        private const val TAG = "RemoteControlA11y"
        var instance: RemoteControlAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 이벤트 처리는 작업 3에서 구현
    }

    fun getA11yTree(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    fun findNodeByPath(path: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val segments = (splitPathSegments(path) ?: return null)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { parsePathSegment(it) ?: return null }

        if (segments.isEmpty()) {
            return null
        }

        return findNodeBySegments(listOf(root), segments, 0)
    }

    private fun splitPathSegments(path: String): List<String>? {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0

        for (char in path) {
            when (char) {
                '[' -> {
                    bracketDepth += 1
                    current.append(char)
                }

                ']' -> {
                    if (bracketDepth == 0) {
                        return null
                    }
                    bracketDepth -= 1
                    current.append(char)
                }

                '/' -> {
                    if (bracketDepth == 0) {
                        val segment = current.toString().trim()
                        if (segment.isNotEmpty()) {
                            segments.add(segment)
                        }
                        current.setLength(0)
                    } else {
                        current.append(char)
                    }
                }

                else -> current.append(char)
            }
        }

        if (bracketDepth != 0) {
            return null
        }

        val tail = current.toString().trim()
        if (tail.isNotEmpty()) {
            segments.add(tail)
        }
        return segments
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val results = root.findAccessibilityNodeInfosByText(text)
        if (results.isNullOrEmpty()) return null
        val first = results[0]
        for (i in 1 until results.size) {
            results[i].recycle()
        }
        return first
    }

    fun performTap(x: Float, y: Float, callback: GestureResultCallback?) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, callback, null)
    }

    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
        callback: GestureResultCallback?
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, callback, null)
    }

    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performRecent(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun typeText(text: String): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            focusedNode.recycle()
        }
    }

    fun clearInput(): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        try {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            focusedNode.recycle()
        }
    }

    suspend fun performKeyEvent(keyCode: Int): Boolean {
        if (keyCode !in 0..999) return false
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
                val exitCode = process.waitFor()
                exitCode == 0
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun findNodeBySegments(
        candidates: List<AccessibilityNodeInfo>,
        segments: List<PathSegment>,
        segmentIndex: Int
    ): AccessibilityNodeInfo? {
        if (segmentIndex !in segments.indices) {
            return null
        }

        val segment = segments[segmentIndex]
        val matched = candidates.filter { matchesSegment(it, segment) }
        val selectedNode = segment.index?.let { index ->
            matched.getOrNull(index)
        } ?: matched.firstOrNull()

        // Recycle unmatched candidates (those not selected)
        candidates.forEach { candidate ->
            if (candidate !== selectedNode) {
                candidate.recycle()
            }
        }

        if (selectedNode == null) {
            return null
        }

        if (segmentIndex == segments.lastIndex) {
            return selectedNode
        }

        val childCandidates = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until selectedNode.childCount) {
            val child = selectedNode.getChild(i) ?: continue
            childCandidates.add(child)
        }
        selectedNode.recycle()

        return findNodeBySegments(childCandidates, segments, segmentIndex + 1)
    }

    private fun parsePathSegment(segment: String): PathSegment? {
        val className = segment.substringBefore("[").trim()
        if (className.isEmpty()) {
            return null
        }

        var text: String? = null
        var desc: String? = null
        var id: String? = null
        var index: Int? = null

        val matcher = Regex("\\[(\\w+)=([^\\]]+)]")
        matcher.findAll(segment).forEach { match ->
            val value = match.groupValues[2].trim()
            when (match.groupValues[1]) {
                "text" -> text = value
                "desc" -> desc = value
                "id" -> id = value
                "index" -> index = value.toIntOrNull()
            }
        }

        return PathSegment(
            className = className,
            text = text,
            desc = desc,
            id = id,
            index = index
        )
    }

    private fun matchesSegment(node: AccessibilityNodeInfo, segment: PathSegment): Boolean {
        if (node.className?.toString() != segment.className) {
            return false
        }
        if (segment.text != null && node.text?.toString() != segment.text) {
            return false
        }
        if (segment.desc != null && node.contentDescription?.toString() != segment.desc) {
            return false
        }
        if (segment.id != null && node.viewIdResourceName != segment.id) {
            return false
        }
        return true
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service destroyed")
    }
}
