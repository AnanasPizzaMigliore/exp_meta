package com.meta.pixelandtexel.scanner.executorch

import android.content.Context
import android.util.Log
import com.facebook.soloader.SoLoader
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.io.FileOutputStream

/**
 * Data class to hold both the raw output array and the tensor dimensions.
 * This is required for OCRManager to calculate the correct number of classes.
 */
data class ModelOutput(val data: FloatArray, val shape: LongArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ModelOutput

        if (!data.contentEquals(other.data)) return false
        if (!shape.contentEquals(other.shape)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + shape.contentHashCode()
        return result
    }
}

/**
 * Wrapper class for ExecuTorch Inference.
 * FIX: Added 'private val' to modelName so it can be accessed in the catch block.
 */
class ExecutorchModel(context: Context, private val modelName: String) {
    private var module: Module? = null

    init {
        SoLoader.init(context, false)
        try {
            val file = File(context.filesDir, modelName)
            if (!file.exists()) {
                context.assets.open(modelName).use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                }
            }
            module = Module.load(file.absolutePath)
            Log.d("ExecutorchModel", "Successfully loaded model: $modelName")
        } catch (e: Exception) {
            Log.e("ExecutorchModel", "Failed to load model: $modelName", e)
            e.printStackTrace()
        }
    }

    /**
     * Runs inference on the input data.
     * @param inputData Flat float array of image data (NCHW)
     * @param width Input width (e.g. 1280 for det, 320 for rec)
     * @param height Input height (e.g. 1280 for det, 48 for rec)
     * @return ModelOutput containing data and shape, or null if failed.
     */
    fun forward(inputData: FloatArray, width: Int, height: Int): ModelOutput? {
        val mod = module ?: return null

        // Prepare Input Tensor (1, 3, H, W)
        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        val inputTensor = Tensor.fromBlob(inputData, shape)

        return try {
            val outputs = mod.forward(EValue.from(inputTensor))

            if (outputs.isNotEmpty() && outputs[0].isTensor) {
                val tensor = outputs[0].toTensor()

                // Return data + shape so OCRManager works correctly
                ModelOutput(
                    data = tensor.dataAsFloatArray,
                    shape = tensor.shape()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            // Fix: modelName is now accessible here because it is a property
            Log.e("OpenOCR", "Inference Failed for $modelName", e)
            null
        }
    }
}