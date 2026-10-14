package com.sacls.foldercleaner

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout luôn vuông: chiều cao bằng chiều rộng. Dùng cho ô lưới thumbnail. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
