package io.github.jqssun.gpssetter.ui

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.PrefKeys as K
import io.github.jqssun.gpssetter.utils.PrefManager
import java.math.BigDecimal

typealias Getter = (String) -> Double?

class ValueField(
    val key: String,
    val hint: String,
    val integer: Boolean,
    val min: Double,
    val max: Double,
    val rangeError: String,
    val signed: Boolean = false
)

class ValueRow(val header: String?, val fields: List<ValueField>)

class ValueDialogSpec(
    val title: String,
    val rows: List<ValueRow>,
    val cross: (List<Double?>) -> Map<Int, String>,
    val summary: (Getter) -> String
) {
    val fields: List<ValueField> get() = rows.flatMap { it.fields }
}

/** Dialogs, limits (brief section 7) and summaries for the ten value settings. */
object ValueSettings {

    private const val NOT_SET = "Not set"
    const val DEFAULT_INTERVAL_SECONDS = 5L

    private val DECIMAL = Regex("^-?(\\d+(\\.\\d+)?|\\.\\d+)$")
    private val WHOLE = Regex("^\\d+$")

    fun fmt(d: Double): String {
        val b = BigDecimal.valueOf(d)
        return if (b.signum() == 0) "0" else b.stripTrailingZeros().toPlainString()
    }

    private fun normalize(text: String): String {
        val b = BigDecimal(text)
        return if (b.signum() == 0) "0" else b.stripTrailingZeros().toPlainString()
    }

