package com.remmi.browser.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class WebSwipeRefreshLayout @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

  var canScrollUpCallback: (() -> Boolean)? = null
  private var initialDownX = 0f
  private var initialDownY = 0f

  override fun canChildScrollUp(): Boolean {
    return canScrollUpCallback?.invoke() ?: super.canChildScrollUp()
  }

  override fun requestDisallowInterceptTouchEvent(b: Boolean) {
    // If the page is at the very top, ignore child's disallow-intercept so pull-to-refresh can engage
    if (b && !canChildScrollUp()) {
      return
    }
    super.requestDisallowInterceptTouchEvent(b)
  }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        initialDownX = ev.x
        initialDownY = ev.y
      }
      MotionEvent.ACTION_MOVE -> {
        val deltaY = ev.y - initialDownY
        val deltaX = ev.x - initialDownX
        // If swiping upwards or horizontal swipe dominates, do not intercept
        if (deltaY <= 0 || Math.abs(deltaX) > Math.abs(deltaY)) {
          return false
        }
      }
    }
    return super.onInterceptTouchEvent(ev)
  }
}
