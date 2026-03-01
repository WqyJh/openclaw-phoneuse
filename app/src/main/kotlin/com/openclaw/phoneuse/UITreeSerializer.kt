package com.openclaw.phoneuse

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes the Android accessibility UI tree into JSON for LLM analysis.
 * Produces a compact representation suitable for AI agent decision-making.
 */
object UITreeSerializer {

    /**
     * Serialize the full UI tree starting from root.
     * @param root The root AccessibilityNodeInfo
     * @param maxDepth Maximum traversal depth (default 15)
     * @return JSON representation of the UI tree
     */
    fun serialize(root: AccessibilityNodeInfo?, maxDepth: Int = 15): JSONObject {
        if (root == null) {
            return JSONObject().put("error", "No UI tree available")
        }

        return JSONObject().apply {
            put("package", root.packageName?.toString() ?: "unknown")
            put("tree", serializeNode(root, 0, maxDepth))
            put("timestamp", System.currentTimeMillis())
        }
    }

    /**
     * Flatten the UI tree into a list of interactive elements.
     * More useful for LLM agents than the full tree.
     */
    fun serializeInteractiveElements(root: AccessibilityNodeInfo?): JSONArray {
        val elements = JSONArray()
        if (root == null) return elements
        collectInteractive(root, elements, 0)
        return elements
    }

    private fun serializeNode(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): JSONObject {
        val obj = JSONObject()

        // Basic info
        obj.put("class", node.className?.toString()?.substringAfterLast('.') ?: "")
        
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) obj.put("text", text)

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrEmpty()) obj.put("desc", desc)

        val resId = node.viewIdResourceName?.toString()
        if (!resId.isNullOrEmpty()) obj.put("id", resId)

        // Bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        obj.put("bounds", JSONObject()
            .put("left", bounds.left)
            .put("top", bounds.top)
            .put("right", bounds.right)
            .put("bottom", bounds.bottom))

        // State flags (only include if true for compactness)
        if (node.isClickable) obj.put("clickable", true)
        if (node.isLongClickable) obj.put("longClickable", true)
        if (node.isEditable) obj.put("editable", true)
        if (node.isScrollable) obj.put("scrollable", true)
        if (node.isCheckable) obj.put("checkable", true)
        if (node.isChecked) obj.put("checked", true)
        if (node.isFocusable) obj.put("focusable", true)
        if (node.isFocused) obj.put("focused", true)
        if (node.isSelected) obj.put("selected", true)
        if (node.isEnabled) obj.put("enabled", true)

        // Children
        if (depth < maxDepth && node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                children.put(serializeNode(child, depth + 1, maxDepth))
                child.recycle()
            }
            if (children.length() > 0) {
                obj.put("children", children)
            }
        }

        return obj
    }

    private fun collectInteractive(
        node: AccessibilityNodeInfo,
        elements: JSONArray,
        index: Int
    ): Int {
        var idx = index

        val isInteractive = node.isClickable || node.isLongClickable || 
                           node.isEditable || node.isScrollable || node.isCheckable

        if (isInteractive && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val centerX = (bounds.left + bounds.right) / 2
            val centerY = (bounds.top + bounds.bottom) / 2

            val elem = JSONObject()
            elem.put("index", idx)
            elem.put("type", node.className?.toString()?.substringAfterLast('.') ?: "View")
            
            val text = node.text?.toString()
            if (!text.isNullOrEmpty()) elem.put("text", text)
            
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrEmpty()) elem.put("desc", desc)

            val resId = node.viewIdResourceName?.toString()
            if (!resId.isNullOrEmpty()) elem.put("id", resId)

            elem.put("center", JSONObject().put("x", centerX).put("y", centerY))
            elem.put("bounds", JSONObject()
                .put("l", bounds.left).put("t", bounds.top)
                .put("r", bounds.right).put("b", bounds.bottom))

            val actions = mutableListOf<String>()
            if (node.isClickable) actions.add("click")
            if (node.isLongClickable) actions.add("longClick")
            if (node.isEditable) actions.add("edit")
            if (node.isScrollable) actions.add("scroll")
            if (node.isCheckable) actions.add("check")
            elem.put("actions", JSONArray(actions))

            elements.put(elem)
            idx++
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            idx = collectInteractive(child, elements, idx)
            child.recycle()
        }

        return idx
    }
}
