package com.lucy.voiceagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * MainActivity: Dashboard for granting permissions (RECORD_AUDIO, SYSTEM_ALERT_WINDOW, READ_CONTACTS, CALL_PHONE),
 * starting FloatingBubbleService, and configuring Gemini API key in SharedPreferences.
 */
class MainActivity : AppCompatActivity() {

	private lateinit var statusText: TextView
	private lateinit var startBubbleButton: Button
	private lateinit var geminiKeyInput: EditText

	private val prefs: SharedPreferences by lazy {
		getSharedPreferences("lucy_prefs", Context.MODE_PRIVATE)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		if (BuildConfig.DEBUG) {
			Timber.plant(Timber.DebugTree())
		}

		statusText = findViewById(R.id.status_text)
		startBubbleButton = findViewById(R.id.start_button)

		try {
			geminiKeyInput = findViewById(R.id.info_text)
		} catch (e: Exception) {
			// fallback
		}

		startBubbleButton.text = "Start Floating Bubble"
		startBubbleButton.setOnClickListener { startFloatingBubble() }

		checkAndRequestPermissions()
	}

	private fun checkAndRequestPermissions() {
		val permissionsToRequest = mutableListOf<String>()

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
		}
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
			permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
		}
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
			permissionsToRequest.add(Manifest.permission.CALL_PHONE)
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) != PackageManager.PERMISSION_GRANTED) {
				permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
			}
		}

		if (permissionsToRequest.isNotEmpty()) {
			ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
		}

		// Overlay Permission for Floating Bubble
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
			Toast.makeText(this, "Please grant Overlay Permission for Floating Bubble", Toast.LENGTH_LONG).show()
			startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
		}
	}

	private fun startFloatingBubble() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
			Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
			startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
			return
		}
		startService(Intent(this, FloatingBubbleService::class.java))
		Toast.makeText(this, "Lucy Floating Bubble Activated", Toast.LENGTH_SHORT).show()
		statusText.text = "Floating Bubble Active"
	}
}
