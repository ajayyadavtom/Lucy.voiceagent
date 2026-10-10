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
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Main Activity: Manages runtime permissions (RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE,
 * and SYSTEM_ALERT_WINDOW overlay permission for Android 10+), displays status updates,
 * and binds to VoiceCommandService.
 */
class MainActivity : AppCompatActivity(), VoiceCommandService.StatusListener {

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
			voiceService?.setStatusListener(this@MainActivity)
			isBound = true
			Log.d("LucyVoice", "Service connected in MainActivity")
			updateUI()
		}

		override fun onServiceDisconnected(name: ComponentName?) {
			voiceService?.setStatusListener(null)
			isBound = false
			voiceService = null
			Log.d("LucyVoice", "Service disconnected in MainActivity")
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
		startButton = findViewById(R.id.start_button)
		stopButton = findViewById(R.id.stop_button)
		enrollButton = findViewById(R.id.enroll_button)

		startButton.setOnClickListener { startVoiceService() }
		stopButton.setOnClickListener { stopVoiceService() }
		enrollButton.setOnClickListener {
			Toast.makeText(this, "Speaker Enrollment is ready in background settings", Toast.LENGTH_SHORT).show()
		}

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
		}
	}

	private fun checkAndRequestPermissions() {
		val permissionsToRequest = mutableListOf<String>()

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) != PackageManager.PERMISSION_GRANTED) {
				permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
			}
		}

		if (permissionsToRequest.isNotEmpty()) {
			ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
		}

		// Check Android 10+ Overlay Permission (SYSTEM_ALERT_WINDOW) for background app launches
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
			Toast.makeText(this, "Please allow 'Display Over Other Apps' so Lucy can open apps from background.", Toast.LENGTH_LONG).show()
			val intent = Intent(
				Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
				Uri.parse("package:$packageName")
			)
			startActivity(intent)
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

	override fun onRequestPermissionsResult(
		requestCode: Int,
		permissions: Array<out String>,
		grantResults: IntArray
	) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			Log.d("LucyVoice", "Microphone permission granted")
			updateUI()
		} else {
			Toast.makeText(this, "Microphone permission is required for Lucy Voice Assistant", Toast.LENGTH_LONG).show()
		}
	}
}
