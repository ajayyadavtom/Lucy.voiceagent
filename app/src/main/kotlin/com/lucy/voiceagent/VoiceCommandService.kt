package com.lucy.voiceagent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Foreground Service for continuous voice listening and command parsing.
 * Extracts the Vosk model from assets/vosk/model-en-us.zip into context.filesDir/model,
 * streams microphone PCM audio via AudioRecord directly into Vosk Recognizer,
 * parses JSON recognition results, detects "lucy", and invokes VoiceCommandExecutor.
 */
class VoiceCommandService : Service() {

	interface StatusListener {
		fun onStatusUpdate(statusText: String)
	}

	private val binder = LocalBinder()
	private var statusListener: StatusListener? = null

	private var recognizer: Recognizer? = null
	private var model: Model? = null
	private var audioRecord: AudioRecord? = null

	@Volatile
	private var isListening = false

	private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
	private val mainHandler = Handler(Looper.getMainLooper())
	private val executor = VoiceCommandExecutor()

	companion object {
		private const val CHANNEL_ID = "VoiceCommandServiceChannel"
		private const val NOTIFICATION_ID = 2001
		private const val SAMPLE_RATE = 16000
	}

	inner class LocalBinder : Binder() {
		fun getService(): VoiceCommandService = this@VoiceCommandService
	}

