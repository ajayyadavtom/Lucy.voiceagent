package com.lucy.voiceagent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * MainActivity: Control dashboard for Lucy Voice Agent.
 * Handles permissions, service controls, overlay settings, and live status logs.
 */
class MainActivity : AppCompatActivity(), VoiceCommandService.StatusListener {

	private lateinit var statusText: TextView
	private lateinit var logText: TextView
	private lateinit var startButton: Button
	private lateinit var stopButton: Button
	private lateinit var bubbleButton: Button

	private var voiceService: VoiceCommandService? = null
	private var isBound = false

	private val serviceConnection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
			val binder = service as VoiceCommandService.LocalBinder
			voiceService = binder.getService()
			voiceService?.setStatusListener(this@MainActivity)
			isBound = true
			updateUI()
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			voiceService?.setStatusListener(null)
			isBound = false
			voiceService = null
			updateUI()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		if (BuildConfig.DEBUG) {
			Timber.plant(Timber.DebugTree())
		}

		statusText = findViewById(R.id.status_text)
		logText = findViewById(R.id.info_text)
		startButton = findViewById(R.id.start_button)
		stopButton = findViewById(R.id.stop_button)
		bubbleButton = findViewById(R.id.enroll_button)

		startButton.setOnClickListener { startVoiceService() }
		stopButton.setOnClickListener { stopVoiceService() }
		bubbleButton.setOnClickListener { toggleFloatingBubble() }

		checkAndRequestPermissions()
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
			voiceService?.setStatusListener(null)
			unbindService(serviceConnection)
			isBound = false
		}
	}

	override fun onStatusUpdate(statusText: String) {
		runOnUiThread {
			this.statusText.text = statusText
			logText.append("\n• $statusText")
		}
	}

	private fun checkAndRequestPermissions() {
		val permissions = mutableListOf<String>()
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.RECORD_AUDIO)
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) != PackageManager.PERMISSION_GRANTED) {
				permissions.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
			}
		}
		if (permissions.isNotEmpty()) {
			ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
		}

		// Overlay Permission for Floating Bubble
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
			Toast.makeText(this, "Please grant Overlay Permission for Floating Bubble", Toast.LENGTH_LONG).show()
			startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
		}
	}

	private fun startVoiceService() {
		val intent = Intent(this, VoiceCommandService::class.java)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(intent)
		} else {
			startService(intent)
		}
		bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
		Toast.makeText(this, "Lucy Voice Assistant Started", Toast.LENGTH_SHORT).show()
		updateUI()
	}

	private fun stopVoiceService() {
		if (isBound) {
			voiceService?.setStatusListener(null)
			unbindService(serviceConnection)
			isBound = false
		}
		stopService(Intent(this, VoiceCommandService::class.java))
		Toast.makeText(this, "Lucy Voice Assistant Stopped", Toast.LENGTH_SHORT).show()
		statusText.text = "Ready"
		updateUI()
	}

	private fun toggleFloatingBubble() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
			Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
			startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
			return
		}
		startService(Intent(this, FloatingBubbleService::class.java))
		Toast.makeText(this, "Floating Bubble Activated", Toast.LENGTH_SHORT).show()
	}

	private fun updateUI() {
		val running = isServiceRunning(VoiceCommandService::class.java)
		startButton.isEnabled = !running
		stopButton.isEnabled = running
		if (!running) {
			statusText.text = "Ready"
		}
	}

	private fun isServiceRunning(serviceClass: Class<*>): Boolean {
		val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
		return am.getRunningServices(Integer.MAX_VALUE).any { it.service.className == serviceClass.name }
	}
}