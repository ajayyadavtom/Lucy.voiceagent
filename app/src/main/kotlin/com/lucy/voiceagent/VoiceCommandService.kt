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
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Foreground Service holding a PARTIAL_WAKE_LOCK for screen-off voice listening.
 * Uses native SpeechRecognizer for wake-word ("lucy" / "లూసీ") with strict mutex locking,
 * plays a ToneGenerator beep, records 5 seconds of audio via AudioRecord, and transcribes via Sarvam STT.
 */
class VoiceCommandService : Service() {

	interface StatusListener {
		fun onStatusUpdate(statusText: String)
	}

	private val binder = LocalBinder()
	private var statusListener: StatusListener? = null

	private var speechRecognizer: SpeechRecognizer? = null
	private var wakeLock: PowerManager.WakeLock? = null
	private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
	private val mainHandler = Handler(Looper.getMainLooper())
	private lateinit var commandRouter: CommandRouter

	@Volatile
	private var isProcessingCommand = false

	@Volatile
	private var isListening = false

	companion object {
		private const val CHANNEL_ID = "VoiceCommandServiceChannel"
		private const val NOTIFICATION_ID = 4001
		private const val SAMPLE_RATE = 16000
	}

	inner class LocalBinder : Binder() {
		fun getService(): VoiceCommandService = this@VoiceCommandService
	}

	override fun onCreate() {
		super.onCreate()
		Log.d("LucyVoice", "VoiceCommandService created")
		commandRouter = CommandRouter(this)

		val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
		wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LucyVoiceAgent::WakeLock").apply {
			acquire()
		}
		Log.d("LucyVoice", "PARTIAL_WAKE_LOCK acquired for screen-off voice listening")

		createNotificationChannel()
		startForeground(NOTIFICATION_ID, getNotification("Listening for 'Lucy'..."))

