package com.example.remotecontrol.model

data class A11yNode(
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val bounds: Bounds?,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isChecked: Boolean,
    val children: List<A11yNode>
)

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)
