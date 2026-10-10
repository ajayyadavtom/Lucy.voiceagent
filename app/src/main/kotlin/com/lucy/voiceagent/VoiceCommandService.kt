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
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import timber.log.Timber

/**
 * ForegroundService for multilingual (en-IN and te-IN) voice assistant supporting offline Vosk wake-word ("Lucy" / "లూసీ")
 * and Sarvam AI REST API for high-accuracy speech transcription and command routing.
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
	private lateinit var commandRouter: CommandRouter

	companion object {
		private const val CHANNEL_ID = "VoiceCommandServiceChannel"
		private const val NOTIFICATION_ID = 3001
		private const val SAMPLE_RATE = 16000
	}

	inner class LocalBinder : Binder() {
		fun getService(): VoiceCommandService = this@VoiceCommandService
	}

	override fun onCreate() {
		super.onCreate()
		Log.d("LucyVoice", "VoiceCommandService created")
		commandRouter = CommandRouter(this)

		LibVosk.setLogLevel(LogLevel.INFO)
		createNotificationChannel()
		startForeground(NOTIFICATION_ID, getNotification("Initializing multilingual engine..."))

		initializeEngine()
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
				"Lucy Multilingual Assistant",
				NotificationManager.IMPORTANCE_LOW
			)
			getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
		}
	}

	private fun getNotification(text: String): Notification {
		val stopIntent = Intent(this, VoiceCommandService::class.java).setAction("STOP_SERVICE")
		val stopPendingIntent = PendingIntent.getService(
			this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setContentTitle("Lucy Voice Assistant (en-IN / te-IN)")
			.setContentText(text)
			.setSmallIcon(android.R.drawable.ic_dialog_info)
			.setOngoing(true)
			.addAction(0, "Stop", stopPendingIntent)
			.build()
	}

	private fun updateNotification(text: String) {
		getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, getNotification(text))
	}

	/**
	 * Unpacks the uncompressed Vosk English-India model directory from assets into context.filesDir/model
	 * using StorageService.unpack(), and initializes the Recognizer only inside the successful callback.
	 */
	private fun initializeEngine() {
		notifyStatus("Extracting voice model...")
		Log.d("LucyVoice", "Starting StorageService.unpack for 'vosk-model-small-en-in-0.4'...")

		StorageService.unpack(
			this,
			"vosk-model-small-en-in-0.4",
			"model",
			{ unpackedModel ->
				model = unpackedModel
				try {
					recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
					Log.d("LucyVoice", "Vosk Recognizer initialized successfully from unpacked model")
					notifyStatus("Listening for 'Lucy' / 'లూసీ'...")
					startListeningLoop()
				} catch (e: Exception) {
					Log.e("LucyVoice", "Failed to create Vosk Recognizer", e)
					notifyStatus("Recognizer Init Failed: ${e.message}")
				}
			},
			{ exception ->
				Log.e("LucyVoice", "Failed to unpack Vosk model via StorageService, trying fallback asset path", exception)
				tryFallbackUnpack()
			}
		)
	}

	private fun tryFallbackUnpack() {
		StorageService.unpack(
			this,
			"vosk/vosk-model-small-en-in-0.4",
			"model",
			{ unpackedModel ->
				model = unpackedModel
				try {
					recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
					Log.d("LucyVoice", "Vosk Recognizer initialized successfully from fallback path")
					notifyStatus("Listening for 'Lucy' / 'లూసీ'...")
					startListeningLoop()
				} catch (e: Exception) {
					Log.e("LucyVoice", "Failed to create Vosk Recognizer on fallback", e)
					notifyStatus("Recognizer Init Failed: ${e.message}")
				}
			},
			{ e ->
				Log.e("LucyVoice", "Fallback model unpack also failed", e)
				notifyStatus("Model Init Failed: ${e.message}")
			}
		)
	}

	@SuppressLint("MissingPermission")
	private fun startListeningLoop() {
		if (isListening) return
		val rec = recognizer ?: return
		isListening = true

		val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		val buffer = ShortArray(4096)

		try {
			val record = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBufferSize, 8192))
			audioRecord = record
			record.startRecording()
			Log.d("LucyVoice", "AudioRecord successfully started recording")

			while (isListening && serviceScope.isActive) {
				val readSamples = record.read(buffer, 0, buffer.size)
				if (readSamples > 0) {
					if (rec.acceptWaveForm(buffer, readSamples)) {
						val jsonResult = rec.result
						processVoskJson(jsonResult)
					}
				}
			}
			record.stop()
			record.release()
			audioRecord = null
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error in audio record loop", e)
		} finally {
			isListening = false
		}
	}

	private fun processVoskJson(jsonResult: String?) {
		if (jsonResult.isNullOrEmpty()) return
		try {
			val json = JSONObject(jsonResult)
			val text = json.optString("text", "").lowercase().trim()
			if (text.isEmpty()) return

			Log.d("LucyVoice", "Heard text: $text")
			notifyStatus("Heard: $text")

			if (text.contains("lucy") || text.contains("లూసీ")) {
				val keyword = if (text.contains("lucy")) "lucy" else "లూసీ"
				val index = text.indexOf(keyword)
				val command = text.substring(index + keyword.length).trim().removePrefix(",").trim()

				Log.d("LucyVoice", "Wake word matched! Command: '$command'")
				notifyStatus("Executing: $command")

				mainHandler.post {
					val accessibilityService = AutomationAccessibilityService.instance
					commandRouter.routeCommand(command, "en-IN", accessibilityService)
				}

				mainHandler.postDelayed({
					notifyStatus("Listening...")
				}, 3500)
			}
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error processing Vosk json", e)
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
