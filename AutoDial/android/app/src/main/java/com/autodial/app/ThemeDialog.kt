package com.autodial.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 主题选择弹窗 - BottomSheetDialog
 * 16 套主题网格 + 7 级亮度模式
 */
class ThemeDialog(private val activity: Activity) : BottomSheetDialog(activity) {

    private var currentThemeId = ThemeManager.loadThemeId(activity)
    private var currentMode = ThemeManager.loadMode(activity)
    private var onConfirm: (() -> Unit)? = null

    private lateinit var grid: LinearLayout
    private lateinit var modeRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val colors = ThemeManager.getColors(activity)
        window?.setBackgroundDrawableResource(android.R.color.transparent)

        val dp = activity.resources.displayMetrics.density

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Color.parseColor(colors.bg))
        }

        // 标题
        root.addView(TextView(activity).apply {
            text = "🎨 选择主题"
            textSize = 18f
            setTextColor(Color.parseColor(colors.goldLight))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        // 主题网格
        grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid)

        // 分割线
        root.addView(View(activity).apply {
            setBackgroundColor(Color.parseColor(colors.bg3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).apply {
                topMargin = (8 * dp).toInt()
                bottomMargin = (12 * dp).toInt()
            }
        })

        // 模式标题
        root.addView(TextView(activity).apply {
            text = "显示模式"
            textSize = 14f
            setTextColor(Color.parseColor(colors.text))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        // 模式选择行
        modeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * dp).toInt()
            }
        }
        root.addView(modeRow)

        // 初始化网格和模式按钮
        rebuildGridColors()
        rebuildModeButtons()

        setContentView(root)
    }

    // ==================== 主题网格 ====================

    private fun rebuildGridColors() {
        grid.removeAllViews()
        val themes = ThemeManager.THEMES
        val columnCount = 4
        val rows = (themes.size + columnCount - 1) / columnCount
        val dp = activity.resources.displayMetrics.density

        for (row in 0 until rows) {
            val rowLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            for (col in 0 until columnCount) {
                val index = row * columnCount + col
                if (index >= themes.size) break

                val theme = themes[index]
                val themeColors = theme.colors[currentMode] ?: theme.colors["dark"]!!

                val card = createThemeCard(activity, theme, themeColors, dp)
                card.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * dp).toInt()
                    marginEnd = (3 * dp).toInt()
                    bottomMargin = (8 * dp).toInt()
                }

                if (theme.id == currentThemeId) {
                    card.isSelected = true
                    highlightCard(card, themeColors, dp)
                }

                card.tag = "theme_card_${theme.id}"
                card.setOnClickListener {
                    currentThemeId = theme.id
                    ThemeManager.saveTheme(activity, currentThemeId, currentMode)
                    rebuildGridColors()
                }

                rowLayout.addView(card)
            }

            grid.addView(rowLayout)
        }
    }

    // ==================== 模式按钮 ====================

    private fun rebuildModeButtons() {
        modeRow.removeAllViews()
        val dp = activity.resources.displayMetrics.density
        val colors = ThemeManager.getColors(activity)

        for (mode in ThemeManager.MODES) {
            val btn = createModeButton(activity, mode, dp, colors)
            btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (2 * dp).toInt()
                marginEnd = (2 * dp).toInt()
            }

            if (mode.key == currentMode) {
                highlightModeButton(btn, colors, dp)
            }

            btn.setOnClickListener {
                currentMode = mode.key
                ThemeManager.saveTheme(activity, currentThemeId, currentMode)
                rebuildModeButtons()
                rebuildGridColors()
            }

            modeRow.addView(btn)
        }
    }

    // ==================== UI 构建 ====================

    private fun createThemeCard(ctx: Context, theme: ThemeInfo, colors: ThemeColors, dp: Float): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        }

        // 4色预览条
        val preview = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (28 * dp).toInt()
            )
        }

        val previewColors = listOf(colors.gold, colors.bg, colors.bg2, colors.text)
        for (c in previewColors) {
            val swatch = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = (1 * dp).toInt()
                    marginEnd = (1 * dp).toInt()
                }
                setBackgroundColor(Color.parseColor(c))
                background = GradientDrawable().apply {
                    cornerRadius = 4 * dp
                }
            }
            preview.addView(swatch)
        }

        val name = TextView(ctx).apply {
            text = theme.name
            textSize = 9f
            setTextColor(Color.parseColor(colors.text2))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
            maxLines = 1
        }

        card.addView(preview)
        card.addView(name)
        return card
    }

    private fun highlightCard(card: View, colors: ThemeColors, dp: Float) {
        card.background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            setColor(Color.parseColor(colors.bg2))
            setStroke((2 * dp).toInt(), Color.parseColor(colors.gold))
        }
    }

    private fun createModeButton(ctx: Context, mode: ThemeManager.ModeInfo, dp: Float, colors: ThemeColors): TextView {
        return TextView(ctx).apply {
            text = "${mode.icon}\n${mode.name}"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(colors.text2))
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(Color.parseColor(colors.bg2))
            }
        }
    }

    private fun highlightModeButton(btn: TextView, colors: ThemeColors, dp: Float) {
        btn.setTextColor(Color.parseColor(colors.bg))
        btn.setTypeface(null, android.graphics.Typeface.BOLD)
        btn.background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            setColor(Color.parseColor(colors.gold))
        }
    }

    companion object {
        fun show(activity: Activity, onConfirm: (() -> Unit)? = null) {
            val dialog = ThemeDialog(activity)
            dialog.onConfirm = onConfirm
            dialog.show()
            dialog.window?.setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
