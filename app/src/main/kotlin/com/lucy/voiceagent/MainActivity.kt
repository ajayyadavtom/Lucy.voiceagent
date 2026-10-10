package com.lucy.voiceagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import timber.log.Timber

/**
 * Main Activity: UI for starting the voice service, enrolling speaker, and managing permissions.
 */
class MainActivity : AppCompatActivity() {

	private lateinit var statusText: TextView
	private lateinit var startButton: Button
	private lateinit var stopButton: Button
	private lateinit var enrollButton: Button

	private var voiceService: VoiceCommandService? = null
	private var isBound = false

	private val serviceConnection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
			val binder = service as VoiceCommandService.LocalBinder
			voiceService = binder.getService()
			isBound = true
			Timber.d("Service connected")
			updateUI()
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			isBound = false
			voiceService = null
			Timber.d("Service disconnected")
			updateUI()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		// Initialize Timber logging
		if (BuildConfig.DEBUG) {
			Timber.plant(Timber.DebugTree())
		}

		// Initialize UI
		statusText = findViewById(R.id.status_text)
		startButton = findViewById(R.id.start_button)
		stopButton = findViewById(R.id.stop_button)
		enrollButton = findViewById(R.id.enroll_button)

		// Set up button listeners
		startButton.setOnClickListener { startVoiceService() }
		stopButton.setOnClickListener { stopVoiceService() }
		enrollButton.setOnClickListener { enrollSpeaker() }

		// Request permissions
		if (!PermissionHelper.hasRecordAudioPermission(this)) {
			Timber.i("Requesting microphone permission")
			PermissionHelper.requestAllPermissions(this)
		}

		updateUI()
	}

	override fun onStart() {
		super.onStart()
		Intent(this, VoiceCommandService::class.java).also { intent ->
			bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
		}
	}

	override fun onStop() {
		super.onStop()
		if (isBound) {
			unbindService(serviceConnection)
			isBound = false
		}
	}

	/**
	 * Start the VoiceCommandService.
	 */
	private fun startVoiceService() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(Intent(this, VoiceCommandService::class.java))
		} else {
			startService(Intent(this, VoiceCommandService::class.java))
		}
		Toast.makeText(this, "Voice agent started", Toast.LENGTH_SHORT).show()
		updateUI()
	}

	/**
	 * Stop the VoiceCommandService.
	 */
	private fun stopVoiceService() {
		stopService(Intent(this, VoiceCommandService::class.java))
		Toast.makeText(this, "Voice agent stopped", Toast.LENGTH_SHORT).show()
		updateUI()
	}

	/**
	 * Enroll speaker (placeholder for full enrollment UI).
	 */
	private fun enrollSpeaker() {
		Toast.makeText(this, "Speaker enrollment will be implemented", Toast.LENGTH_LONG).show()
		Timber.i("Enroll speaker clicked - TODO: implement enrollment UI")

		// TODO: Implement enrollment flow:
		// 1. Record 3-5 utterances (e.g., "voice agent")
		// 2. Extract embeddings via TFLite
		// 3. Store embeddings securely
		// 4. Update SpeakerVerifier.enrollSpeaker()
	}

	/**
	 * Update UI based on service state.
	 */
	private fun updateUI() {
		val serviceRunning = isServiceRunning(VoiceCommandService::class.java)
		statusText.text = if (serviceRunning) {
			"Status: Voice Agent Running"
		} else {
			"Status: Voice Agent Stopped"
		}

		startButton.isEnabled = !serviceRunning
		stopButton.isEnabled = serviceRunning
		enrollButton.isEnabled = true
	}

	/**
	 * Check if a service is running.
	 */
	private fun isServiceRunning(serviceClass: Class<*>): Boolean {
		val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
		return activityManager.getRunningServices(Integer.MAX_VALUE)
			.any { it.service.className == serviceClass.name }
	}

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
			Timber.i("Microphone permission granted")
			updateUI()
		} else {
			Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
			Timber.w("Microphone permission denied")
		}
	}
}
