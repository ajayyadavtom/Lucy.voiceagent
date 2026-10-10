package com.lucy.voiceagent

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Main Activity: UI for starting the voice service, enrolling speaker, and managing permissions.
 */
class MainActivity : AppCompatActivity() {

	private lateinit var statusText: TextView
	private lateinit var startButton: Button
	private lateinit var stopButton: Button
	private lateinit var enrollButton: Button

	private lateinit var speakerVerifier: SpeakerVerifier
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

		speakerVerifier = SpeakerVerifier(this)

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
		Toast.makeText(this, "Lucy Voice Agent started", Toast.LENGTH_SHORT).show()
		updateUI()
	}

	/**
	 * Stop the VoiceCommandService.
	 */
	private fun stopVoiceService() {
		stopService(Intent(this, VoiceCommandService::class.java))
		Toast.makeText(this, "Lucy Voice Agent stopped", Toast.LENGTH_SHORT).show()
		updateUI()
	}

	/**
	 * Enroll speaker voice profile through a 3-utterance recording dialog.
	 */
	private fun enrollSpeaker() {
		if (!PermissionHelper.hasRecordAudioPermission(this)) {
			PermissionHelper.requestRecordAudioPermission(this)
			return
		}

		val utteranceEmbeddings = mutableListOf<FloatArray>()
		var currentStep = 1

		val dialogBuilder = AlertDialog.Builder(this)
		dialogBuilder.setTitle("Enroll Voice for Lucy")
		dialogBuilder.setMessage("Step $currentStep of 3: Say 'Hey Lucy' clearly into your microphone when you tap Record.")

		dialogBuilder.setPositiveButton("Record Sample $currentStep", null)
		dialogBuilder.setNegativeButton("Cancel") { dialog, _ ->
			dialog.dismiss()
		}

		val dialog = dialogBuilder.create()
		dialog.show()

		val recordButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
		recordButton.setOnClickListener {
			recordButton.isEnabled = false
			dialog.setMessage("Recording... Say 'Hey Lucy' now!")

			recordAudioUtterance { floatSamples ->
				lifecycleScope.launch {
					val embedding = speakerVerifier.extractEmbedding(floatSamples)
					utteranceEmbeddings.add(embedding)

					currentStep++
					if (currentStep <= 3) {
						dialog.setMessage("Step $currentStep of 3: Great! Say 'Hey Lucy' again.")
						recordButton.text = "Record Sample $currentStep"
						recordButton.isEnabled = true
					} else {
						dialog.setMessage("Saving voice embeddings...")
						val success = speakerVerifier.enrollSpeaker(utteranceEmbeddings)
						dialog.dismiss()
						if (success) {
							Toast.makeText(this@MainActivity, "Voice enrollment successful! Lucy now recognizes your voice.", Toast.LENGTH_LONG).show()
							updateUI()
						} else {
							Toast.makeText(this@MainActivity, "Enrollment failed. Please try again.", Toast.LENGTH_SHORT).show()
						}
					}
				}
			}
		}
	}

	/**
	 * Record 2 seconds of audio from microphone and return FloatArray PCM samples.
	 */
	@SuppressLint("MissingPermission")
	private fun recordAudioUtterance(onComplete: (FloatArray) -> Unit) {
		val sampleRate = 16000
		val channelConfig = AudioFormat.CHANNEL_IN_MONO
		val audioFormat = AudioFormat.ENCODING_PCM_16BIT
		val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
		val bufferSize = maxOf(minBufferSize, sampleRate * 2 * 2)

		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
			return
		}

		val audioRecord = AudioRecord(
			MediaRecorder.AudioSource.MIC,
			sampleRate,
			channelConfig,
			audioFormat,
			bufferSize
		)

		val totalSamples = sampleRate * 2 // 2 seconds
		val audioBuffer = ShortArray(totalSamples)
		audioRecord.startRecording()

		lifecycleScope.launch(Dispatchers.IO) {
			var readCount = 0
			while (readCount < totalSamples) {
				val read = audioRecord.read(audioBuffer, readCount, totalSamples - readCount)
				if (read > 0) {
					readCount += read
				} else {
					break
				}
			}
			try {
				audioRecord.stop()
			} catch (e: Exception) {
				Timber.e(e, "Error stopping AudioRecord")
			}
			audioRecord.release()

			val floatSamples = FloatArray(readCount) { i -> audioBuffer[i] / 32768.0f }
			withContext(Dispatchers.Main) {
				onComplete(floatSamples)
			}
		}
	}

	/**
	 * Update UI based on service state and enrollment status.
	 */
	private fun updateUI() {
		val serviceRunning = isServiceRunning(VoiceCommandService::class.java)
		statusText.text = if (serviceRunning) {
			"Status: Lucy Voice Agent Running"
		} else {
			"Status: Lucy Voice Agent Stopped"
		}

		startButton.isEnabled = !serviceRunning
		stopButton.isEnabled = serviceRunning

		if (::speakerVerifier.isInitialized && speakerVerifier.isEnrolled()) {
			enrollButton.text = "Re-enroll Speaker (Enrolled ✓)"
		} else {
			enrollButton.text = "Enroll Speaker"
		}
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
		if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			Timber.i("Microphone permission granted")
			updateUI()
		} else {
			Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
			Timber.w("Microphone permission denied")
		}
	}
}
