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

    // 1. Setup API Keys and Clients (No AssemblyAI SDK needed anymore!)
    val aaiApiKey = System.getenv("ASSEMBLYAI_API_KEY") ?: ""
    val humeApiKey = System.getenv("HUME_API_KEY") ?: ""
    val httpClient = OkHttpClient()
    val jsonParser = Gson()

    // 2. Define the API Routes
    routing {

        post("/analyze-audio") {
            val multipartData = call.receiveMultipart()
            val tempFile = File("temp_${UUID.randomUUID()}.m4a")

            // Save uploaded file from the mobile app
            multipartData.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val fileBytes = part.provider().toByteArray()
                    tempFile.writeBytes(fileBytes)
                }
                part.dispose()
            }

            try {
                // --- ASSEMBLY AI (Speaker Diarization via Raw REST API) ---

                // 1. Upload audio file securely
                val uploadRequest = Request.Builder()
                    .url("https://api.assemblyai.com/v2/upload")
                    .addHeader("Authorization", aaiApiKey)
                    .post(tempFile.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    .build()

                val uploadResponse = httpClient.newCall(uploadRequest).execute()
                val uploadUrl = jsonParser.fromJson(uploadResponse.body?.string(), JsonObject::class.java).get("upload_url").asString

                // 2. Start transcription with the exact JSON list needed
                val aaiJson = """
                    {
                        "audio_url": "$uploadUrl",
                        "speech_models": ["universal-3-pro", "universal-2"],
                        "speaker_labels": true
                    }
                """.trimIndent()

                val transcriptRequest = Request.Builder()
                    .url("https://api.assemblyai.com/v2/transcript")
                    .addHeader("Authorization", aaiApiKey)
                    .post(aaiJson.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val transcriptResponse = httpClient.newCall(transcriptRequest).execute()
                val transcriptId = jsonParser.fromJson(transcriptResponse.body?.string(), JsonObject::class.java).get("id").asString

                // 3. Poll AssemblyAI until finished
                var utterancesList = listOf<Map<String, String>>()
                var aaiDone = false
                while (!aaiDone) {
                    Thread.sleep(2000)
                    val pollReq = Request.Builder()
                        .url("https://api.assemblyai.com/v2/transcript/$transcriptId")
                        .addHeader("Authorization", aaiApiKey)
                        .get().build()

                    val pollObj = jsonParser.fromJson(httpClient.newCall(pollReq).execute().body?.string(), JsonObject::class.java)
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


                // --- HUME AI (Emotion Analysis) ---
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
                val jobJson = jsonParser.fromJson(humeResponse.body?.string(), JsonObject::class.java)
                val jobId = jobJson.get("job_id").asString

                // Poll Hume until finished
                var isDone = false
                while (!isDone) {
                    Thread.sleep(2000)
                    val statusReq = Request.Builder()
                        .url("https://api.hume.ai/v0/batch/jobs/$jobId")
                        .addHeader("X-Hume-Api-Key", humeApiKey)
                        .get().build()
                    val statusObj = jsonParser.fromJson(httpClient.newCall(statusReq).execute().body?.string(), JsonObject::class.java)
                    if (statusObj.getAsJsonObject("state").get("status").asString == "COMPLETED") {
                        isDone = true
                    }
                }

                // Parse Hume Results
                val resultsReq = Request.Builder()
                    .url("https://api.hume.ai/v0/batch/jobs/$jobId/predictions")
                    .addHeader("X-Hume-Api-Key", humeApiKey)
                    .get().build()

                val resultsResponse = httpClient.newCall(resultsReq).execute()
                val resultsArray = jsonParser.fromJson(resultsResponse.body?.string(), com.google.gson.JsonArray::class.java)

                // Extracting top emotions
                val predictions = resultsArray.get(0).asJsonObject
                    .getAsJsonObject("results").getAsJsonArray("predictions")
                val topEmotions = predictions.get(0).asJsonObject
                    .getAsJsonObject("models").getAsJsonObject("prosody")
                    .getAsJsonArray("grouped_predictions").get(0).asJsonObject
                    .getAsJsonArray("predictions").get(0).asJsonObject
                    .getAsJsonArray("emotions")
                    .map { it.asJsonObject }
                    .sortedByDescending { it.get("score").asDouble }
                    .take(3)
                    .map { mapOf("name" to it.get("name").asString, "score" to it.get("score").asDouble) }

                // --- RETURN DATA TO MOBILE APP ---
                call.respond(
                    mapOf(
                        "status" to "success",
                        "utterances" to utterancesList,
                        "emotions" to topEmotions
                    )
                )

            } catch (e: Exception) {
                // Safely handle errors so the app doesn't crash on a bad response
                call.respond(
                    mapOf(
                        "status" to "error",
                        "message" to (e.message ?: "Unknown error occurred")
                    )
                )
            } finally {
                // Delete the file from the server so the hard drive doesn't fill up
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }
}