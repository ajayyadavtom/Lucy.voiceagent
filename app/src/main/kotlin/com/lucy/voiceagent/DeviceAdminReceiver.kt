package com.lucy.voiceagent

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Device Admin Receiver for executing device-level actions.
 * Requires the app to be set as device owner via adb:
 *   adb shell dpm set-device-owner com.lucy.voiceagent/.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)
		Timber.d("DeviceAdminReceiver received: ${intent.action}")
	}

	companion object {
		/**
		 * Shutdown device (requires device owner).
		 */
		fun shutdown(context: Context) {
			val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
			val admin = ComponentName(context, DeviceAdminReceiver::class.java)

			if (dpm.isAdminActive(admin)) {
				try {
					dpm.reboot(admin)
					Timber.i("Device reboot initiated")
				} catch (e: Exception) {
					Timber.e(e, "Failed to initiate device shutdown")
				}
			} else {
				Timber.w("App is not set as device owner. Cannot execute shutdown.")
			}
		}

		/**
		 * Reboot device (requires device owner).
		 */
		fun reboot(context: Context) {
			val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
			val admin = ComponentName(context, DeviceAdminReceiver::class.java)

			if (dpm.isAdminActive(admin)) {
				try {
					// Note: rebootIntoRecovery is the primary shutdown method.
					// For a regular reboot without entering recovery, use Runtime.exec("reboot")
					// but that requires root access.
					dpm.reboot(admin)
					Timber.i("Device reboot initiated")
				} catch (e: Exception) {
					Timber.e(e, "Failed to initiate device reboot")
				}
			} else {
				Timber.w("App is not set as device owner. Cannot execute reboot.")
			}
		}

		/**
		 * Lock device screen (requires device owner).
		 */
		fun lockDevice(context: Context) {
			val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
			val admin = ComponentName(context, DeviceAdminReceiver::class.java)

			if (dpm.isAdminActive(admin)) {
				try {
					dpm.lockNow()
					Timber.i("Device locked")
				} catch (e: Exception) {
					Timber.e(e, "Failed to lock device")
				}
			} else {
				Timber.w("App is not set as device owner. Cannot lock device.")
			}
		}

		/**
		 * Check if app is active as device admin.
		 */
		fun isDeviceAdminActive(context: Context): Boolean {
			val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
			val admin = ComponentName(context, DeviceAdminReceiver::class.java)
			return dpm.isAdminActive(admin)
		}
	}
}
