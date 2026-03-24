// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.pixelandtexel.scanner.services.llama

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.core.graphics.scale
// import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
// import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
// import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest
import com.google.gson.Gson
import com.meta.pixelandtexel.scanner.BuildConfig
import com.meta.pixelandtexel.scanner.services.llama.models.BedrockRequest
import com.meta.pixelandtexel.scanner.services.llama.models.BedrockResponse
import java.io.ByteArrayOutputStream

/**
 * Service object for interacting with a Llama model via AWS Bedrock.
 * AWS INTEGRATION CURRENTLY DISABLED.
 */
object QueryLlamaService {
    private const val TAG: String = "QueryLlamaService"
    private const val MAX_IMAGE_DIMENSION = 1120

    private val gson = Gson()
    // private var bedrockClient: BedrockRuntimeClient

    init {
        /* // AWS initialization disabled to prevent region configuration crash
        val credentials = StaticCredentialsProvider {
          accessKeyId = BuildConfig.AWS_ACCESS_KEY
          secretAccessKey = BuildConfig.AWS_SECRET_KEY
        }

        bedrockClient = BedrockRuntimeClient {
          region = BuildConfig.AWS_REGION
          credentialsProvider = credentials
        }
        */
        Log.d(TAG, "QueryLlamaService initialized in Stub mode (AWS Disabled)")
    }

    /**
     * Stub implementation: Returns a default message immediately.
     */
    suspend fun submitQuery(
        query: String,
        creativity: Float = .1f, // temperature
        diversity: Float = .9f, // top_p
        handler: IQueryLlamaServiceHandler,
    ) {
        Log.d(TAG, "submitQuery (Text) called while service is disabled")
        handler.onStreamStart()
        handler.onFinished("AI Service currently disabled.")
    }

    /**
     * Stub implementation: Returns a default message immediately.
     */
    suspend fun submitQuery(
        query: String,
        imageData: Bitmap,
        creativity: Float = .1f, // temperature
        diversity: Float = .9f, // top_p
        handler: IQueryLlamaServiceHandler,
    ) {
        Log.d(TAG, "submitQuery (Image) called while service is disabled")
        handler.onStreamStart()
        handler.onFinished("Cloud Service currently disabled.")
    }

    /**
     * Logic commented out to prevent reference to uninitialized bedrockClient
     */
    private suspend fun internalQuery(request: BedrockRequest, handler: IQueryLlamaServiceHandler) {
        /*
        try {
          val requestBody = gson.toJson(request)
          val nativeRequest = InvokeModelWithResponseStreamRequest {
            modelId = "us.meta.llama3-2-11b-instruct-v1:0"
            contentType = "application/json"
            accept = "application/json"
            body = requestBody.encodeToByteArray()
          }

          val responseBuilder = StringBuilder()
          var chunksReceived = 0

          bedrockClient.invokeModelWithResponseStream(nativeRequest) { resp ->
            resp.body?.collect { partial ->
              val partialBody = partial.asChunk().bytes?.decodeToString()
              val parsedBody: BedrockResponse = gson.fromJson(partialBody, BedrockResponse::class.java)

              responseBuilder.append(parsedBody.generation)

              chunksReceived++
              if (chunksReceived == 1) {
                handler.onStreamStart()
              }

              handler.onPartial(responseBuilder.toString())
            }
          }

          handler.onFinished(responseBuilder.toString())
        } catch (e: Exception) {
          handler.onError("Error: ${e.message}")
        }
        */
    }

    private fun resizeToFit(
        bitmap: Bitmap,
        maxWidth: Int,
        maxHeight: Int,
        filter: Boolean = true,
    ): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val aspectRatio = width.toFloat() / height.toFloat()
        var newWidth = maxWidth
        var newHeight = (newWidth / aspectRatio).toInt()

        if (newHeight > maxHeight) {
            newHeight = maxHeight
            newWidth = (newHeight * aspectRatio).toInt()
        }

        if (newWidth <= 0) newWidth = 1
        if (newHeight <= 0) newHeight = 1

        return bitmap.scale(newWidth, newHeight, filter)
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT).replace("\n", "").replace("\r", "")
    }
}