package com.mipago.ex2center.utils

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mipago.ex2center.core.R

/**
 * App-styled replacement for AlertDialog.Builder (same call shape, so call sites only swap the
 * class name).
 *
 * The platform AlertDialog uses the device default theme, which on the head unit does not match
 * the app's light/dark palette (text ended up the same colour as the background). This dialog is
 * built from the app's own resources: card background, text_primary / text_secondary, 56dp
 * buttons (primary = emerald, destructive = red, others = neutral) and large list rows for
 * choices. Pass a context wrapped by ThemeManager (every Activity of the app already is) so the
 * colours follow the chosen light/dark mode.
 */
class AppDialog(private val context: Context) {

    private data class Button(val label: CharSequence, val action: ((DialogInterface, Int) -> Unit)?, val which: Int)

    private var title: CharSequence? = null
    private var message: CharSequence? = null
    private var custom: View? = null
    private var positive: Button? = null
    private var neutral: Button? = null
    private var negative: Button? = null
    private var destructive = false
    private var items: Array<out CharSequence>? = null
    private var checkedItem = -1
    private var itemListener: ((DialogInterface, Int) -> Unit)? = null
    private var cancelable = true
    private var onCancel: DialogInterface.OnCancelListener? = null
    private var overlay = false
    private val buttonViews = HashMap<Int, View>()

    /** Button view by DialogInterface.BUTTON_* (after create()/show()), e.g. to disable "Hủy". */
    fun getButton(which: Int): View? = buttonViews[which]

    fun setTitle(value: CharSequence) = apply { title = value }
    fun setMessage(value: CharSequence) = apply { message = value }
    fun setView(view: View) = apply { custom = view }
    fun setPositiveButton(label: CharSequence, action: ((DialogInterface, Int) -> Unit)?) =
        apply { positive = Button(label, action, DialogInterface.BUTTON_POSITIVE) }
    fun setNeutralButton(label: CharSequence, action: ((DialogInterface, Int) -> Unit)?) =
        apply { neutral = Button(label, action, DialogInterface.BUTTON_NEUTRAL) }
    fun setNegativeButton(label: CharSequence, action: ((DialogInterface, Int) -> Unit)?) =
        apply { negative = Button(label, action, DialogInterface.BUTTON_NEGATIVE) }

    /** Positive button in red: for irreversible / disruptive actions (uninstall, Wi-Fi off, reboot). */
    fun setDestructive(value: Boolean = true) = apply { destructive = value }

    /** Plain list: tapping a row calls [listener] and closes the dialog. */
    fun setItems(values: Array<out CharSequence>, listener: (DialogInterface, Int) -> Unit) =
        apply { items = values; checkedItem = -1; itemListener = listener }

    /** Single choice: the current value is highlighted; the listener decides when to dismiss. */
    fun setSingleChoiceItems(values: Array<out CharSequence>, checked: Int, listener: (DialogInterface, Int) -> Unit) =
        apply { items = values; checkedItem = checked; itemListener = listener }

    fun setCancelable(value: Boolean) = apply { cancelable = value }
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener) = apply { onCancel = listener }

    /** Show above other apps (TYPE_APPLICATION_OVERLAY), e.g. from an overlay service. */
    fun asOverlay() = apply { overlay = true }

    fun show(): Dialog = create().also { it.show() }

    fun create(): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        onCancel?.let { dialog.setOnCancelListener(it) }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(32), dp(28), dp(32), dp(24))
        }
        title?.let {
            card.addView(text(it, 24f, R.color.text_primary, bold = true))
        }
        message?.let {
            card.addView(text(it, 18f, R.color.text_secondary).apply {
                setLineSpacing(0f, 1.25f)
                setPadding(0, if (title != null) dp(12) else 0, 0, 0)
            })
        }
        custom?.let { view ->
            styleInputs(view)
            (view.parent as? ViewGroup)?.removeView(view)
            card.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) })
        }
        items?.let { values -> card.addView(itemList(dialog, values)) }

        val buttons = listOfNotNull(negative, neutral, positive)
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            buttons.forEachIndexed { i, b ->
                row.addView(button(dialog, b), LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    if (i > 0) marginStart = dp(12)
                })
            }
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(24) })
        }

        dialog.setContentView(card)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(dp(if (items != null) 640 else 720).coerceAtMost(context.resources.displayMetrics.widthPixels - dp(64)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
            if (overlay) w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        return dialog
    }

    // -------------------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun text(value: CharSequence, sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(context.getColor(colorRes))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(dialog: Dialog, b: Button): TextView {
        val primary = b.which == DialogInterface.BUTTON_POSITIVE
        return text(b.label, 17f, if (primary) R.color.text_on_accent else R.color.text_primary, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(
                when {
                    primary && destructive -> R.drawable.bg_btn_delete
                    primary -> R.drawable.bg_btn_active
                    else -> R.drawable.bg_btn_inactive
                }
            )
            if (primary && destructive) setTextColor(context.getColor(R.color.accent_danger))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                b.action?.invoke(dialog, b.which)
                dialog.dismiss()
            }
        }.also { buttonViews[b.which] = it }
    }

    private fun itemList(dialog: Dialog, values: Array<out CharSequence>): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        values.forEachIndexed { i, label ->
            val selected = i == checkedItem
            val row = text(label, 18f, if (selected) R.color.text_on_accent else R.color.text_primary, bold = selected).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(64)
                setPadding(dp(20), dp(12), dp(20), dp(12))
                setBackgroundResource(if (selected) R.drawable.bg_btn_active else R.drawable.bg_btn_inactive)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    itemListener?.invoke(dialog, i)
                    if (checkedItem < 0) dialog.dismiss() // plain list closes; single choice decides itself
                }
            }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (i > 0) topMargin = dp(8) })
        }
        val maxHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(list)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16) }
        return scroll
    }

    /** Text fields / plain TextViews inside a custom view follow the app palette too. */
    private fun styleInputs(view: View) {
        when (view) {
            is EditText -> {
                view.setTextColor(context.getColor(R.color.text_primary))
                view.setHintTextColor(context.getColor(R.color.text_muted))
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.accent_primary))
            }
            is TextView -> if (view.currentTextColor == Color.BLACK) view.setTextColor(context.getColor(R.color.text_primary))
            is ViewGroup -> for (i in 0 until view.childCount) styleInputs(view.getChildAt(i))
        }
    }
}
