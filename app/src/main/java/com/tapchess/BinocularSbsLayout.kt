package com.tapchess

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * FABLE_X3_STARTER_GUIDE Part IV pattern #1: one logical child, drawn twice
 * side by side — left copy for the left eye, right copy for the right. The
 * proven drawChild path, no vendor SDK. With `sbsEnabled = false` (phone
 * testing) the child simply fills the window.
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var sbsEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    private var touchOffsetLatched = false

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val childW = if (sbsEnabled) w / 2 else w
        getChildAt(0)?.measure(
            MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val childW = if (sbsEnabled) (right - left) / 2 else right - left
        child.layout(0, 0, childW, bottom - top)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = getChildAt(0) ?: return
        if (!sbsEnabled) {
            drawChild(canvas, child, drawingTime)
            return
        }
        val logicalWidth = width / 2
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawingTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawingTime)
        canvas.restore()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        invalidate()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (sbsEnabled) {
            val logicalWidth = width / 2f
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                touchOffsetLatched = ev.x >= logicalWidth
            }
            if (touchOffsetLatched) ev.offsetLocation(-logicalWidth, 0f)
        }
        return super.dispatchTouchEvent(ev)
    }
}
