package com.pianosounds.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/** Two-octave touch audition; external MIDI always supports the full 88-note range. */
class PianoView @JvmOverloads constructor(context: Context, private val note: (Int, Boolean) -> Unit = { _, _ -> }) : View(context) {
    private val white = listOf(60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 83)
    private val black = listOf(61 to 1, 63 to 2, 66 to 4, 68 to 5, 70 to 6, 73 to 8, 75 to 9, 78 to 11, 80 to 12, 82 to 13)
    private val pointers = mutableMapOf<Int, Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var externalNote = -1
    init {
        contentDescription = "피아노 미리 듣기 건반, C4부터 B5까지. 접근성 미리 듣기 버튼도 사용할 수 있습니다."
        isClickable = true
    }
    fun highlight(midiNote: Int) { if (externalNote != midiNote) { externalNote = midiNote; invalidate() } }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = width / 14f
        white.forEachIndexed { index, key ->
            paint.color = if (pointers.containsValue(key) || externalNote == key) Color.rgb(171, 204, 179) else Color.rgb(253, 252, 247)
            canvas.drawRoundRect(index * unit + 1, 1f, (index + 1) * unit - 1, height - 1f, 5f, 5f, paint)
            if (key % 12 == 0) {
                paint.color = Color.rgb(100, 111, 102); paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("C${key / 12 - 1}", (index + .5f) * unit, height - 12f, paint)
            }
        }
        black.forEach { (key, index) ->
            paint.color = if (pointers.containsValue(key) || externalNote == key) Color.rgb(75, 131, 102) else Color.rgb(39, 48, 43)
            canvas.drawRoundRect(index * unit - unit * .30f, 0f, index * unit + unit * .30f, height * .61f, 4f, 4f, paint)
        }
    }
    private fun keyAt(x: Float, y: Float): Int? {
        if (x < 0 || x >= width || y < 0 || y >= height) return null
        val unit = width / 14f
        if (y < height * .61f) black.firstOrNull { (_, i) -> x > (i - .30f) * unit && x < (i + .30f) * unit }?.let { return it.first }
        return white[(x / unit).toInt().coerceIn(0, 13)]
    }
    private fun setPointer(id: Int, key: Int?) {
        val old = pointers[id]
        if (old == key) return
        pointers.remove(id)
        if (old != null && !pointers.containsValue(old)) note(old, false)
        if (key != null) {
            if (!pointers.containsValue(key)) note(key, true)
            pointers[id] = key
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val i = event.actionIndex
                setPointer(event.getPointerId(i), keyAt(event.getX(i), event.getY(i)))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount)
                setPointer(event.getPointerId(i), keyAt(event.getX(i), event.getY(i)))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                setPointer(event.getPointerId(event.actionIndex), null)
                if (event.actionMasked == MotionEvent.ACTION_UP) { parent.requestDisallowInterceptTouchEvent(false); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun releaseAll() {
        pointers.values.toSet().forEach { note(it, false) }
        pointers.clear(); externalNote = -1; invalidate()
    }
}