    fun durationText(total: Long): String {
        val d = total / 86400
        val h = (total % 86400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val parts = ArrayList<String>()
        if (d > 0) parts.add("$d d")
        if (h > 0) parts.add("$h h")
        if (m > 0) parts.add("$m min")
        if (s > 0 || parts.isEmpty()) parts.add("$s s")
        return parts.joinToString(" ")
    }

    private fun none(): Map<Int, String> = emptyMap()

    private fun accField(key: String, hint: String, title: String) =
        ValueField(key, hint, false, 0.0, 100.0, "$title must be between 0 and 100 meters")

    private fun pairRule(v: List<Double?>): Map<Int, String> =
        if (v[0] == null && v[1] != null) mapOf(0 to "Enter this value first") else none()

    private fun pairSummary(unit: String, base: String, rand: String): (Getter) -> String = { g ->
        val b = g(base)
        if (b == null) NOT_SET else {
            val r = g(rand)
            fmt(b) + " $unit" + (if (r != null && r > 0) " ± ${fmt(r)} $unit" else "")
        }
    }

    private fun accuracySpec(title: String, base: String, rand: String) = ValueDialogSpec(
        title,
        listOf(
            ValueRow(null, listOf(accField(base, "Accuracy (meters)", "Accuracy"))),
            ValueRow(null, listOf(accField(rand, "Randomize +/- (meters)", "Randomize")))
        ),
        { pairRule(it) },
        pairSummary("m", base, rand)
    )

    private fun singleSpec(title: String, field: ValueField, unit: String) = ValueDialogSpec(
        title,
        listOf(ValueRow(null, listOf(field))),
        { none() },
        { g -> g(field.key)?.let { fmt(it) + " $unit" } ?: NOT_SET }
    )

    private fun intervalFields(h: String, m: String, s: String) = listOf(
        ValueField(h, "Hours", true, 0.0, 999.0, "Hours must be a whole number from 0 to 999"),
        ValueField(m, "Minutes", true, 0.0, 60.0, "Minutes cannot be more than 60"),
        ValueField(s, "Seconds", true, 0.0, 60.0, "Seconds cannot be more than 60")
    )

    fun intervalTotal(g: Getter, h: String, m: String, s: String): Long? {
        if (g(h) == null && g(m) == null && g(s) == null) return null
        return ((g(h) ?: 0.0) * 3600 + (g(m) ?: 0.0) * 60 + (g(s) ?: 0.0)).toLong()
    }

    private fun intervalRule(v: List<Double?>): Map<Int, String> {
        val custom = v.subList(0, 3)
        val rand = v.subList(3, 6)
        val cSet = custom.any { it != null }
        val rSet = rand.any { it != null }
        fun total(l: List<Double?>) = ((l[0] ?: 0.0) * 3600 + (l[1] ?: 0.0) * 60 + (l[2] ?: 0.0)).toLong()
        if (rSet && !cSet) return mapOf(0 to "Enter the custom interval first")
        if (cSet && total(custom) < 1) return mapOf(2 to "Interval must be at least 1 second")
        if (cSet && rSet && total(rand) > total(custom)) return mapOf(5 to "Randomize interval cannot be longer than the interval")
        return none()
    }

    private fun speedRule(v: List<Double?>): Map<Int, String> {
        val speed = v[0] ?: 0.0
        val r = v[1] ?: return none()
        if (r <= 0.0) return none()
        if (r > speed) return mapOf(1 to "Speed accuracy cannot be higher than the speed")
        if (r > 500.0 - speed) return mapOf(1 to "Speed accuracy cannot raise the speed above 500 m/s")
        return none()
    }

    fun specs(ctx: Context): Map<String, ValueDialogSpec> {
        val map = LinkedHashMap<String, ValueDialogSpec>()
        map["pref_gps_accuracy"] = accuracySpec(ctx.getString(R.string.pref_gps_accuracy), K.GPS_ACC, K.GPS_ACC_RAND)
        map["pref_network_accuracy"] = accuracySpec(ctx.getString(R.string.pref_network_accuracy), K.NET_ACC, K.NET_ACC_RAND)
        map["pref_fused_accuracy"] = accuracySpec(ctx.getString(R.string.pref_fused_accuracy), K.FUSED_ACC, K.FUSED_ACC_RAND)
        map["pref_altitude"] = ValueDialogSpec(
            ctx.getString(R.string.pref_altitude),
            listOf(
                ValueRow(null, listOf(ValueField(K.ALT, "Altitude (meters)", false, -400.0, 5400.0, "Altitude must be between -400 and 5400 meters", true))),
                ValueRow(null, listOf(ValueField(K.ALT_RAND, "Randomize +/- (meters)", false, 0.0, 100.0, "Randomize must be between 0 and 100 meters")))
            ),
            { pairRule(it) },
            pairSummary("m", K.ALT, K.ALT_RAND)
        )
        map["pref_speed"] = ValueDialogSpec(
            ctx.getString(R.string.pref_speed),
            listOf(
                ValueRow(null, listOf(ValueField(K.SPEED, "Speed (m/s)", false, 0.0, 500.0, "Speed must be between 0 and 500 m/s"))),
                ValueRow(null, listOf(ValueField(K.SPEED_RAND, "Randomize +/- (m/s)", false, 0.0, 500.0, "Speed accuracy must be between 0 and 500 m/s")))
            ),
            { speedRule(it) },
            pairSummary("m/s", K.SPEED, K.SPEED_RAND)
        )
        map["pref_horizontal_accuracy"] = singleSpec(
            ctx.getString(R.string.pref_horizontal_accuracy),
            ValueField(K.H_ACC, "Horizontal Accuracy (meters)", false, 0.0, 100.0, "Horizontal accuracy must be between 0 and 100 meters"), "m"
        )
        map["pref_vertical_accuracy"] = singleSpec(
            ctx.getString(R.string.pref_vertical_accuracy),
            ValueField(K.V_ACC, "Vertical Accuracy (meters)", false, 0.0, 100.0, "Vertical accuracy must be between 0 and 100 meters"), "m"
        )
        map["pref_msl"] = ValueDialogSpec(
            ctx.getString(R.string.pref_msl),
            listOf(
                ValueRow(null, listOf(ValueField(K.MSL, "MSL (meters)", false, -400.0, 5400.0, "MSL must be between -400 and 5400 meters", true))),
                ValueRow(null, listOf(ValueField(K.MSL_ACC, "MSL Accuracy (meters)", false, 0.0, 100.0, "MSL accuracy must be between 0 and 100 meters")))
            ),
            { none() },
            { g ->
                val m = g(K.MSL)
                val a = g(K.MSL_ACC)
                if (m == null && a == null) NOT_SET
                else listOfNotNull(m?.let { "${fmt(it)} m" }, a?.let { "accuracy ${fmt(it)} m" }).joinToString(", ")
            }
        )
        map["pref_random_radius"] = singleSpec(
            ctx.getString(R.string.pref_random_radius),
            ValueField(K.RADIUS, "Randomize radius (meters)", false, 0.0, 2000.0, "Radius must be between 0 and 2000 meters"), "m"
        )
        map["pref_random_interval"] = ValueDialogSpec(
            ctx.getString(R.string.pref_random_interval),
            listOf(
                ValueRow("Custom interval for location randomization", intervalFields(K.INT_H, K.INT_M, K.INT_S)),
                ValueRow("Randomize interval for location randomization (+/-)", intervalFields(K.INT_RH, K.INT_RM, K.INT_RS))
            ),
            { intervalRule(it) },
            { g ->
                val c = intervalTotal(g, K.INT_H, K.INT_M, K.INT_S)
                if (c == null) "$NOT_SET (default every ${DEFAULT_INTERVAL_SECONDS} s)" else {
                    val r = intervalTotal(g, K.INT_RH, K.INT_RM, K.INT_RS)
                    "Every " + durationText(c) + (if (r != null && r > 0) " ± " + durationText(r) else "")
                }
            }
        )
        return map
    }

    private fun readValue(key: String): Double? =
        PrefManager.getRaw(key)?.replace(',', '.')?.trim()?.toDoubleOrNull()

    fun summary(spec: ValueDialogSpec): String = spec.summary { readValue(it) }

    private fun parse(f: ValueField, raw: String): Pair<Double?, String?> {
        val t = raw.trim().replace(',', '.')
        if (t.isEmpty()) return Pair(null, null)
        if (f.integer) {
            if (!WHOLE.matches(t)) return Pair(null, "Use whole numbers only")
        } else if (!DECIMAL.matches(t)) {
            return Pair(null, "Enter a valid number")
        }
        val v = t.toDoubleOrNull() ?: return Pair(null, "Enter a valid number")
        if (v < f.min || v > f.max) return Pair(null, f.rangeError)
        return Pair(v, null)
    }

    fun show(ctx: Context, spec: ValueDialogSpec, onSaved: () -> Unit) {
        val density = ctx.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val gap = (8 * density).toInt()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val inflater = LayoutInflater.from(ctx)
        val fields = spec.fields
        val layouts = ArrayList<TextInputLayout>()
        val edits = ArrayList<TextInputEditText>()

        for (row in spec.rows) {
            if (row.header != null) {
                column.addView(TextView(ctx).apply {
                    text = row.header
                    textSize = 15f
                    setPadding(0, gap, 0, gap / 2)
                })
            }
            val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, f) in row.fields.withIndex()) {
                val til = inflater.inflate(R.layout.dialog_value_field, rowView, false) as TextInputLayout
                (til.layoutParams as LinearLayout.LayoutParams).topMargin = gap / 2
                if (i > 0) (til.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                til.hint = f.hint
                val et = til.findViewById<TextInputEditText>(R.id.value_edit)
                var type = InputType.TYPE_CLASS_NUMBER
                if (!f.integer) type = type or InputType.TYPE_NUMBER_FLAG_DECIMAL
                if (f.signed) type = type or InputType.TYPE_NUMBER_FLAG_SIGNED
                et.inputType = type
                val allowed = if (f.integer) "0123456789" else if (f.signed) "0123456789.,-" else "0123456789.,"
                et.filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                    val sb = StringBuilder()
                    for (idx in start until end) if (allowed.indexOf(source[idx]) >= 0) sb.append(source[idx])
                    if (sb.length == end - start) null else sb.toString()
                })
                et.setText(PrefManager.getRaw(f.key) ?: "")
                rowView.addView(til)
                layouts.add(til)
                edits.add(et)
            }
            column.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(spec.title)
            .setView(ScrollView(ctx).apply { addView(column) })
            .setPositiveButton(R.string.dialog_set, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.dialog_clear, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = ArrayList<Double?>()
                var ok = true
                for (i in fields.indices) {
                    layouts[i].error = null
                    val (v, err) = parse(fields[i], edits[i].text?.toString().orEmpty())
                    if (err != null) {
                        layouts[i].error = err
                        ok = false
                    }
                    values.add(v)
                }
                if (ok) {
                    val cross = spec.cross(values)
                    if (cross.isNotEmpty()) {
                        for ((idx, msg) in cross) layouts[idx].error = msg
                        ok = false
                    }
                }
                if (ok) {
                    if (values.all { it == null }) {
                        for (f in fields) PrefManager.setRaw(f.key, null)
                    } else {
                        for (i in fields.indices) {
                            val t = edits[i].text?.toString().orEmpty().trim().replace(',', '.')
                            PrefManager.setRaw(fields[i].key, if (values[i] == null) null else normalize(t))
                        }
                    }
                    onSaved()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                for (f in fields) PrefManager.setRaw(f.key, null)
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