	override fun onCreate() {
		super.onCreate()
		Timber.d("VoiceCommandService created")
		Log.d("LucyVoice", "VoiceCommandService created")

		LibVosk.setLogLevel(LogLevel.INFO)
		createNotificationChannel()
		startForeground(NOTIFICATION_ID, getNotification("Initializing..."))

		initializePipeline()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == "STOP_SERVICE") {
			stopListening()
			stopSelf()
			return START_NOT_STICKY
		}
		return START_STICKY
	}

	override fun onBind(intent: Intent?): IBinder = binder

	fun setStatusListener(listener: StatusListener?) {
		this.statusListener = listener
	}

	private fun notifyStatus(status: String) {
		Log.d("LucyVoice", "Service Status: $status")
		Timber.d("Service Status: $status")
		mainHandler.post {
			statusListener?.onStatusUpdate(status)
			updateNotification(status)
		}
	}

	private fun createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				CHANNEL_ID,
				"Lucy Voice Assistant Service",
				NotificationManager.IMPORTANCE_LOW
			)
			val manager = getSystemService(NotificationManager::class.java)
			manager?.createNotificationChannel(channel)
		}
	}

	private fun getNotification(text: String): Notification {
		val stopIntent = Intent(this, VoiceCommandService::class.java).setAction("STOP_SERVICE")
		val stopPendingIntent = PendingIntent.getService(
			this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Lucy Voice Agent")
			.setContentText(text)
			.setSmallIcon(android.R.drawable.ic_dialog_info)
			.setOngoing(true)
			.addAction(0, "Stop", stopPendingIntent)
			.build()
	}

	private fun updateNotification(text: String) {
		val manager = getSystemService(NotificationManager::class.java)
		manager?.notify(NOTIFICATION_ID, getNotification(text))
	}

	/**
	 * Background initialization: checks context.filesDir.resolve("model"),
	 * extracts model zip from assets if necessary, and initializes Vosk.
	 */
	private fun initializePipeline() {
		serviceScope.launch(Dispatchers.IO) {
			try {
				val modelDir = filesDir.resolve("model")
				Log.d("LucyVoice", "Checking model directory at: ${modelDir.absolutePath}")

				if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
					notifyStatus("Extracting voice model...")
					Log.d("LucyVoice", "Model dir empty. Starting extraction from assets...")
					extractModelFromAssets(modelDir)
				}

				// Find model root directory (in case zip extracted into a subfolder)
				val actualModelPath = getActualModelDir(modelDir)
				Log.d("LucyVoice", "Initializing Vosk Model with path: ${actualModelPath.absolutePath}")

				model = Model(actualModelPath.absolutePath)
				recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

				Log.d("LucyVoice", "Vosk Model & Recognizer initialized successfully")
				notifyStatus("Listening...")

				startListeningLoop()

			} catch (e: Exception) {
				Log.e("LucyVoice", "Failed to initialize Vosk model pipeline", e)
				Timber.e(e, "Failed to initialize Vosk model pipeline")
				notifyStatus("Model Init Failed: ${e.message}")
			}
		}
	}

	/**
	 * Extracts app/src/main/assets/vosk/model-en-us.zip into context.filesDir/model.
	 */
	private fun extractModelFromAssets(targetDir: File) {
		targetDir.mkdirs()

		val assetPath = "vosk/model-en-us.zip"
		val inputStream: InputStream = try {
			assets.open(assetPath)
		} catch (e: Exception) {
			Log.e("LucyVoice", "Could not open asset at '$assetPath', trying fallback 'model-en-us.zip'", e)
			assets.open("model-en-us.zip")
		}

		ZipInputStream(inputStream).use { zipInput ->
			var entry = zipInput.nextEntry
			while (entry != null) {
				val outFile = File(targetDir, entry.name)
				if (entry.isDirectory) {
					outFile.mkdirs()
				} else {
					outFile.parentFile?.mkdirs()
					outFile.outputStream().use { output ->
						zipInput.copyTo(output)
					}
				}
				zipInput.closeEntry()
				entry = zipInput.nextEntry
			}
		}
		Log.d("LucyVoice", "Extraction complete into: ${targetDir.absolutePath}")
	}

	/**
	 * Helper to locate folder containing Vosk model files (am, conf, etc.)
	 */
	private fun getActualModelDir(baseDir: File): File {
		if (File(baseDir, "conf").exists() || File(baseDir, "am").exists()) {
			return baseDir
		}
		baseDir.listFiles()?.forEach { child ->
			if (child.isDirectory) {
				if (File(child, "conf").exists() || File(child, "am").exists()) {
					return child
				}
			}
		}
		return baseDir
	}

	/**
	 * Continuous AudioRecord read loop streaming mic audio into Vosk Recognizer.
	 */
	@SuppressLint("MissingPermission")
	private fun startListeningLoop() {
		if (isListening) return
		val rec = recognizer ?: return

		isListening = true
		Log.d("LucyVoice", "Starting AudioRecord microphone loop...")

		val minBufferSize = AudioRecord.getMinBufferSize(
			SAMPLE_RATE,
			AudioFormat.CHANNEL_IN_MONO,
			AudioFormat.ENCODING_PCM_16BIT
		)
		val bufferSize = maxOf(minBufferSize, 8192)
		val buffer = ShortArray(4096)

		try {
			val record = AudioRecord(
				MediaRecorder.AudioSource.MIC,
				SAMPLE_RATE,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				bufferSize
			)
			audioRecord = record

			if (record.state != AudioRecord.STATE_INITIALIZED) {
				Log.e("LucyVoice", "AudioRecord initialization failed")
				isListening = false
				notifyStatus("Mic Init Failed")
				return
			}

			record.startRecording()
			Log.d("LucyVoice", "AudioRecord successfully started recording")

			while (isListening && serviceScope.isActive) {
				val readSamples = record.read(buffer, 0, buffer.size)
				if (readSamples > 0) {
					if (rec.acceptWaveForm(buffer, readSamples)) {
						val jsonResult = rec.result
						Log.d("LucyVoice", "Vosk Recognizer Result JSON: $jsonResult")
						processVoskJson(jsonResult)
					}
				}
			}

			try {
				record.stop()
			} catch (e: Exception) {
				Timber.e(e, "Error stopping AudioRecord")
			}
			record.release()
			audioRecord = null

		} catch (e: Exception) {
			Log.e("LucyVoice", "Error in AudioRecord loop", e)
			Timber.e(e, "Error in AudioRecord loop")
		} finally {
			isListening = false
		}
	}

	/**
	 * Parse JSON returned by Vosk recognizer and execute command if wake word "lucy" is detected.
	 */
	private fun processVoskJson(jsonResult: String?) {
		if (jsonResult.isNullOrEmpty()) return

		try {
			val jsonObject = JSONObject(jsonResult)
			val rawText = jsonObject.optString("text", "").lowercase().trim()

			if (rawText.isEmpty()) return

			Log.d("LucyVoice", "Heard text: $rawText")
			notifyStatus("Heard: $rawText")

			if (rawText.contains("lucy")) {
				val index = rawText.indexOf("lucy")
				val command = rawText.substring(index + "lucy".length)
					.trim()
					.removePrefix(",")
					.removePrefix("-")
					.trim()

				Log.d("LucyVoice", "Parsed Command: '$command'")

				mainHandler.post {
					notifyStatus("Executing: $command")
					executor.execute(applicationContext, command)
				}

				mainHandler.postDelayed({
					notifyStatus("Listening...")
				}, 3500)
			}
		} catch (e: Exception) {
			Log.e("LucyVoice", "Failed to parse Vosk result JSON", e)
		}
	}

	private fun stopListening() {
		isListening = false
		try {
			audioRecord?.stop()
			audioRecord?.release()
			audioRecord = null
		} catch (e: Exception) {
			Timber.e(e, "Error stopping AudioRecord")
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		stopListening()
		recognizer?.close()
		model?.close()
		serviceScope.cancel()
		Log.d("LucyVoice", "VoiceCommandService destroyed")
		Timber.d("VoiceCommandService destroyed")
	}
}
