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

  override fun canChildScrollUp(): Boolean {
    return canScrollUpCallback?.invoke() ?: super.canChildScrollUp()
  }

  override fun requestDisallowInterceptTouchEvent(b: Boolean) {
    super.requestDisallowInterceptTouchEvent(b)
  }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    if (!isEnabled) {
      return false
    }
    return super.onInterceptTouchEvent(ev)
  }
}
