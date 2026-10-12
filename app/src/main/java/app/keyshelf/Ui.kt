package app.keyshelf

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.*

object Ui {
    val background = Color.rgb(40, 44, 52)
    val surface = Color.rgb(33, 37, 43)
    val text = Color.rgb(230, 230, 230)
    val muted = Color.rgb(157, 165, 180)
    val accent = Color.rgb(97, 175, 239)
    val favorite = Color.rgb(209, 154, 102)
    fun typeColor(kind: String) = when (kind) {
        "account", "legacy" -> Color.rgb(152, 195, 121)
        "api" -> Color.rgb(123, 192, 246)
        "other" -> Color.rgb(198, 160, 220)
        else -> muted
    }
    fun typeName(kind: String) = when (kind) {
        "account", "legacy" -> "Аккаунт"
        "api" -> "API"
        else -> "Другое"
    }
    fun typeSurface(kind: String): Int {
        val color = typeColor(kind)
        fun channel(base: Int, tint: Int) = kotlin.math.round(base * .94 + tint * .06).toInt()
        return Color.rgb(channel(Color.red(surface), Color.red(color)), channel(Color.green(surface), Color.green(color)), channel(Color.blue(surface), Color.blue(color)))
    }
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()
    fun panel(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16))
        setBackgroundColor(Ui.background)
    }
    fun label(c: Context, value: String, size: Float = 16f, muted: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; setTextColor(if (muted) Ui.muted else Ui.text)
        setPadding(0, dp(c, 8), 0, dp(c, 8))
    }
    fun input(c: Context, hintText: String, value: String = "", secret: Boolean = false) = KeyboardInput(c).apply {
        hint = hintText; setText(value); setTextColor(Ui.text); setHintTextColor(muted); textSize = 16f
        setSingleLine(true); minimumHeight = dp(c, 52)
        background = rounded(c).apply { setStroke(dp(c, 1).coerceAtLeast(1), Color.rgb(62, 68, 81)) }
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) }
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (secret) imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    fun button(c: Context, value: String, action: () -> Unit) = Button(c).apply {
        text = value; isAllCaps = false; setTextColor(Ui.text); textSize = 15f
        minimumHeight = dp(c, 48); setOnClickListener { action() }
        dialogAction(this, primary = value in listOf("Открыть хранилище", "Создать хранилище", "Разблокировать"))
        layoutParams = LinearLayout.LayoutParams(-1, dp(c, 48)).apply { bottomMargin = dp(c, 8) }
    }
    fun card(c: Context) = panel(c).apply {
        background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(c, 12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 12) }
    }
    fun iconButton(c: Context, icon: Int, description: String, action: () -> Unit) = ImageButton(c).apply {
        setImageResource(icon); contentDescription = description; tooltipText = description
        imageTintList = android.content.res.ColorStateList.valueOf(text)
        val feedback = android.util.TypedValue()
        c.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, feedback, true)
        setBackgroundResource(feedback.resourceId)
        val inset = dp(c, 12); setPadding(inset, inset, inset, inset)
        layoutParams = LinearLayout.LayoutParams(dp(c, 48), dp(c, 48))
        setOnClickListener { action() }
    }
    fun scroll(c: Context, content: View) = ScrollView(c).apply { isFillViewport = true; addView(content) }

    fun rounded(c: Context, color: Int = surface, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radius).toFloat()
    }
    fun selector(c: Context, values: List<String>, description: String, open: (() -> Unit)? = null) = object : Spinner(c) {
        override fun performClick(): Boolean {
            if (open == null) return super.performClick()
            open(); return true
        }
    }.apply {
        contentDescription = description; minimumHeight = dp(c, 48); background = rounded(c)
        setPadding(0, 0, 0, 0)
        adapter = object : ArrayAdapter<String>(c, android.R.layout.simple_spinner_dropdown_item, values) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View = label(c, values[position], 14f).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = dp(c, 48)
                setPadding(dp(c, 12), 0, dp(c, 12), 0); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                val arrow = object : android.graphics.drawable.Drawable() {
                    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = Ui.muted; style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    override fun draw(canvas: android.graphics.Canvas) {
                        paint.strokeWidth = bounds.width() / 12f
                        val path = android.graphics.Path().apply {
                            moveTo(bounds.left + bounds.width() * .25f, bounds.top + bounds.height() * .375f)
                            lineTo(bounds.left + bounds.width() * .5f, bounds.top + bounds.height() * .625f)
                            lineTo(bounds.left + bounds.width() * .75f, bounds.top + bounds.height() * .375f)
                        }
                        canvas.drawPath(path, paint)
                    }
                    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
                    @Suppress("DEPRECATION")
                    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                }
                arrow.setBounds(0, 0, dp(c, 20), dp(c, 20)); compoundDrawablePadding = dp(c, 8)
                setCompoundDrawables(null, null, arrow, null)
            }
        }
    }
    fun styleFavorite(button: ImageButton, selected: Boolean) {
        val c = button.context
        button.isSelected = selected
        button.setImageResource(if (selected) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        button.imageTintList = android.content.res.ColorStateList.valueOf(if (selected) favorite else text)
        val shape = rounded(c, if (selected) Color.rgb(62, 52, 47) else surface).apply {
            if (selected) setStroke(dp(c, 1).coerceAtLeast(1), favorite)
        }
        button.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(30, 255, 255, 255)), shape, null)
    }
    fun apiBadge(c: Context, paid: Boolean, full: Boolean = false) = label(c,
        if (full) { if (paid) "Платный API" else "Бесплатный API" } else { if (paid) "$" else "⓪" }, 12f).apply {
        contentDescription = if (paid) "Платный API" else "Бесплатный API"
        val color = if (paid) Ui.favorite else typeColor("account")
        setTextColor(color); gravity = android.view.Gravity.CENTER
        setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4))
        background = rounded(c, if (paid) Color.rgb(62, 52, 47) else Color.rgb(42, 55, 47), 8)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    fun recordCard(c: Context, title: String, service: String, favorite: Boolean, kind: String, paid: Boolean = false, selected: Boolean = false, open: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(c, 16), dp(c, 12), dp(c, 12), dp(c, 12))
        isSelected = selected
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(30, 255, 255, 255)), rounded(c, typeSurface(kind)).apply {
            if (selected) setStroke(dp(c, 2).coerceAtLeast(1), accent)
        }, null)
        isClickable = true; isFocusable = true; contentDescription = "Открыть запись $title"
        setOnClickListener { open() }
        addView(View(c).apply { background = rounded(c, typeColor(kind), 2); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(c, 3), dp(c, 36)).apply { marginEnd = dp(c, 12) })
        val names = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label(c, title, 16f).apply {
            setPadding(0, 0, 0, 0); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(typeColor(kind))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        names.addView(label(c, "$service · ${typeName(kind)}", 13f, muted = true).apply {
            setPadding(0, dp(c, 5), 0, 0); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        if (kind == "api") addView(apiBadge(c, paid), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(c, 8); marginEnd = dp(c, 8) })
        if (favorite) addView(label(c, "★", 18f).apply { setTextColor(Ui.favorite); setPadding(dp(c, 12), 0, dp(c, 8), 0); contentDescription = "Избранное" })
        addView(ImageView(c).apply { setImageResource(R.drawable.ic_chevron); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 10) }
    }

    fun recordDialog(dialog: android.app.AlertDialog) {
        val c = dialog.context
        dialog.window?.apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(background); cornerRadius = dp(c, 20).toFloat() })
            decorView.clipToOutline = true
            setLayout(minOf(c.resources.displayMetrics.widthPixels - dp(c, 32), dp(c, 480)), -2)
        }
        for (name in listOf("topPanel", "customPanel", "contentPanel")) {
            val id = c.resources.getIdentifier(name, "id", "android")
            if (id != 0) dialog.findViewById<View>(id)?.setBackgroundColor(background)
        }
        val buttons = listOf(android.app.AlertDialog.BUTTON_NEUTRAL, android.app.AlertDialog.BUTTON_NEGATIVE,
            android.app.AlertDialog.BUTTON_POSITIVE).mapNotNull { which ->
            dialog.getButton(which)?.takeIf { it.visibility == View.VISIBLE }?.let { which to it }
        }
        val old = buttons.firstOrNull()?.second?.parent as? android.view.ViewGroup ?: return
        val parent = old.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(old)
        val row = LinearLayout(c).apply {
            // AlertDialogLayout recognises its panels by ID and reserves button height.
            id = old.id
            minimumHeight = dp(c, 68)
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 12)); setBackgroundColor(Ui.background)
        }
        buttons.forEach { (which, button) ->
            (button.parent as android.view.ViewGroup).removeView(button)
            val primary = which == android.app.AlertDialog.BUTTON_POSITIVE
            dialogAction(button, primary, which == android.app.AlertDialog.BUTTON_NEUTRAL || button.text.toString() == "Удалить")
            row.addView(button, LinearLayout.LayoutParams(0, dp(c, 48), 1f).apply { if (which != buttons.first().first) leftMargin = dp(c, 8) })
        }
        val params = old.layoutParams
        parent.removeView(old); parent.addView(row, index, params)
    }

    fun dialogAction(button: Button, primary: Boolean = false, danger: Boolean = false) {
        val c = button.context
        button.isAllCaps = false; button.textSize = 14f; button.setSingleLine(true)
        button.ellipsize = android.text.TextUtils.TruncateAt.END
        button.minWidth = 0; button.minimumWidth = 0; button.minHeight = dp(c, 48); button.minimumHeight = dp(c, 48)
        button.setPadding(dp(c, 4), 0, dp(c, 4), 0); button.backgroundTintList = null
        val red = Color.rgb(224, 108, 117)
        val fill = when { danger -> Color.rgb(63, 42, 48); primary -> accent; else -> Color.rgb(62, 68, 81) }
        val border = when { danger -> Color.rgb(119, 68, 76); primary -> Color.rgb(123, 192, 246); else -> Color.rgb(82, 90, 105) }
        button.setTextColor(when { danger -> red; primary -> surface; else -> text })
        val base = rounded(c, fill, 10).apply { setStroke(dp(c, 1).coerceAtLeast(1), border) }
        button.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(35, 255, 255, 255)), base, null)
    }

    fun settingRow(c: Context, icon: Int, title: String, description: String = title, subtitle: String = "", action: (() -> Unit)? = null): LinearLayout = LinearLayout(c).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL; minimumHeight = dp(c, 64)
        setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
        addView(ImageView(c).apply {
            setImageResource(icon)
            imageTintList = if (icon == R.drawable.ic_keyshelf || icon == R.drawable.ic_keyshelf_mark) null else android.content.res.ColorStateList.valueOf(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)).apply { marginEnd = dp(c, 12) })
        val names = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label(c, title, 15f).apply { setPadding(0, 0, 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (subtitle.isNotEmpty()) names.addView(label(c, subtitle, 12f, muted = true).apply {
            tag = "setting-subtitle"
            setPadding(0, dp(c, 4), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            contentDescription = description; isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(30, 255, 255, 255)), rounded(c), null)
            addView(ImageView(c).apply { setImageResource(R.drawable.ic_chevron); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)).apply { marginStart = dp(c, 8) })
            setOnClickListener { action() }
        }
    }
}

fun android.app.AlertDialog.Builder.showStyled(): android.app.AlertDialog = create().apply {
    show(); Ui.recordDialog(this)
}
