package com.retrivedmods.wclient.render

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.retrivedmods.wclient.game.ModuleManager
import com.retrivedmods.wclient.game.module.visual.ESPModule

class RenderOverlayView(context: Context) : View(context) {

    private var needsRedraw = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate() // Initial render
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        ModuleManager.modules
            .filterIsInstance<ESPModule>()
            .filter { it.isEnabled && it.isSessionCreated }
            .forEach { it.render(canvas) }

        // Only schedule the next frame if a rendering module is active.
        if (ModuleManager.modules.any {
                it is ESPModule && it.isEnabled && it.isSessionCreated
            }) {
            needsRedraw = true
            postInvalidateOnAnimation()
        } else {
            needsRedraw = false
        }
    }

    /** Request an immediate redraw without scheduling continuous animation frames. */
    fun requestRedraw() {
        needsRedraw = true
        invalidate()
    }

    fun isNeedsRedraw(): Boolean {
        return needsRedraw
    }
}
