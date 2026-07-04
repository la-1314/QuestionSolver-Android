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
import kotlin.math.min

/**
 * 题目框选叠加视图。
 *
 * - 自身负责按 fit-center 绘制底图（单页或多页横向并排拼接），并在其上叠加可拖拽/可缩放的裁剪框。
 * - 框坐标统一使用"相对底图的归一化坐标 (0~1)"，与具体显示尺寸解耦。
 * - 多页支持：通过 [pageBoundariesX] 传入每页结束 X 的归一化坐标（0~1），用于绘制竖向页边界、
 *   页码标签。多页时底图为横向并排拼接，视觉上"从左到右列表式并排"，可滑动查看。
 * - 视觉辅助：每个框使用不同颜色（调色板循环）、左上角带数字角标圆徽。
 * - 多选模式：[multiSelectMode]=true 时，点击框切换勾选状态（不进入拖动），角标显示对勾；
 *   外部可通过 [getCheckedIndices] 获取选中框索引，用于「多选拼接」。
 * - 交互（非多选模式）：
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
    /** 与 boxes 一一对应：多选模式下是否被勾选。 */
    private val checkedFlags = mutableListOf<Boolean>()
    private var selectedIndex = -1

    /**
     * 各页结束 X 的归一化坐标（0~1，递增，最后一项为 1.0）。
     * 例如两页时若第一页占总宽的 48%（含间隔），则为 [0.48, 1.0]。
     * 空列表表示单页模式（不绘制页边界）。
     */
    private var pageBoundariesX: List<Float> = emptyList()

    /** 多选模式开关。开启后点击框切换勾选，不进入拖动/缩放。 */
    var multiSelectMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                checkedFlags.indices.forEach { checkedFlags[it] = false }
            }
            invalidate()
        }

    // 图片在视图中的显示矩形（fit-center）
    private var displayRect = RectF()
    private var imgScale = 1f
    /**
     * 是否处于横向滚动模式（多页宽图在 HorizontalScrollView 中）。
     * true 时：触摸空白区域不新建框，交由父容器横向滑动；底图填满高度、宽度按比例延伸。
     * 由 [onMeasure] 根据测量模式与图片宽高比自动判定。
     */
    private var scrollableMode = false

    /** 框颜色调色板：循环使用，确保相邻框颜色不同。 */
    private val boxColorPalette = intArrayOf(
        Color.parseColor("#3F51B5"), // 靛蓝
        Color.parseColor("#00897B"), // 青绿
        Color.parseColor("#8E24AA"), // 紫
        Color.parseColor("#3949AB"), // 蓝
        Color.parseColor("#43A047"), // 绿
        Color.parseColor("#1E88E5"), // 亮蓝
        Color.parseColor("#00ACC1"), // 青
        Color.parseColor("#7CB342")  // 黄绿
    )

    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = Color.parseColor("#FF4081")
    }
    private val checkedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = Color.parseColor("#FB8C00") // 勾选框用醒目橙
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
    /** 数字角标圆徽底色（随框颜色变化，绘制前会设置 color）。 */
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.WHITE
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    /** 勾选对勾画笔。 */
    private val checkMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /** 页边界虚线（竖向）。 */
    private val pageDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#AAFF5722")
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val pageLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E65100")
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val pageLabelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CCFFFFFF")
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

    /**
     * 设置页边界（归一化 X，递增，末项为 1.0）。空列表表示单页。
     */
    fun setPageBoundaries(boundaries: List<Float>) {
        this.pageBoundariesX = boundaries
        invalidate()
    }

    fun setBoxes(list: List<RectF>) {
        boxes.clear()
        boxes.addAll(list.map { RectF(it) })
        manualFlags.clear()
        checkedFlags.clear()
        repeat(boxes.size) {
            manualFlags.add(false)
            checkedFlags.add(false)
        }
        selectedIndex = if (boxes.isNotEmpty()) 0 else -1
        invalidate()
    }

    fun getBoxes(): List<RectF> = boxes.map { RectF(it) }

    fun getManualFlags(): List<Boolean> = manualFlags.toList()

    /** 多选模式下勾选的框索引列表。 */
    fun getCheckedIndices(): List<Int> =
        checkedFlags.indices.filter { checkedFlags[it] }

    /** 勾选框数量。 */
    fun checkedCount(): Int = checkedFlags.count { it }

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
            if (selectedIndex in checkedFlags.indices) checkedFlags.removeAt(selectedIndex)
            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
            if (boxes.isEmpty()) selectedIndex = -1
            onBoxesChanged?.invoke()
            invalidate()
        }
    }

    /**
     * 删除指定索引列表的框（用于「多选拼接」后删除被合并的原框）。
     * 内部按索引降序删除，避免索引错位。
     */
    fun deleteBoxes(indices: List<Int>) {
        val sorted = indices.sortedDescending()
        for (i in sorted) {
            if (i in boxes.indices) {
                boxes.removeAt(i)
                if (i in manualFlags.indices) manualFlags.removeAt(i)
                if (i in checkedFlags.indices) checkedFlags.removeAt(i)
            }
        }
        selectedIndex = if (boxes.isNotEmpty()) 0 else -1
        onBoxesChanged?.invoke()
        invalidate()
    }

    /** 在指定位置追加一个新框（用于「多选拼接」后追加合成题框）。 */
    fun addBox(rect: RectF, manual: Boolean = true) {
        boxes.add(RectF(rect))
        manualFlags.add(manual)
        checkedFlags.add(false)
        selectedIndex = boxes.size - 1
        onBoxesChanged?.invoke()
        invalidate()
    }

    fun addEmptyBox() {
        val cx = 0.5f
        val cy = 0.5f
        val w = 0.4f
        val h = 0.2f
        val rect = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        boxes.add(rect)
        manualFlags.add(true)
        checkedFlags.add(false)
        selectedIndex = boxes.size - 1
        onBoxesChanged?.invoke()
        invalidate()
    }

    /**
     * 测量：在可滚动容器（HorizontalScrollView）中遇到宽图时，按"填满高度、宽度按比例延伸"测量，
     * 使 View 宽度超出容器以支持横向滑动；其余情况按默认测量（fit-center 由 [computeDisplayRect] 计算）。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bmp = bitmap
        if (bmp == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val widthMode = android.view.View.MeasureSpec.getMode(widthMeasureSpec)
        val vw = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val vh = android.view.View.MeasureSpec.getSize(heightMeasureSpec)
        // HorizontalScrollView 给子 View 的 widthMode 非 EXACTLY；fillViewport=true 时若内容窄于视口则改为 EXACTLY
        scrollableMode = widthMode != android.view.View.MeasureSpec.EXACTLY &&
                bmp.width.toFloat() / bmp.height.toFloat() > vw.toFloat() / vh.toFloat()
        if (scrollableMode) {
            val s = vh / bmp.height
            val dw = (bmp.width * s).toInt().coerceAtLeast(1)
            setMeasuredDimension(dw, vh)
        } else {
            setMeasuredDimension(vw, vh)
        }
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
        // 页边界竖向虚线 + 页码标签
        if (pageBoundariesX.isNotEmpty()) {
            pageBoundariesX.dropLast(1).forEachIndexed { i, boundary ->
                val x = displayRect.left + boundary * displayRect.width()
                canvas.drawLine(x, displayRect.top, x, displayRect.bottom, pageDividerPaint)
                // 页码标签：边界左侧标注「第 i+1 页」，右侧标注「第 i+2 页」
                val leftLabel = "第${i + 1}页"
                val rightLabel = "第${i + 2}页"
                val pad = dp(4f)
                // 左侧标签
                var tw = pageLabelPaint.measureText(leftLabel)
                var lx = x - tw - pad * 2 - dp(2f)
                var ly = displayRect.top + dp(4f)
                canvas.drawRect(lx - pad, ly - dp(11f), lx + tw + pad, ly + dp(3f), pageLabelBgPaint)
                canvas.drawText(leftLabel, lx, ly, pageLabelPaint)
                // 右侧标签
                tw = pageLabelPaint.measureText(rightLabel)
                lx = x + dp(2f)
                ly = displayRect.top + dp(4f)
                canvas.drawRect(lx - pad, ly - dp(11f), lx + tw + pad, ly + dp(3f), pageLabelBgPaint)
                canvas.drawText(rightLabel, lx, ly, pageLabelPaint)
            }
        }
        // 框
        boxes.forEachIndexed { i, box ->
            val screen = toScreen(box)
            val color = boxColorPalette[i % boxColorPalette.size]
            val checked = checkedFlags.getOrElse(i) { false }
            boxStroke.color = color
            val paint = when {
                checked -> checkedStroke
                i == selectedIndex -> selectedStroke
                else -> boxStroke
            }
            canvas.drawRect(screen, paint)
            // 数字角标圆徽（多选模式下显示勾选状态）
            drawBadge(canvas, screen, i + 1, color, checked)
            // 选中框绘制手柄（多选模式下不显示手柄）
            if (i == selectedIndex && !multiSelectMode) {
                drawHandles(canvas, screen)
            }
        }
    }

    /** 绘制左上角数字角标圆徽；勾选时改用对勾覆盖数字。 */
    private fun drawBadge(canvas: Canvas, r: RectF, number: Int, color: Int, checked: Boolean) {
        val radius = dp(11f)
        val cx = r.left + radius
        val cy = r.top + radius
        badgePaint.color = if (checked) checkedStroke.color else color
        canvas.drawCircle(cx, cy, radius, badgePaint)
        canvas.drawCircle(cx, cy, radius, badgeStroke)
        if (checked) {
            // 对勾
            val p1 = floatArrayOf(cx - dp(5f), cy)
            val p2 = floatArrayOf(cx - dp(1.5f), cy + dp(4f))
            val p3 = floatArrayOf(cx + dp(5f), cy - dp(4f))
            checkMarkPaint.color = Color.WHITE
            canvas.drawLine(p1[0], p1[1], p2[0], p2[1], checkMarkPaint)
            canvas.drawLine(p2[0], p2[1], p3[0], p3[1], checkMarkPaint)
        } else {
            val baseline = cy - (numberPaint.descent() + numberPaint.ascent()) / 2
            canvas.drawText(number.toString(), cx, baseline, numberPaint)
        }
    }

    private fun drawHandles(canvas: Canvas, r: RectF) {
        val hs = dp(8f)
        for (hx in listOf(r.left, r.centerX(), r.right)) {
            for (hy in listOf(r.top, r.centerY(), r.bottom)) {
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
                // 多选模式：点击框切换勾选，不进入拖动
                if (multiSelectMode) {
                    val hit = hitBox(event.x, event.y)
                    if (hit >= 0) {
                        checkedFlags[hit] = !checkedFlags[hit]
                        selectedIndex = hit
                        parent?.requestDisallowInterceptTouchEvent(true)
                        onBoxesChanged?.invoke()
                        invalidate()
                        return true
                    }
                    // 空白区域：交由父容器横向滑动
                    return false
                }
                // 1) 命中选中框的手柄？→ 缩放（手动修改）
                if (selectedIndex in boxes.indices) {
                    val h = hitHandle(toScreen(boxes[selectedIndex]), event.x, event.y)
                    if (h != null) {
                        activeHandle = h
                        markManual(selectedIndex)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                // 2) 命中任意框内部？→ 拖动（手动修改）
                val hit = hitBox(event.x, event.y)
                if (hit >= 0) {
                    selectedIndex = hit
                    draggingBoxIndex = hit
                    markManual(hit)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                // 3) 空白区域：滚动模式下交由父容器横向滑动；否则开始新建框（手动）
                if (scrollableMode) return false
                val n = toNormalized(event.x, event.y) ?: return true
                creatingBox = RectF(n.first, n.second, n.first, n.second)
                boxes.add(creatingBox!!)
                manualFlags.add(true)
                checkedFlags.add(false)
                selectedIndex = boxes.size - 1
                activeHandle = Handle.BR
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiSelectMode) return true
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
                    val nl = (b.left + dx).coerceIn(0f, 1f - w)
                    val nt = (b.top + dy).coerceIn(0f, 1f - h)
                    b.set(nl, nt, nl + w, nt + h)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (creatingBox != null) {
                    val b = creatingBox!!
                    if (b.width() < MIN && b.height() < MIN) {
                        boxes.removeAt(selectedIndex)
                        if (selectedIndex in manualFlags.indices) manualFlags.removeAt(selectedIndex)
                        if (selectedIndex in checkedFlags.indices) checkedFlags.removeAt(selectedIndex)
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(-1)
                    }
                    creatingBox = null
                }
                activeHandle = null
                draggingBoxIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
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
