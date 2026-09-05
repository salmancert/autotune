package dev.autotune.core.ml

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

enum class Activation { TANH, RELU, SOFTMAX, LINEAR }

/**
 * Fully connected layer. Weights are row-major `[output][input]`.
 */
class DenseLayer(
    val inputs: Int,
    val outputs: Int,
    val weights: FloatArray,
    val bias: FloatArray,
    val activation: Activation,
) {
    init {
        require(weights.size == inputs * outputs) { "expected ${inputs * outputs} weights, got ${weights.size}" }
        require(bias.size == outputs) { "expected $outputs biases, got ${bias.size}" }
    }

    fun forward(input: FloatArray, output: FloatArray) {
        var w = 0
        for (o in 0 until outputs) {
            var sum = bias[o]
            for (i in 0 until inputs) {
                sum += weights[w++] * input[i]
            }
            output[o] = sum
        }
        when (activation) {
            Activation.TANH -> for (o in 0 until outputs) output[o] = tanh(output[o].toDouble()).toFloat()
            Activation.RELU -> for (o in 0 until outputs) output[o] = max(0f, output[o])
            Activation.LINEAR -> Unit
            Activation.SOFTMAX -> softmax(output, outputs)
        }
    }

    private fun softmax(values: FloatArray, n: Int) {
        var maxValue = values[0]
        for (i in 1 until n) maxValue = max(maxValue, values[i])
        var sum = 0.0
        for (i in 0 until n) {
            val e = exp((values[i] - maxValue).toDouble())
            values[i] = e.toFloat()
            sum += e
        }
        val inv = (1.0 / sum).toFloat()
        for (i in 0 until n) values[i] *= inv
    }
}

/**
 * The bundled network: a small standardise-then-MLP stack.
 *
 * It is intentionally tiny (a few thousand multiply-adds per 32 ms hop). The
 * heavy lifting is done by the features; the network only has to learn the
 * decision surface between them, and a bigger model would cost battery-free but
 * not free CPU on a TV stick that is also decoding 4K video.
 */
class Mlp(
    val featureMean: FloatArray,
    val featureScale: FloatArray,
    val layers: List<DenseLayer>,
    val classNames: List<String>,
) {
    val inputSize: Int = layers.first().inputs
    val outputSize: Int = layers.last().outputs

    private val widest = layers.maxOf { it.outputs }
    private val bufferA = FloatArray(max(inputSize, widest))
    private val bufferB = FloatArray(widest)

    init {
        require(featureMean.size == inputSize && featureScale.size == inputSize) {
            "standardisation vectors must match the input size"
        }
        require(classNames.size == outputSize) { "classNames must match the output size" }
    }

    /** Runs inference. [out] receives [outputSize] values; nothing is allocated. */
    fun predict(features: FloatArray, out: FloatArray) {
        require(features.size == inputSize) { "expected $inputSize features, got ${features.size}" }
        for (i in 0 until inputSize) {
            bufferA[i] = (features[i] - featureMean[i]) / featureScale[i]
        }
        var source = bufferA
        var target = bufferB
        for ((index, layer) in layers.withIndex()) {
            val destination = if (index == layers.lastIndex) out else target
            layer.forward(source, destination)
            if (index != layers.lastIndex) {
                val swap = source
                source = target
                target = swap
            }
        }
    }
}
