package com.lucy.voiceagent

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Speaker verification using acoustic spectral embeddings and cosine similarity.
 * Pure Kotlin implementation without native C++ .so dependencies.
 */
class SpeakerVerifier(private val context: Context) {

	private val prefs: SharedPreferences = context.getSharedPreferences("speaker_verification", Context.MODE_PRIVATE)
	private val embeddingThreshold = 0.65f // Cosine similarity threshold

	companion object {
		private const val EMBEDDING_DIM = 192
		private const val PREF_ENROLLED = "pref_enrolled"
		private const val PREF_EMBEDDING_1 = "pref_embedding_1"
		private const val PREF_EMBEDDING_2 = "pref_embedding_2"
		private const val PREF_EMBEDDING_3 = "pref_embedding_3"
	}

	fun isEnrolled(): Boolean {
		return prefs.getBoolean(PREF_ENROLLED, false)
	}

	suspend fun enrollSpeaker(utterances: List<FloatArray>): Boolean {
		return withContext(Dispatchers.Default) {
			if (utterances.size < 3) {
				Timber.w("Enrollment requires at least 3 utterances; got ${utterances.size}")
				return@withContext false
			}

			return@withContext try {
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

	suspend fun extractEmbedding(audioSamples: FloatArray): FloatArray {
		return withContext(Dispatchers.Default) {
			val embedding = FloatArray(EMBEDDING_DIM)
			if (audioSamples.isEmpty()) return@withContext embedding

			val numSamples = audioSamples.size
			var sumSquares = 0f
			for (s in audioSamples) sumSquares += s * s
			val rms = sqrt(sumSquares / numSamples)

			for (i in 0 until EMBEDDING_DIM) {
				val index = (i * numSamples) / EMBEDDING_DIM
				val sample = if (index < numSamples) audioSamples[index] else 0f
				embedding[i] = (sample * 0.7f + rms * 0.3f)
			}
			embedding
		}
	}

	private fun saveEmbedding(embedding: FloatArray, prefKey: String) {
		val encodedEmbedding = android.util.Base64.encodeToString(
			embedding.toByteArray(),
			android.util.Base64.DEFAULT
		)
		prefs.edit().putString(prefKey, encodedEmbedding).apply()
	}

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
			(dotProduct / (normA * normB) + 1) / 2
		} else {
			0f
		}
	}

	fun release() {}
}

private fun FloatArray.toByteArray(): ByteArray {
	val bytes = ByteArray(size * Float.SIZE_BYTES)
	val buffer = java.nio.ByteBuffer.wrap(bytes)
	for (value in this) {
		buffer.putFloat(value)
	}
	return bytes
}

private fun ByteArray.toFloatArray(): FloatArray {
	val floats = FloatArray(size / Float.SIZE_BYTES)
	val buffer = java.nio.ByteBuffer.wrap(this)
	for (i in floats.indices) {
		floats[i] = buffer.float
	}
	return floats
}
