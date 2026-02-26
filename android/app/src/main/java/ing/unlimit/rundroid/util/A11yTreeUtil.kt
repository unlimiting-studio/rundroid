package ing.unlimit.rundroid.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import ing.unlimit.rundroid.model.A11yNode
import ing.unlimit.rundroid.model.Bounds

object A11yTreeUtil {
    fun buildTree(rootNode: AccessibilityNodeInfo?): A11yNode? {
        return rootNode?.let { buildNode(it) }
    }

    private fun buildNode(node: AccessibilityNodeInfo): A11yNode {
        val children = mutableListOf<A11yNode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                children.add(buildNode(child))
            } finally {
                child.recycle()
            }
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = Bounds(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom
        )

        return A11yNode(
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            bounds = bounds,
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            isFocused = node.isFocused,
            isChecked = node.isChecked,
            children = children
        )
    }
}
