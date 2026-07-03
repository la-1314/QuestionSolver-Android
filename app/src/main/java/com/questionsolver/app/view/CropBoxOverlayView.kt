package com.questionsolver.app.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 题目框选叠加视图。
 *
 * - 自身负责按 fit-center 绘制底图，并在其上叠加可拖拽/可缩放的裁剪框。
 * - 框坐标统一使用“相对位图的归一化坐标 (0~1)”，与具体显示尺寸解耦。
 * - 交互：
 *    触到角点/边中点 → 缩放对应边；
 *    触到框内部 → 拖动整框；
 *    触到空白区域并拖动 → 新建一个框；
 *    选中态高亮，外部可调用删除当前选中框。
 */
class CropBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var bitmap: Bitmap? = null
    private val boxes = mutableListOf<RectF>()
    /** 与 boxes 一一对应：是否被用户手动修改/新建。true 表示该题为手动框选，不再做百度增强。 */
    private val manualFlags = mutableListOf<Boolean>()
    private var selectedIndex = -1

    // 图片在视图中的显示矩形（fit-center）
    private var displayRect = RectF()
    private var imgScale = 1f

    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.parseColor("#3F51B5")
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = Color.parseColor("#FF4081")
    }
    private val fillDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33000000")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF4081")
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.WHITE
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        isFakeBoldText = true
    }

    private enum class Handle { TL, TR, BR, BL, T, R, B, L }
    private var activeHandle: Handle? = null
    private var draggingBoxIndex = -1
    private var creatingBox: RectF? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    var onBoxesChanged: (() -> Unit)? = null

    fun setBitmap(bmp: Bitmap) {
        this.bitmap = bmp
        requestLayout()
        invalidate()
    }

    fun setBoxes(list: List<RectF>) {
        boxes.clear()
        boxes.addAll(list.map { RectF(it) })
        manualFlags.clear()
        repeat(boxes.size) { manualFlags.add(false) }   // 自动切分默认为非手动
        selectedIndex = if (boxes.isNotEmpty()) 0 else -1
        invalidate()
    }

    fun getBoxes(): List<RectF> = boxes.map { RectF(it) }

    fun getManualFlags(): List<Boolean> = manualFlags.toList()

    private fun markManual(index: Int) {
        if (index in manualFlags.indices) {
            manualFlags[index] = true
            onBoxesChanged?.invoke()
        }
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun selectBox(index: Int) {
        if (index in boxes.indices) {
            selectedIndex = index
            invalidate()
        }
    }

    fun deleteSelected() {
        if (selectedIndex in boxes.indices) {
            boxes.removeAt(selectedIndex)
            if (selectedIndex in manualFlags.indices) manualFlags.removeAt(selectedIndex)
            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
            if (boxes.isEmpty()) selectedIndex = -1
            onBoxesChanged?.invoke()
            invalidate()
        }
    }

    fun addEmptyBox() {
        // 在画面中央新建一个默认框并选中（手动新建 → 标记为手动）
        val cx = 0.5f
        val cy = 0.5f
        val w = 0.4f
        val h = 0.2f
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        boxes.add(rect)
        manualFlags.add(true)
        selectedIndex = boxes.size - 1
        onBoxesChanged?.invoke()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeDisplayRect()
    }

    private fun computeDisplayRect() {
        val bmp = bitmap ?: return
        val vw = width.toFloat() - paddingLeft - paddingRight
        val vh = height.toFloat() - paddingTop - paddingBottom
        if (vw <= 0 || vh <= 0) return
        val s = min(vw / bmp.width, vh / bmp.height)
        imgScale = s
        val dw = bmp.width * s
        val dh = bmp.height * s
        val left = paddingLeft + (vw - dw) / 2
        val top = paddingTop + (vh - dh) / 2
        displayRect = RectF(left, top, left + dw, top + dh)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (displayRect.width() <= 0) computeDisplayRect()
        // 底图
        canvas.drawBitmap(bmp, null, displayRect, null)
        // 框
        boxes.forEachIndexed { i, box ->
            val screen = toScreen(box)
            // 暗化非选中框外侧（简化：仅描边）
            val paint = if (i == selectedIndex) selectedStroke else boxStroke
            canvas.drawRect(screen, paint)
            // 编号
            val label = (i + 1).toString()
            canvas.drawText(label, screen.left + dp(4f), screen.top + dp(14f), numberPaint)
            // 选中框绘制手柄
            if (i == selectedIndex) {
                drawHandles(canvas, screen)
            }
        }
    }

    private fun drawHandles(canvas: Canvas, r: RectF) {
        val hs = dp(8f)
        for (hx in listOf(r.left, r.centerX(), r.right)) {
            for (hy in listOf(r.top, r.centerY(), r.bottom)) {
                // 跳过边中点的中心
                if ((hx == r.centerX()) && (hy == r.centerY())) continue
                canvas.drawCircle(hx, hy, hs, handlePaint)
                canvas.drawCircle(hx, hy, hs, handleStroke)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (displayRect.width() <= 0) computeDisplayRect()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                // 1) 命中选中框的手柄？→ 缩放（手动修改）
                if (selectedIndex in boxes.indices) {
                    val h = hitHandle(toScreen(boxes[selectedIndex]), event.x, event.y)
                    if (h != null) {
                        activeHandle = h
                        markManual(selectedIndex)
                        return true
                    }
                }
                // 2) 命中任意框内部？→ 拖动（手动修改）
                val hit = hitBox(event.x, event.y)
                if (hit >= 0) {
                    selectedIndex = hit
                    draggingBoxIndex = hit
                    markManual(hit)
                    invalidate()
                    return true
                }
                // 3) 空白区域：开始新建框（手动）
                val n = toNormalized(event.x, event.y) ?: return true
                creatingBox = RectF(n.first, n.second, n.first, n.second)
                boxes.add(creatingBox!!)
                manualFlags.add(true)
                selectedIndex = boxes.size - 1
                activeHandle = Handle.BR
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val handle = activeHandle
                if (handle != null && selectedIndex in boxes.indices) {
                    val n = toNormalized(event.x, event.y) ?: return true
                    val b = boxes[selectedIndex]
                    when (handle) {
                        Handle.TL -> { b.left = n.first.coerceAtMost(b.right - MIN); b.top = n.second.coerceAtMost(b.bottom - MIN) }
                        Handle.TR -> { b.right = n.first.coerceAtLeast(b.left + MIN); b.top = n.second.coerceAtMost(b.bottom - MIN) }
                        Handle.BR -> { b.right = n.first.coerceAtLeast(b.left + MIN); b.bottom = n.second.coerceAtLeast(b.top + MIN) }
                        Handle.BL -> { b.left = n.first.coerceAtMost(b.right - MIN); b.bottom = n.second.coerceAtLeast(b.top + MIN) }
                        Handle.T -> b.top = n.second.coerceAtMost(b.bottom - MIN)
                        Handle.R -> b.right = n.first.coerceAtLeast(b.left + MIN)
                        Handle.B -> b.bottom = n.second.coerceAtLeast(b.top + MIN)
                        Handle.L -> b.left = n.first.coerceAtMost(b.right - MIN)
                    }
                    b.left = b.left.coerceIn(0f, 1f)
                    b.top = b.top.coerceIn(0f, 1f)
                    b.right = b.right.coerceIn(0f, 1f)
                    b.bottom = b.bottom.coerceIn(0f, 1f)
                    markManual(selectedIndex)
                    invalidate()
                    return true
                }
                if (draggingBoxIndex in boxes.indices) {
                    val dx = (event.x - lastTouchX) / displayRect.width()
                    val dy = (event.y - lastTouchY) / displayRect.height()
                    val b = boxes[draggingBoxIndex]
                    val w = b.width()
                    val h = b.height()
                    var nl = (b.left + dx).coerceIn(0f, 1f - w)
                    var nt = (b.top + dy).coerceIn(0f, 1f - h)
                    b.set(nl, nt, nl + w, nt + h)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (creatingBox != null) {
                    // 太小则删除
                    val b = creatingBox!!
                    if (b.width() < MIN && b.height() < MIN) {
                        boxes.removeAt(selectedIndex)
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(-1)
                    }
                    creatingBox = null
                }
                activeHandle = null
                draggingBoxIndex = -1
                onBoxesChanged?.invoke()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun hitBox(x: Float, y: Float): Int {
        for (i in boxes.indices.reversed()) {
            if (toScreen(boxes[i]).contains(x, y)) return i
        }
        return -1
    }

    private fun hitHandle(r: RectF, x: Float, y: Float): Handle? {
        val tol = dp(16f)
        val nearL = abs(x - r.left) < tol
        val nearR = abs(x - r.right) < tol
        val nearT = abs(y - r.top) < tol
        val nearB = abs(y - r.bottom) < tol
        val nearCX = abs(x - r.centerX()) < tol
        val nearCY = abs(y - r.centerY()) < tol
        if (nearL && nearT) return Handle.TL
        if (nearR && nearT) return Handle.TR
        if (nearR && nearB) return Handle.BR
        if (nearL && nearB) return Handle.BL
        if (nearCX && nearT) return Handle.T
        if (nearR && nearCY) return Handle.R
        if (nearCX && nearB) return Handle.B
        if (nearL && nearCY) return Handle.L
        return null
    }

    private fun toScreen(norm: RectF): RectF = RectF(
        displayRect.left + norm.left * displayRect.width(),
        displayRect.top + norm.top * displayRect.height(),
        displayRect.left + norm.right * displayRect.width(),
        displayRect.top + norm.bottom * displayRect.height()
    )

    private fun toNormalized(x: Float, y: Float): Pair<Float, Float>? {
        if (displayRect.width() <= 0 || displayRect.height() <= 0) return null
        val nx = ((x - displayRect.left) / displayRect.width()).coerceIn(0f, 1f)
        val ny = ((y - displayRect.top) / displayRect.height()).coerceIn(0f, 1f)
        return nx to ny
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun abs(v: Float) = if (v < 0) -v else v

    companion object {
        private const val MIN = 0.05f
    }
}
