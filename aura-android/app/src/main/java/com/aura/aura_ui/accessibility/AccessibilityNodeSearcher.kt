package com.aura.aura_ui.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityNodeSearcher {

    fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        if ((node.isEditable || className.contains("EditText") || className.contains("AutoCompleteTextView")) && !className.contains("WebView")) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
            @Suppress("DEPRECATION")
            child.recycle()
        }

        return null
    }

    fun findEditableNodeAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        val isEditable = node.isEditable || className.contains("EditText") || className.contains("AutoCompleteTextView")
        if (isEditable && !className.contains("WebView")) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y)) return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeAtPoint(child, x, y)
            if (result != null) return result
            @Suppress("DEPRECATION")
            child.recycle()
        }

        return null
    }

    fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString().orEmpty()
        val isWebView = className.contains("WebView")
        val isEditable = node.isEditable || className.contains("EditText") || className.contains("AutoCompleteTextView")
        if (isEditable && !isWebView && node.isFocused) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableNode(child)
            if (result != null) return result
            @Suppress("DEPRECATION")
            child.recycle()
        }

        return null
    }
}
