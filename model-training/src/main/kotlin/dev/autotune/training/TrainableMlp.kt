package dev.autotune.training

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A small dense network trained with Adam and cross-entropy.
 *
 * Written out longhand rather than pulled from a framework: the network is a few
 * hundred parameters, and a self-contained trainer means `./gradlew trainModel`
 * works on any JVM with no Python, no wheels and no GPU.
 *
 * Hidden layers are tanh, the output is softmax.
 */
class TrainableMlp(private val sizes: IntArray, seed: Int) {

    private val random = Random(seed)
    private val layerCount = sizes.size - 1

    private val weights = Array(layerCount) { l ->
        // Xavier initialisation, which is what tanh layers want.
        val fanIn = sizes[l]
        val fanOut = sizes[l + 1]
        val limit = sqrt(6.0 / (fanIn + fanOut)).toFloat()
        FloatArray(fanIn * fanOut) { (random.nextFloat() * 2f - 1f) * limit }
    }
    private val biases = Array(layerCount) { FloatArray(sizes[it + 1]) }

    private val mWeights = Array(layerCount) { FloatArray(weights[it].size) }
    private val vWeights = Array(layerCount) { FloatArray(weights[it].size) }
    private val mBias = Array(layerCount) { FloatArray(biases[it].size) }
    private val vBias = Array(layerCount) { FloatArray(biases[it].size) }

    private val gradWeights = Array(layerCount) { FloatArray(weights[it].size) }
    private val gradBias = Array(layerCount) { FloatArray(biases[it].size) }

    private val activations = Array(sizes.size) { FloatArray(sizes[it]) }
    private val deltas = Array(layerCount) { FloatArray(sizes[it + 1]) }

    private var step = 0

    fun forward(input: FloatArray): FloatArray {
        input.copyInto(activations[0])
        for (l in 0 until layerCount) {
            val inSize = sizes[l]
            val outSize = sizes[l + 1]
            val w = weights[l]
            val a = activations[l]
            val out = activations[l + 1]
            var index = 0
            for (o in 0 until outSize) {
                var sum = biases[l][o]
                for (i in 0 until inSize) sum += w[index++] * a[i]
                out[o] = sum
            }
            if (l == layerCount - 1) softmax(out) else for (o in 0 until outSize) out[o] = tanh(out[o].toDouble()).toFloat()
        }
        return activations[layerCount]
    }

    /** Accumulates gradients for one example and returns its cross-entropy loss. */
    fun accumulate(input: FloatArray, label: Int, sampleWeight: Float): Float {
        val output = forward(input)
        val loss = -ln(max(1e-7f, output[label]).toDouble()).toFloat() * sampleWeight

        // Softmax + cross-entropy collapse to (p - y).
        val last = layerCount - 1
        for (o in 0 until sizes[layerCount]) {
            deltas[last][o] = (output[o] - if (o == label) 1f else 0f) * sampleWeight
        }
        for (l in last downTo 0) {
            val inSize = sizes[l]
            val outSize = sizes[l + 1]
            val a = activations[l]
            val delta = deltas[l]
            var index = 0
            for (o in 0 until outSize) {
                val d = delta[o]
                gradBias[l][o] += d
                for (i in 0 until inSize) gradWeights[l][index++] += d * a[i]
            }
            if (l > 0) {
                val previous = deltas[l - 1]
                java.util.Arrays.fill(previous, 0f)
                val w = weights[l]
                var wi = 0
                for (o in 0 until outSize) {
                    val d = delta[o]
                    for (i in 0 until inSize) previous[i] += d * w[wi++]
                }
                // tanh'(x) = 1 - tanh(x)^2, and activations[l] already holds tanh(x).
                val activation = activations[l]
                for (i in 0 until inSize) previous[i] *= 1f - activation[i] * activation[i]
            }
        }
        return loss
    }

    /** Applies the accumulated gradients and clears them. */
    fun applyGradients(batchSize: Int, learningRate: Float, weightDecay: Float) {
        step++
        val scale = 1f / batchSize
        val biasCorrection1 = (1.0 - BETA1.pow(step)).toFloat()
        val biasCorrection2 = (1.0 - BETA2.pow(step)).toFloat()
        for (l in 0 until layerCount) {
            update(weights[l], gradWeights[l], mWeights[l], vWeights[l], scale, learningRate, weightDecay, biasCorrection1, biasCorrection2)
            update(biases[l], gradBias[l], mBias[l], vBias[l], scale, learningRate, 0f, biasCorrection1, biasCorrection2)
        }
    }

    private fun update(
        parameters: FloatArray,
        gradients: FloatArray,
        m: FloatArray,
        v: FloatArray,
        scale: Float,
        learningRate: Float,
        weightDecay: Float,
        biasCorrection1: Float,
        biasCorrection2: Float,
    ) {
        for (i in parameters.indices) {
            val g = gradients[i] * scale + weightDecay * parameters[i]
            m[i] = (BETA1 * m[i] + (1.0 - BETA1) * g).toFloat()
            v[i] = (BETA2 * v[i] + (1.0 - BETA2) * g * g).toFloat()
            val mHat = m[i] / biasCorrection1
            val vHat = v[i] / biasCorrection2
            parameters[i] -= learningRate * mHat / (sqrt(vHat.toDouble()).toFloat() + EPSILON)
            gradients[i] = 0f
        }
    }

    fun predictedClass(input: FloatArray): Int {
        val output = forward(input)
        var best = 0
        for (i in 1 until output.size) if (output[i] > output[best]) best = i
        return best
    }

    fun weightsOf(layer: Int): FloatArray = weights[layer]

    fun biasOf(layer: Int): FloatArray = biases[layer]

    val layers: Int get() = layerCount

    fun inputsOf(layer: Int): Int = sizes[layer]

    fun outputsOf(layer: Int): Int = sizes[layer + 1]

    private fun softmax(values: FloatArray) {
        var maxValue = values[0]
        for (i in 1 until values.size) maxValue = max(maxValue, values[i])
        var sum = 0.0
        for (i in values.indices) {
            val e = exp((values[i] - maxValue).toDouble())
            values[i] = e.toFloat()
            sum += e
        }
        for (i in values.indices) values[i] = (values[i] / sum).toFloat()
    }

    private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())

    private companion object {
        const val BETA1 = 0.9
        const val BETA2 = 0.999
        const val EPSILON = 1e-8f
    }
}
