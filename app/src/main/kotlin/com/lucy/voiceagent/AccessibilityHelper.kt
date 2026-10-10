package com.lucy.voiceagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

/**
 * Accessibility Service for optional UI automation.
 * Provides programmatic tapping, swiping, and text input capabilities.
 * Disabled by default; enable via Settings -> Accessibility if UI automation is needed.
 */
class AccessibilityHelper : AccessibilityService() {

	override fun onAccessibilityEvent(event: AccessibilityEvent) {
		// Log accessibility events for debugging
		Timber.d("Accessibility event: ${event.eventType}")
	}

	override fun onInterrupt() {
		Timber.d("Accessibility service interrupted")
	}

	/**
	 * Simulate a tap at coordinates (x, y).
	 */
	fun tap(x: Int, y: Int) {
		val gesture = android.accessibilityservice.GestureDescription.Builder()
			.addStroke(
				android.accessibilityservice.GestureDescription.StrokeDescription(
					getPath(x, y, x, y),
					0,
					50
				)
			)
			.build()

		dispatchGesture(gesture, null, null)
		Timber.d("Tap performed at ($x, $y)")
	}

	/**
	 * Simulate a swipe from (x1, y1) to (x2, y2).
	 */
	fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 100) {
		val gesture = android.accessibilityservice.GestureDescription.Builder()
			.addStroke(
				android.accessibilityservice.GestureDescription.StrokeDescription(
					getPath(x1, y1, x2, y2),
					0,
					duration
				)
			)
			.build()

		dispatchGesture(gesture, null, null)
		Timber.d("Swipe performed from ($x1, $y1) to ($x2, $y2)")
	}

	/**
	 * Simulate a long press at (x, y).
	 */
	fun longPress(x: Int, y: Int, duration: Long = 500) {
		val gesture = android.accessibilityservice.GestureDescription.Builder()
			.addStroke(
				android.accessibilityservice.GestureDescription.StrokeDescription(
					getPath(x, y, x, y),
					0,
					duration
				)
			)
			.build()

		dispatchGesture(gesture, null, null)
		Timber.d("Long press performed at ($x, $y)")
	}

	/**
	 * Helper to create a path for gesture.
	 */
	private fun getPath(x1: Int, y1: Int, x2: Int, y2: Int): android.graphics.Path {
		val path = android.graphics.Path()
		path.moveTo(x1.toFloat(), y1.toFloat())
		path.lineTo(x2.toFloat(), y2.toFloat())
		return path
	}
}
