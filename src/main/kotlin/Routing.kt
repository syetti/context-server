package com.example

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

fun Application.configureRouting() {
    val aaiApiKey = System.getenv("ASSEMBLYAI_API_KEY") ?: ""
    val humeApiKey = System.getenv("HUME_API_KEY") ?: ""
    val httpClient = OkHttpClient()
    val jsonParser = Gson()

    routing {
        post("/analyze-audio") {
            val multipartData = call.receiveMultipart()
            val tempFile = File("temp_${UUID.randomUUID()}.m4a")

            multipartData.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val fileBytes = part.provider().toByteArray()
                    tempFile.writeBytes(fileBytes)
                }
                part.dispose()
            }

            try {
                // Upload audio to AssemblyAI for transcription
                val uploadRequest = Request.Builder()
                    .url("https://api.assemblyai.com/v2/upload")
                    .addHeader("Authorization", aaiApiKey)
                    .post(tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()

                val uploadResponse = httpClient.newCall(uploadRequest).execute()
                val uploadBody = uploadResponse.body?.string() ?: ""

                if (!uploadResponse.isSuccessful) {
                    throw Exception("AssemblyAI Upload Failed (${uploadResponse.code}): $uploadBody")
                }
                val uploadUrl = jsonParser.fromJson(uploadBody, JsonObject::class.java).get("upload_url").asString

                val aaiJson = """
                    {
                        "audio_url": "$uploadUrl",
                        "speech_models": ["universal-3-pro", "universal-2"],
                        "speaker_labels": true,
                        "speakers_expected": 2
                    }
                """.trimIndent()

                val transcriptRequest = Request.Builder()
                    .url("https://api.assemblyai.com/v2/transcript")
                    .addHeader("Authorization", aaiApiKey)
                    .post(aaiJson.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val transcriptResponse = httpClient.newCall(transcriptRequest).execute()
                val transcriptBody = transcriptResponse.body?.string() ?: ""

                if (!transcriptResponse.isSuccessful) {
                    throw Exception("AssemblyAI Transcribe Failed (${transcriptResponse.code}): $transcriptBody")
                }
                val transcriptId = jsonParser.fromJson(transcriptBody, JsonObject::class.java).get("id").asString

                var utterancesList = listOf<Map<String, String>>()
                var aaiDone = false
                while (!aaiDone) {
                    Thread.sleep(2000)
                    val pollReq = Request.Builder()
                        .url("https://api.assemblyai.com/v2/transcript/$transcriptId")
                        .addHeader("Authorization", aaiApiKey)
                        .get().build()

                    val pollResponse = httpClient.newCall(pollReq).execute()
                    val pollBody = pollResponse.body?.string() ?: ""
                    if (!pollResponse.isSuccessful) throw Exception("AssemblyAI Poll Failed: $pollBody")

                    val pollObj = jsonParser.fromJson(pollBody, JsonObject::class.java)
                    val status = pollObj.get("status").asString

                    if (status == "completed") {
                        aaiDone = true
                        val utterancesArray = pollObj.getAsJsonArray("utterances")
                        if (utterancesArray != null) {
                            utterancesList = utterancesArray.map {
                                val u = it.asJsonObject
                                mapOf("speaker" to u.get("speaker").asString, "text" to u.get("text").asString)
                            }
                        }
                    } else if (status == "error") {
                        throw Exception("AssemblyAI failed: ${pollObj.get("error").asString}")
                    }
                }

                // Send audio to Hume AI for prosody (emotion) analysis
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("json", "{\"models\": {\"prosody\": {}}}")
                    .addFormDataPart("file", tempFile.name, tempFile.asRequestBody("audio/mp4".toMediaTypeOrNull()))
                    .build()

                val humeRequest = Request.Builder()
                    .url("https://api.hume.ai/v0/batch/jobs")
                    .addHeader("X-Hume-Api-Key", humeApiKey)
                    .post(requestBody)
                    .build()

                val humeResponse = httpClient.newCall(humeRequest).execute()
                val humeBody = humeResponse.body?.string() ?: ""

                if (!humeResponse.isSuccessful) {
                    throw Exception("Hume AI Job Failed (${humeResponse.code}): $humeBody")
                }

                val jobId = jsonParser.fromJson(humeBody, JsonObject::class.java).get("job_id").asString

                var isDone = false
                while (!isDone) {
                    Thread.sleep(2000)
                    val statusReq = Request.Builder()
                        .url("https://api.hume.ai/v0/batch/jobs/$jobId")
                        .addHeader("X-Hume-Api-Key", humeApiKey)
                        .get().build()

                    val statusResponse = httpClient.newCall(statusReq).execute()
                    val statusBody = statusResponse.body?.string() ?: ""
                    if (!statusResponse.isSuccessful) throw Exception("Hume AI Poll Failed: $statusBody")

                    val statusObj = jsonParser.fromJson(statusBody, JsonObject::class.java)
                    if (statusObj.getAsJsonObject("state").get("status").asString == "COMPLETED") {
                        isDone = true
                    } else if (statusObj.getAsJsonObject("state").get("status").asString == "FAILED") {
                        throw Exception("Hume AI Processing Failed.")
                    }
                }

                val resultsReq = Request.Builder()
                    .url("https://api.hume.ai/v0/batch/jobs/$jobId/predictions")
                    .addHeader("X-Hume-Api-Key", humeApiKey)
                    .get().build()

                val resultsResponse = httpClient.newCall(resultsReq).execute()
                val resultsBody = resultsResponse.body?.string() ?: ""
                if (!resultsResponse.isSuccessful) throw Exception("Hume AI Results Failed: $resultsBody")

                val resultsArray = jsonParser.fromJson(resultsBody, com.google.gson.JsonArray::class.java)

                // Extract top 3 emotions and average their scores across all predictions
                val topEmotions = try {
                    val resultsObj = resultsArray.get(0).asJsonObject.getAsJsonObject("results")

                    if (resultsObj.has("errors") && !resultsObj.getAsJsonArray("errors").isEmpty) {
                        emptyList()
                    }
                    else if (!resultsObj.has("predictions") || resultsObj.get("predictions").isJsonNull) {
                        emptyList()
                    }
                    else {
                        val predictions = resultsObj.getAsJsonArray("predictions")
                        if (predictions.isEmpty) {
                            emptyList()
                        } else {
                            val allPredictions = predictions.get(0).asJsonObject
                                .getAsJsonObject("models").getAsJsonObject("prosody")
                                .getAsJsonArray("grouped_predictions").get(0).asJsonObject
                                .getAsJsonArray("predictions")

                            val emotionTotals = mutableMapOf<String, Double>()

                            for (i in 0 until allPredictions.size()) {
                                val emotionsArray = allPredictions.get(i).asJsonObject.getAsJsonArray("emotions")
                                for (j in 0 until emotionsArray.size()) {
                                    val emotion = emotionsArray.get(j).asJsonObject
                                    val name = emotion.get("name").asString
                                    val score = emotion.get("score").asDouble
                                    emotionTotals[name] = (emotionTotals[name] ?: 0.0) + score
                                }
                            }

                            emotionTotals.entries
                                .sortedByDescending { it.value }
                                .take(3)
                                .map { mapOf("name" to it.key, "score" to (it.value / allPredictions.size())) }
                        }
                    }
                } catch (_: Exception) {
                    emptyList()
                }

                call.respond(
                    mapOf(
                        "status" to "success",
                        "utterances" to utterancesList,
                        "emotions" to topEmotions
                    )
                )

            } catch (e: Exception) {
                call.respond(
                    mapOf(
                        "status" to "error",
                        "message" to (e.message ?: "Unknown error occurred")
                    )
                )
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }
}
