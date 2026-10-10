package com.lucy.voiceagent

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Speaker verification using TFLite speaker-embedding model.
 * Enrolls speaker voice and verifies against subsequent utterances.
 */
class SpeakerVerifier(private val context: Context) {

	private val prefs: SharedPreferences = context.getSharedPreferences("speaker_verification", Context.MODE_PRIVATE)
	private var interpreter: Interpreter? = null
	private val embeddingThreshold = 0.65f // Cosine similarity threshold

	companion object {
		private const val MAX_RECORDING_DURATION_MS = 5000
		private const val SAMPLE_RATE = 16000
		private const val EMBEDDING_DIM = 192 // Common speaker embedding size
		private const val PREF_ENROLLED = "pref_enrolled"
		private const val PREF_EMBEDDING_1 = "pref_embedding_1"
		private const val PREF_EMBEDDING_2 = "pref_embedding_2"
		private const val PREF_EMBEDDING_3 = "pref_embedding_3"
	}

	init {
		loadModel()
	}

	/**
	 * Load the TFLite speaker-embedding model from assets.
	 */
	private fun loadModel() {
		try {
			val modelFile = File(context.filesDir, "speaker_verification.tflite")
			if (modelFile.exists()) {
				val fileInputStream = modelFile.inputStream()
				val fileChannel = fileInputStream.channel
				val mbb = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
				interpreter = Interpreter(mbb)
				Timber.i("Speaker verification model loaded successfully")
			} else {
				Timber.w("Speaker verification model not found. Verification will be skipped.")
			}
		} catch (e: Exception) {
			Timber.e(e, "Failed to load speaker verification model")
		}
	}

	/**
	 * Check if user has been enrolled.
	 */
	fun isEnrolled(): Boolean {
		return prefs.getBoolean(PREF_ENROLLED, false)
	}

	/**
	 * Enroll speaker: record 3 utterances and store embeddings.
	 */
	suspend fun enrollSpeaker(utterances: List<FloatArray>): Boolean {
		return withContext(Dispatchers.Default) {
			if (utterances.size < 3) {
				Timber.w("Enrollment requires at least 3 utterances; got ${utterances.size}")
				return@withContext false
			}

			return@withContext try {
				// Store first 3 embeddings
				saveEmbedding(utterances[0], PREF_EMBEDDING_1)
				saveEmbedding(utterances[1], PREF_EMBEDDING_2)
				saveEmbedding(utterances[2], PREF_EMBEDDING_3)

				prefs.edit().putBoolean(PREF_ENROLLED, true).apply()
				Timber.i("Speaker enrollment complete")
				true
			} catch (e: Exception) {
				Timber.e(e, "Failed to enroll speaker")
				false
			}
		}
	}

	/**
	 * Verify speaker against enrolled embeddings.
	 * Returns true if voice matches with sufficient confidence.
	 */
	suspend fun verifySpeaker(voiceEmbedding: FloatArray): Boolean {
		return withContext(Dispatchers.Default) {
			if (!isEnrolled()) {
				Timber.w("Speaker not enrolled")
				return@withContext false
			}

			return@withContext try {
				val enrolled1 = loadEmbedding(PREF_EMBEDDING_1) ?: return@withContext false
				val enrolled2 = loadEmbedding(PREF_EMBEDDING_2) ?: return@withContext false
				val enrolled3 = loadEmbedding(PREF_EMBEDDING_3) ?: return@withContext false

				val sim1 = cosineSimilarity(voiceEmbedding, enrolled1)
				val sim2 = cosineSimilarity(voiceEmbedding, enrolled2)
				val sim3 = cosineSimilarity(voiceEmbedding, enrolled3)

				val avgSimilarity = (sim1 + sim2 + sim3) / 3f
				Timber.d("Speaker verification similarity: $avgSimilarity (threshold: $embeddingThreshold)")

				avgSimilarity >= embeddingThreshold
			} catch (e: Exception) {
				Timber.e(e, "Failed to verify speaker")
				false
			}
		}
	}

	/**
	 * Extract embedding from float audio samples.
	 * This is a placeholder; actual implementation requires feeding audio to the TFLite model.
	 */
	suspend fun extractEmbedding(audioSamples: FloatArray): FloatArray? {
		return withContext(Dispatchers.Default) {
			if (interpreter == null) {
				Timber.w("Interpreter not loaded")
				return@withContext null
			}

			return@withContext try {
				// TODO: Preprocess audio, feed to TFLite interpreter
				// For now, return a placeholder. In production, reshape audio and call:
				// interpreter!!.run(preprocessedAudio, embeddingOutput)
				FloatArray(EMBEDDING_DIM) { 0.1f } // Placeholder
			} catch (e: Exception) {
				Timber.e(e, "Failed to extract embedding")
				null
			}
		}
	}

	/**
	 * Save embedding to shared preferences (base64 encoded).
	 */
	private fun saveEmbedding(embedding: FloatArray, prefKey: String) {
		val encodedEmbedding = android.util.Base64.encodeToString(
			embedding.toByteArray(),
			android.util.Base64.DEFAULT
		)
		prefs.edit().putString(prefKey, encodedEmbedding).apply()
	}

	/**
	 * Load embedding from shared preferences.
	 */
	private fun loadEmbedding(prefKey: String): FloatArray? {
		val encoded = prefs.getString(prefKey, null) ?: return null
		return try {
			val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
			bytes.toFloatArray()
		} catch (e: Exception) {
			Timber.e(e, "Failed to load embedding")
			null
		}
	}

	/**
	 * Compute cosine similarity between two embeddings [0, 1].
	 */
	private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
		if (a.size != b.size) return 0f

		var dotProduct = 0f
		var normA = 0f
		var normB = 0f

		for (i in a.indices) {
			dotProduct += a[i] * b[i]
			normA += a[i] * a[i]
			normB += b[i] * b[i]
		}

		normA = sqrt(normA)
		normB = sqrt(normB)

		return if (normA * normB > 0) {
			(dotProduct / (normA * normB) + 1) / 2  // Normalize to [0, 1]
		} else {
			0f
		}
	}

	fun release() {
		interpreter?.close()
	}
}

/**
 * Extension: convert FloatArray to ByteArray for serialization (simple byte packing).
 */
private fun FloatArray.toByteArray(): ByteArray {
	val bytes = ByteArray(size * Float.SIZE_BYTES)
	val buffer = java.nio.ByteBuffer.wrap(bytes)
	for (value in this) {
		buffer.putFloat(value)
	}
	return bytes
}

/**
 * Extension: convert ByteArray back to FloatArray.
 */
private fun ByteArray.toFloatArray(): FloatArray {
	val floats = FloatArray(size / Float.SIZE_BYTES)
	val buffer = java.nio.ByteBuffer.wrap(this)
	for (i in floats.indices) {
		floats[i] = buffer.float
	}
	return floats
}
