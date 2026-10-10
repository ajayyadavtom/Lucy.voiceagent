package com.lucy.voiceagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

	private const val RECORD_AUDIO_CODE = 1001
	private const val FOREGROUND_SERVICE_CODE = 1002

	fun hasRecordAudioPermission(context: Context): Boolean {
		return ContextCompat.checkSelfPermission(
			context,
			Manifest.permission.RECORD_AUDIO
		) == PackageManager.PERMISSION_GRANTED
	}

	fun hasForegroundServicePermission(context: Context): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.FOREGROUND_SERVICE
			) == PackageManager.PERMISSION_GRANTED
		} else {
			true // Not required on older APIs
		}
	}

	fun requestRecordAudioPermission(activity: Activity) {
		ActivityCompat.requestPermissions(
			activity,
			arrayOf(Manifest.permission.RECORD_AUDIO),
			RECORD_AUDIO_CODE
		)
	}

	fun requestForegroundServicePermission(activity: Activity) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			ActivityCompat.requestPermissions(
				activity,
				arrayOf(Manifest.permission.FOREGROUND_SERVICE),
				FOREGROUND_SERVICE_CODE
			)
		}
	}

	fun requestAllPermissions(activity: Activity) {
		val permissionsToRequest = mutableListOf<String>()

		if (!hasRecordAudioPermission(activity)) {
			permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
		}

		if (!hasForegroundServicePermission(activity)) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE)
			}
		}

		if (permissionsToRequest.isNotEmpty()) {
			ActivityCompat.requestPermissions(
				activity,
				permissionsToRequest.toTypedArray(),
				RECORD_AUDIO_CODE
			)
		}
	}
}