		initNativeWakeWordRecognizer()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == "STOP_SERVICE") {
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
				"Lucy Native Assistant",
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
			.setContentTitle("Lucy Voice Agent")
			.setContentText(text)
			.setSmallIcon(android.R.drawable.ic_dialog_info)
			.setOngoing(true)
			.addAction(0, "Stop", stopPendingIntent)
			.build()
	}

	private fun updateNotification(text: String) {
		getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, getNotification(text))
	}

	private fun initNativeWakeWordRecognizer() {
		mainHandler.post {
			try {
				if (SpeechRecognizer.isRecognitionAvailable(this)) {
					speechRecognizer?.destroy()
					speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
					speechRecognizer?.setRecognitionListener(object : RecognitionListener {
						override fun onReadyForSpeech(params: Bundle?) {
							isListening = true
						}
						override fun onBeginningOfSpeech() {}
						override fun onRmsChanged(rmsdB: Float) {}
						override fun onBufferReceived(buffer: ByteArray?) {}
						override fun onEndOfSpeech() {}

						override fun onError(error: Int) {
							isListening = false
							Log.d("LucyVoice", "Native SpeechRecognizer error code: $error")
							if (!isProcessingCommand) {
								mainHandler.postDelayed({ restartWakeWordListening() }, 1000)
							}
						}

						override fun onResults(results: Bundle?) {
							isListening = false
							if (isProcessingCommand) return

							val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
							if (!matches.isNullOrEmpty()) {
								val heardText = matches[0].lowercase(Locale.ROOT)
								Log.d("LucyVoice", "Native SpeechRecognizer heard: $heardText")

								if (heardText.contains("lucy") || heardText.contains("లూసీ")) {
									Log.d("LucyVoice", "Wake word matched! Triggering command capture...")
									onWakeWordDetected()
									return
								}
							}
							if (!isProcessingCommand) {
								restartWakeWordListening()
							}
						}

						override fun onPartialResults(partialResults: Bundle?) {
							if (isProcessingCommand) return
							val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
							if (!matches.isNullOrEmpty()) {
								val partial = matches[0].lowercase(Locale.ROOT)
								if (partial.contains("lucy") || partial.contains("లూసీ")) {
									Log.d("LucyVoice", "Wake word detected in partial result: $partial")
									onWakeWordDetected()
								}
							}
						}

						override fun onEvent(eventType: Int, params: Bundle?) {}
					})

					restartWakeWordListening()
					notifyStatus("Listening for 'Lucy'...")
				} else {
					Log.e("LucyVoice", "Native SpeechRecognizer is not available on this device")
					notifyStatus("SpeechRecognizer Unavailable")
				}
			} catch (e: Exception) {
				Log.e("LucyVoice", "Error initializing native SpeechRecognizer", e)
			}
		}
	}

	private fun restartWakeWordListening() {
		if (isProcessingCommand || isListening) return
		try {
			val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
				putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
				putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
				putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
			}
			speechRecognizer?.startListening(intent)
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error restarting native SpeechRecognizer", e)
		}
	}

	private fun onWakeWordDetected() {
		if (isProcessingCommand) return
		isProcessingCommand = true
		isListening = false

		try {
			speechRecognizer?.stopListening()
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error stopping SpeechRecognizer", e)
		}

		notifyStatus("Lucy Activated! Recording command...")

		try {
			val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
			toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error playing beep tone", e)
		}

		captureAudioAndProcessSarvam()
	}

	@SuppressLint("MissingPermission")
	private fun captureAudioAndProcessSarvam() {
		serviceScope.launch(Dispatchers.IO) {
			val totalSamples = SAMPLE_RATE * 5 // 5 seconds
			val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

			val pcmBuffer = ByteArray(totalSamples * 2)

			try {
				val record = AudioRecord(
					MediaRecorder.AudioSource.MIC,
					SAMPLE_RATE,
					AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT,
					maxOf(minBufferSize, 8192)
				)

				record.startRecording()
				Log.d("LucyVoice", "AudioRecord started 5-second command recording...")

				var readBytes = 0
				while (readBytes < pcmBuffer.size && isProcessingCommand) {
					val read = record.read(pcmBuffer, readBytes, pcmBuffer.size - readBytes)
					if (read > 0) readBytes += read else break
				}

				try {
					record.stop()
				} catch (e: Exception) {
					Timber.e(e, "Error stopping AudioRecord")
				}
				record.release()

				val rawFile = File(cacheDir, "command_audio.raw")
				val wavFile = File(cacheDir, "command_audio.wav")
				FileOutputStream(rawFile).use { it.write(pcmBuffer, 0, readBytes) }

				pcmToWavFile(rawFile, wavFile, sampleRate = SAMPLE_RATE)

				Log.d("LucyVoice", "Uploading WAV audio to Sarvam STT API...")
				notifyStatus("Transcribing via Sarvam AI...")

				val sttResult = SarvamApiClient.transcribe(wavFile, "en-IN")
				val transcript = sttResult?.first

				withContext(Dispatchers.Main) {
					if (!transcript.isNullOrEmpty()) {
						Log.d("LucyVoice", "Sarvam Transcribed: '$transcript'")
						notifyStatus("Executing: $transcript")

						val accessibilityService = AutomationAccessibilityService.instance
						commandRouter.routeCommand(transcript, "en-IN", accessibilityService)
					} else {
						notifyStatus("Speech not recognized")
					}

					mainHandler.postDelayed({
						isProcessingCommand = false
						notifyStatus("Listening for 'Lucy'...")
						restartWakeWordListening()
					}, 3500)
				}

			} catch (e: Exception) {
				Log.e("LucyVoice", "Error during command audio capture", e)
				isProcessingCommand = false
				mainHandler.post {
					notifyStatus("Listening for 'Lucy'...")
					restartWakeWordListening()
				}
			}
		}
	}

	private fun pcmToWavFile(rawFile: File, wavFile: File, sampleRate: Int) {
		val pcmData = rawFile.readBytes()
		val totalAudioLen = pcmData.size.toLong()
		val totalDataLen = totalAudioLen + 36
		val channels = 1
		val bitDepth = 16
		val byteRate = (sampleRate * channels * bitDepth / 8).toLong()

		val header = ByteArray(44)
		header[0] = 'R'.code.toByte()
		header[1] = 'I'.code.toByte()
		header[2] = 'F'.code.toByte()
		header[3] = 'F'.code.toByte()
		header[4] = (totalDataLen and 0xff).toByte()
		header[5] = ((totalDataLen shr 8) and 0xff).toByte()
		header[6] = ((totalDataLen shr 16) and 0xff).toByte()
		header[7] = ((totalDataLen shr 24) and 0xff).toByte()
		header[8] = 'W'.code.toByte()
		header[9] = 'A'.code.toByte()
		header[10] = 'V'.code.toByte()
		header[11] = 'E'.code.toByte()
		header[12] = 'f'.code.toByte()
		header[13] = 'm'.code.toByte()
		header[14] = 't'.code.toByte()
		header[15] = ' '.code.toByte()
		header[16] = 16
		header[17] = 0
		header[18] = 0
		header[19] = 0
		header[20] = 1
		header[21] = 0
		header[22] = channels.toByte()
		header[23] = 0
		header[24] = (sampleRate and 0xff).toByte()
		header[25] = ((sampleRate shr 8) and 0xff).toByte()
		header[26] = ((sampleRate shr 16) and 0xff).toByte()
		header[27] = ((sampleRate shr 24) and 0xff).toByte()
		header[28] = (byteRate and 0xff).toByte()
		header[29] = ((byteRate shr 8) and 0xff).toByte()
		header[30] = ((byteRate shr 16) and 0xff).toByte()
		header[31] = ((byteRate shr 24) and 0xff).toByte()
		header[32] = (channels * bitDepth / 8).toByte()
		header[33] = 0
		header[34] = bitDepth.toByte()
		header[35] = 0
		header[36] = 'd'.code.toByte()
		header[37] = 'a'.code.toByte()
		header[38] = 't'.code.toByte()
		header[39] = 'a'.code.toByte()
		header[40] = (totalAudioLen and 0xff).toByte()
		header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
		header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
		header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

		FileOutputStream(wavFile).use { out ->
			out.write(header)
			out.write(pcmData)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		isProcessingCommand = false
		isListening = false
		try {
			if (wakeLock?.isHeld == true) {
				wakeLock?.release()
				Log.d("LucyVoice", "PARTIAL_WAKE_LOCK released")
			}
		} catch (e: Exception) {
			Log.e("LucyVoice", "Error releasing WakeLock", e)
		}
		speechRecognizer?.destroy()
		speechRecognizer = null
		serviceScope.cancel()
		Log.d("LucyVoice", "VoiceCommandService destroyed")
	}
}
