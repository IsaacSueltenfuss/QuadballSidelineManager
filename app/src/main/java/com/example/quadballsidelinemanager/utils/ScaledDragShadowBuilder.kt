package com.example.quadballsidelinemanager.utils

import android.graphics.*
import android.view.View

class ScaledDragShadowBuilder(
    view: View,
    private val targetWidthPx: Int,
    private val targetHeightPx: Int
) : View.DragShadowBuilder(view) {

    // Define the visual "elevation" (around 6dp look)
    private val shadowRadius = 12f // The blur size
    private val shadowOffset = 6f   // How far the image floats above the pitch

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        // We make the canvas larger than the target card size
        // to leave space for the soft shadow edges.
        val finalWidth = targetWidthPx + (shadowRadius * 2).toInt()
        val finalHeight = targetHeightPx + (shadowRadius * 2).toInt()

        // Set the size of the entire shadow image
        outShadowSize.set(finalWidth, finalHeight)

        // Set where the finger (touch point) should anchor.
        // We put it exactly in the middle of the shrunken card.
        outShadowTouchPoint.set(finalWidth / 2, finalHeight / 2)
    }

    // 2. Draw the "ghost" card onto the canvas
    override fun onDrawShadow(canvas: Canvas) {
        val sourceView = view ?: return

        // -- Setup the "Native Shadow" Paint --
        // (Uses a black, semi-transparent blur around the card edges)
        val shadowPaint = Paint().apply {
            color = Color.BLACK
            alpha = (0.25f * 255).toInt() // 25% transparent black
            maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.OUTER)
        }

        // -- Calculate scaling factors --
        val scaleX = targetWidthPx.toFloat() / sourceView.width
        val scaleY = targetHeightPx.toFloat() / sourceView.height

        // **A. Add Elevation**
        // Shift everything slightly down and to the right to simulate float.
        canvas.translate(shadowOffset, shadowOffset)

        // **B. Draw the "Native Shadow"**
        // (A slightly smaller rectangle that sits 'under' the card)
        canvas.drawRoundRect(
            RectF(shadowRadius, shadowRadius, targetWidthPx - shadowRadius, targetHeightPx - shadowRadius),
            20f, // Match the corner radius of your item_field_slot
            20f,
            shadowPaint
        )

        // **C. Draw the scaled player card**
        canvas.save()
        canvas.scale(scaleX, scaleY)
        sourceView.draw(canvas)
        canvas.restore()
    }
}