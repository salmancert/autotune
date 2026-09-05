package dev.autotune.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.autotune.core.engine.StabilizerState
import dev.autotune.tv.R

/**
 * Live view of what the engine is doing: the three class probabilities, the
 * gain being applied, and the measured loudness.
 *
 * Worth the screen space - the whole system is invisible otherwise, and when a
 * user says "it ducked something it shouldn't have", this is the thing that
 * says why.
 */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = fill(R.color.meter_track)
    private val dialoguePaint = fill(R.color.dialogue)
    private val musicPaint = fill(R.color.music)
    private val effectsPaint = fill(R.color.effects)
    private val accentPaint = fill(R.color.accent)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 26f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 26f
        textAlign = Paint.Align.RIGHT
    }

    private val bar = RectF()

    var state: StabilizerState = StabilizerState()
        set(value) {
            field = value
            invalidate()
        }

    private fun fill(colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, colorRes)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rowHeight = height / 5f
        val barHeight = rowHeight * 0.42f
        val labelWidth = 190f
        val valueWidth = 170f
        val trackLeft = paddingLeft + labelWidth
        val trackRight = width - paddingRight - valueWidth

        drawProbability(canvas, 0, rowHeight, barHeight, trackLeft, trackRight, R.string.meter_dialogue, state.speechProbability, dialoguePaint)
        drawProbability(canvas, 1, rowHeight, barHeight, trackLeft, trackRight, R.string.meter_music, state.musicProbability, musicPaint)
        drawProbability(canvas, 2, rowHeight, barHeight, trackLeft, trackRight, R.string.meter_effects, state.effectsProbability, effectsPaint)
        drawGain(canvas, 3, rowHeight, barHeight, trackLeft, trackRight)
        drawLoudness(canvas, 4, rowHeight, barHeight, trackLeft, trackRight)
    }

    private fun drawProbability(
        canvas: Canvas,
        row: Int,
        rowHeight: Float,
        barHeight: Float,
        left: Float,
        right: Float,
        labelRes: Int,
        value: Float,
        paint: Paint,
    ) {
        val centreY = rowHeight * (row + 0.5f)
        canvas.drawText(context.getString(labelRes), paddingLeft.toFloat(), centreY + 9f, labelPaint)
        track(canvas, left, right, centreY, barHeight)
        bar.set(left, centreY - barHeight / 2, left + (right - left) * value.coerceIn(0f, 1f), centreY + barHeight / 2)
        canvas.drawRoundRect(bar, 6f, 6f, paint)
        canvas.drawText("%.0f%%".format(value * 100), width - paddingRight.toFloat(), centreY + 9f, valuePaint)
    }

    /** Gain is bipolar, so it grows out from the centre of the track. */
    private fun drawGain(canvas: Canvas, row: Int, rowHeight: Float, barHeight: Float, left: Float, right: Float) {
        val centreY = rowHeight * (row + 0.5f)
        canvas.drawText(context.getString(R.string.meter_gain), paddingLeft.toFloat(), centreY + 9f, labelPaint)
        track(canvas, left, right, centreY, barHeight)

        val midpoint = (left + right) / 2
        val fraction = (state.gainDb / GAIN_RANGE_DB).coerceIn(-1f, 1f)
        val end = midpoint + (right - midpoint) * fraction
        bar.set(minOf(midpoint, end), centreY - barHeight / 2, maxOf(midpoint, end), centreY + barHeight / 2)
        canvas.drawRoundRect(bar, 6f, 6f, if (state.gainDb < 0) musicPaint else dialoguePaint)
        canvas.drawRect(midpoint - 1f, centreY - barHeight, midpoint + 1f, centreY + barHeight, labelPaint)
        canvas.drawText(
            context.getString(R.string.meter_gain_format, state.gainDb),
            width - paddingRight.toFloat(),
            centreY + 9f,
            valuePaint,
        )
    }

    private fun drawLoudness(canvas: Canvas, row: Int, rowHeight: Float, barHeight: Float, left: Float, right: Float) {
        val centreY = rowHeight * (row + 0.5f)
        canvas.drawText(context.getString(R.string.meter_loudness), paddingLeft.toFloat(), centreY + 9f, labelPaint)
        track(canvas, left, right, centreY, barHeight)

        // -60 LUFS at the left edge, 0 at the right.
        val fraction = ((state.momentaryLufs + 60f) / 60f).coerceIn(0f, 1f)
        bar.set(left, centreY - barHeight / 2, left + (right - left) * fraction, centreY + barHeight / 2)
        canvas.drawRoundRect(bar, 6f, 6f, accentPaint)
        val text = if (state.momentaryLufs <= StabilizerState.LoudnessSilence + 1f || !state.hasSignal) {
            "-"
        } else {
            context.getString(R.string.meter_loudness_format, state.momentaryLufs)
        }
        canvas.drawText(text, width - paddingRight.toFloat(), centreY + 9f, valuePaint)
    }

    private fun track(canvas: Canvas, left: Float, right: Float, centreY: Float, barHeight: Float) {
        bar.set(left, centreY - barHeight / 2, right, centreY + barHeight / 2)
        canvas.drawRoundRect(bar, 6f, 6f, trackPaint)
    }

    private companion object {
        /** Full-scale deflection of the gain meter, in dB either way. */
        const val GAIN_RANGE_DB = 15f
    }
}
