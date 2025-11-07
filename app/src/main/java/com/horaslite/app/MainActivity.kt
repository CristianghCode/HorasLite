package com.horaslite.app

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.horaslite.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private var weekOffset = 0 // 0 = semana actual; -1 anterior; +1 siguiente; etc.
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("HorasLite", Context.MODE_PRIVATE) }
    private val dayNames = arrayOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.weekLabel.text = currentWeekLabel()
        populateDays()
        Toast.makeText(this, "💖 ¡Hola mamá! Tu semana empieza fuerte ☕", Toast.LENGTH_LONG).show()


        binding.resetButton.setOnClickListener {
            resetWeek()
            populateDays()
        }

        binding.configureRatesButton.setOnClickListener {
            val (year, month) = currentYearMonthForWeek()
            showRatesDialog(year, month)
        }

        binding.prevWeekButton.setOnClickListener {
            weekOffset--
            refreshWeek()
        }

        binding.nextWeekButton.setOnClickListener {
            weekOffset++
            refreshWeek()
        }

        binding.datePickerButton.setOnClickListener {
            showDatePicker()
        }
    }

    private fun refreshWeek() {
        binding.weekLabel.text = currentWeekLabel()
        populateDays()
    }

    private fun currentWeekId(): String {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset) // 👈 ESTA LÍNEA ES LA CLAVE
        val year = cal.get(Calendar.YEAR)
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        return "$year-$week"
    }

    private fun currentWeekLabel(): String {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset) // 👈 Aquí también
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val start = df.format(cal.time)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        val end = df.format(cal.time)
        return "Semana $start – $end"
    }

    private fun showDatePicker() {
        val base = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
        }
        val current = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            add(Calendar.WEEK_OF_YEAR, weekOffset)
        }

        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    firstDayOfWeek = Calendar.MONDAY
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    normalizeToStartOfDay()
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                }

                val baseMonday = base.clone() as Calendar
                baseMonday.normalizeToStartOfDay()
                baseMonday.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

                val diffWeeks = ((selected.timeInMillis - baseMonday.timeInMillis) / (7 * 24 * 60 * 60 * 1000L)).toInt()
                weekOffset = diffWeeks
                refreshWeek()
            },
            current.get(Calendar.YEAR),
            current.get(Calendar.MONTH),
            current.get(Calendar.DAY_OF_MONTH)
        )

        dialog.show()
    }

    private fun Calendar.normalizeToStartOfDay() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun key(prefix: String, dayIndex: Int) = "${currentWeekId()}:$prefix:$dayIndex"

    // Intervals stored as JSON array per day: [{s:intMinutes, e:intMinutes, extra:boolean, src:"manual"|"punch"}]
    private fun getIntervals(dayIndex: Int): JSONArray {
        val raw = prefs.getString(key("intervals", dayIndex), "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }
    private fun saveIntervals(dayIndex: Int, arr: JSONArray) {
        prefs.edit().putString(key("intervals", dayIndex), arr.toString()).apply()
    }

    private fun getOngoingStartMillis(dayIndex: Int): Long = prefs.getLong(key("ongoing", dayIndex), 0L)
    private fun setOngoingStartMillis(dayIndex: Int, value: Long) { prefs.edit().putLong(key("ongoing", dayIndex), value).apply() }

    private fun populateDays() {
        val container: LinearLayout = binding.daysContainer
        container.removeAllViews()

        var weeklyTotal = 0L
        var weeklyManualExtra = 0L
        var weeklyAutoExtra = 0L

        val (targetYear, targetMonth) = currentYearMonthForWeek()

        for (i in 0 until 7) {
            val item = LayoutInflater.from(this).inflate(R.layout.item_day, container, false) as LinearLayout
            val dayName = item.findViewById<TextView>(R.id.dayName)
            val intervalsContainer = item.findViewById<LinearLayout>(R.id.intervalsContainer)
            val totalTime = item.findViewById<TextView>(R.id.totalTime)
            val extraAutoTime = item.findViewById<TextView>(R.id.extraAutoTime)
            val extraManualTime = item.findViewById<TextView>(R.id.extraManualTime)
            val buttonAddInterval = item.findViewById<MaterialButton>(R.id.buttonAddInterval)
            val buttonStartStop = item.findViewById<MaterialButton>(R.id.buttonStartStop)

            buttonAddInterval.icon = AppCompatResources.getDrawable(this, R.drawable.ic_add_interval)

            dayName.text = dayNames[i]

            // Render intervals
            val intervals = getIntervals(i)
            intervalsContainer.removeAllViews()
            var dayTotalMillis = 0L
            var manualExtraMillis = 0L

            for (idx in 0 until intervals.length()) {
                val it = intervals.getJSONObject(idx)
                val s = it.optInt("s", 0)
                val e = it.optInt("e", 0)
                val extra = it.optBoolean("extra", false)
                val dur = max(0, e - s) * 60_000L
                dayTotalMillis += dur
                if (extra) manualExtraMillis += dur

                val intervalRow = LinearLayout(this)
                intervalRow.orientation = LinearLayout.HORIZONTAL
                intervalRow.setPadding(0, dp(4), 0, dp(4))
                intervalRow.gravity = Gravity.CENTER_VERTICAL

                val tv = TextView(this)
                tv.text = intervalLabel(s, e, dur, extra)
                tv.setBackgroundResource(R.drawable.bg_interval_chip)
                val tvLayoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                tvLayoutParams.marginEnd = dp(8)
                tv.layoutParams = tvLayoutParams
                tv.setOnLongClickListener { v ->
                    // Toggle manual EXTRA flag
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    val newObj = JSONObject((v.tag as? String) ?: "{}")
                    val newExtra = !newObj.optBoolean("extra", false)
                    newObj.put("extra", newExtra)
                    intervals.put(idx, newObj)
                    saveIntervals(i, intervals)
                    populateDays()
                    true
                }
                tv.tag = it.toString()

                val deleteButton = ImageButton(this)
                val typedValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
                deleteButton.setBackgroundResource(typedValue.resourceId)
                deleteButton.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_delete_interval))
                deleteButton.contentDescription = getString(R.string.delete_interval)
                deleteButton.adjustViewBounds = true
                deleteButton.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val deleteParams = LinearLayout.LayoutParams(dp(36), dp(36))
                deleteButton.layoutParams = deleteParams
                deleteButton.setPadding(dp(6), dp(6), dp(6), dp(6))
                val index = idx
                deleteButton.setOnClickListener {
                    intervals.remove(index)
                    saveIntervals(i, intervals)
                    populateDays()
                }

                intervalRow.addView(tv)
                intervalRow.addView(deleteButton)
                intervalsContainer.addView(intervalRow)
            }

            // Ongoing punch
            val ongoing = getOngoingStartMillis(i)
            if (ongoing > 0L) {
                buttonStartStop.text = getString(R.string.stop)
                buttonStartStop.icon = AppCompatResources.getDrawable(this, R.drawable.ic_stop_circle)
            } else {
                buttonStartStop.text = getString(R.string.start)
                buttonStartStop.icon = AppCompatResources.getDrawable(this, R.drawable.ic_play_circle)
            }
            buttonStartStop.setOnClickListener {
                val now = System.currentTimeMillis()
                if (getOngoingStartMillis(i) > 0L) {
                    // Stop -> close interval in minutes-of-day
                    val cal = Calendar.getInstance()
                    val endMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    val startMillis = getOngoingStartMillis(i)
                    setOngoingStartMillis(i, 0L)

                    val elapsedMin = ((now - startMillis) / 60000L).toInt()
                    var startMinutes = endMinutes - elapsedMin
                    if (startMinutes < 0) startMinutes = 0
                    if (startMinutes > endMinutes) startMinutes = endMinutes

                    val arr = getIntervals(i)
                    val obj = JSONObject()
                    obj.put("s", startMinutes)
                    obj.put("e", endMinutes)
                    obj.put("extra", false)
                    obj.put("src", "punch")
                    arr.put(obj)
                    saveIntervals(i, arr)
                    populateDays()
                } else {
                    setOngoingStartMillis(i, now)
                    populateDays()
                }
            }

            buttonAddInterval.setOnClickListener {
                addManualIntervalDialog(i)
            }

            // Extras union rule: manual extra + auto extra computed on the remaining "normal" pool
            val autoExtra = max(0L, (dayTotalMillis - manualExtraMillis) - 8 * 60 * 60 * 1000L)

            totalTime.text = "Total: " + formatDuration(dayTotalMillis)
            extraAutoTime.text = "Extra auto: " + formatDuration(autoExtra)
            extraManualTime.text = "Extra manual: " + formatDuration(manualExtraMillis)

            weeklyTotal += dayTotalMillis
            weeklyManualExtra += manualExtraMillis
            weeklyAutoExtra += autoExtra

            container.addView(item)
        }

        val weeklyTotalExtras = weeklyManualExtra + weeklyAutoExtra
        val weeklyNormal = weeklyTotal - weeklyTotalExtras
        val (normalRate, extraRate) = getMonthlyRates(targetYear, targetMonth)
        val weeklyNormalPay = durationToHours(weeklyNormal) * normalRate
        val weeklyExtraPay = durationToHours(weeklyTotalExtras) * extraRate
        val weeklyPayText = if (normalRate == 0.0 && extraRate == 0.0) {
            getString(R.string.rates_hint_message)
        } else {
            "Ingresos: normales ${formatCurrency(weeklyNormalPay)}, extra ${formatCurrency(weeklyExtraPay)} (total ${formatCurrency(weeklyNormalPay + weeklyExtraPay)})"
        }
        binding.summary.text = "${getString(R.string.normal_week)} ${formatDuration(weeklyNormal)}   " +
                "${getString(R.string.extra_week)} ${formatDuration(weeklyTotalExtras)}   " +
                "${getString(R.string.total_week)} ${formatDuration(weeklyTotal)}\n" +
                weeklyPayText

        updateMonthlySummary()

    }

    private fun updateMonthlySummary() {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset)
        val targetYear = cal.get(Calendar.YEAR)
        val targetMonth = cal.get(Calendar.MONTH)

        var monthTotalMillis = 0L
        var monthExtrasMillis = 0L

        // Recorremos las semanas del mes
        val tempCal = Calendar.getInstance()
        tempCal.firstDayOfWeek = Calendar.MONDAY
        tempCal.set(Calendar.YEAR, targetYear)
        tempCal.set(Calendar.MONTH, targetMonth)
        tempCal.set(Calendar.DAY_OF_MONTH, 1)

        // Empezamos desde el lunes de la primera semana del mes
        while (tempCal.get(Calendar.MONTH) == targetMonth) {
            val weekId = "${tempCal.get(Calendar.YEAR)}-${tempCal.get(Calendar.WEEK_OF_YEAR)}"

            // Días de esa semana
            for (dayIndex in 0 until 7) {
                val dayCal = tempCal.clone() as Calendar
                dayCal.add(Calendar.DAY_OF_MONTH, dayIndex)
                if (dayCal.get(Calendar.MONTH) != targetMonth) continue  // solo días del mes real

                val raw = prefs.getString("$weekId:intervals:$dayIndex", "[]") ?: continue
                val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }

                var dayTotalMillis = 0L
                var manualExtraMillis = 0L

                for (j in 0 until arr.length()) {
                    val it = arr.getJSONObject(j)
                    val s = it.optInt("s", 0)
                    val e = it.optInt("e", 0)
                    val extra = it.optBoolean("extra", false)
                    val dur = max(0, e - s) * 60_000L
                    dayTotalMillis += dur
                    if (extra) manualExtraMillis += dur
                }

                val autoExtraMillis = max(0L, (dayTotalMillis - manualExtraMillis) - 8 * 60 * 60 * 1000L)

                monthTotalMillis += dayTotalMillis
                monthExtrasMillis += manualExtraMillis + autoExtraMillis
            }


            // Avanzamos a la siguiente semana
            tempCal.add(Calendar.WEEK_OF_YEAR, 1)
        }

        val normalMillis = monthTotalMillis - monthExtrasMillis
        val (normalRate, extraRate) = getMonthlyRates(targetYear, targetMonth)
        val normalPay = durationToHours(normalMillis) * normalRate
        val extraPay = durationToHours(monthExtrasMillis) * extraRate
        val totalPay = normalPay + extraPay

        if (monthTotalMillis > 0) {
            binding.monthlySummary.visibility = View.VISIBLE
            val monthName = SimpleDateFormat("MMMM", Locale("es", "ES"))
                .format(cal.time)
                .replaceFirstChar { it.uppercase() }

            val mensajeCariñoso = when {
                monthExtrasMillis > 0L -> "🫶 ¡Qué trabajadora, mamá!"
                monthTotalMillis == 0L -> "💤 Descansa, te lo has ganado."
                else -> "🌼 Buen equilibrio, mamá."
            }

            val paySummary = if (normalRate == 0.0 && extraRate == 0.0) {
                getString(R.string.rates_hint_message)
            } else {
                "Ingresos: normales ${formatCurrency(normalPay)}, extra ${formatCurrency(extraPay)} (total ${formatCurrency(totalPay)})"
            }

            binding.monthlySummary.text =
                "$monthName: ${formatDuration(normalMillis)} normales, " +
                        "${formatDuration(monthExtrasMillis)} extra (total ${formatDuration(monthTotalMillis)})\n" +
                        "$paySummary\n" +
                        mensajeCariñoso

        } else {
            binding.monthlySummary.visibility = View.GONE
        }
    }



    private fun intervalLabel(sMin: Int, eMin: Int, durMillis: Long, extra: Boolean): String {
        val s = formatHM(sMin)
        val e = formatHM(eMin)
        val d = formatDuration(durMillis)
        val flag = if (extra) " [EXTRA]" else ""
        return "$s - $e  ($d)$flag  (mantén pulsado para alternar EXTRA)"
    }

    private fun addManualIntervalDialog(dayIndex: Int) {
        var startMins = 9 * 60
        var endMins = 17 * 60

        TimePickerDialog(this, { _, sh, sm ->
            startMins = sh * 60 + sm
            TimePickerDialog(this, { _, eh, em ->
                endMins = eh * 60 + em
                if (endMins <= startMins) {
                    Toast.makeText(this, "El fin debe ser después del inicio", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }
                AlertDialog.Builder(this)
                    .setTitle("¿Contar como horas EXTRA?")
                    .setMessage("Puedes marcar este intervalo como horas extra manualmente.")
                    .setPositiveButton("Sí") { _, _ -> addInterval(dayIndex, startMins, endMins, true) }
                    .setNegativeButton("No") { _, _ -> addInterval(dayIndex, startMins, endMins, false) }
                    .show()
            }, endMins / 60, endMins % 60, true).show()
        }, startMins / 60, startMins % 60, true).show()
    }

    private fun addInterval(dayIndex: Int, start: Int, end: Int, extra: Boolean) {
        val arr = getIntervals(dayIndex)
        val obj = JSONObject()
        obj.put("s", start)
        obj.put("e", end)
        obj.put("extra", extra)
        obj.put("src", "manual")
        arr.put(obj)
        saveIntervals(dayIndex, arr)
        populateDays()
    }

    private fun currentYearMonthForWeek(): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset)
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
    }

    private fun monthKey(year: Int, month: Int): String = String.format(Locale.US, "%04d-%02d", year, month + 1)

    private fun getMonthlyRates(year: Int, month: Int): Pair<Double, Double> {
        val key = monthKey(year, month)
        val normal = prefs.getString("rate:$key:normal", null)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val extra = prefs.getString("rate:$key:extra", null)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        return normal to extra
    }

    private fun saveMonthlyRates(year: Int, month: Int, normal: Double, extra: Double) {
        val key = monthKey(year, month)
        prefs.edit()
            .putString("rate:$key:normal", normal.toString())
            .putString("rate:$key:extra", extra.toString())
            .apply()
    }

    private fun showRatesDialog(year: Int, month: Int) {
        val (currentNormal, currentExtra) = getMonthlyRates(year, month)
        val context = this

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
        }

        val normalInput = EditText(context).apply {
            hint = getString(R.string.normal_rate_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (currentNormal > 0.0) setText(formatRateInput(currentNormal))
        }

        val extraInput = EditText(context).apply {
            hint = getString(R.string.extra_rate_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (currentExtra > 0.0) setText(formatRateInput(currentExtra))
        }

        layout.addView(normalInput)
        layout.addView(extraInput)

        val monthName = SimpleDateFormat("MMMM yyyy", Locale("es", "ES"))
            .format(Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
            }.time)
            .replaceFirstChar { it.uppercase() }

        AlertDialog.Builder(context)
            .setTitle("Tarifas $monthName")
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val normalRate = normalInput.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val extraRate = extraInput.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                saveMonthlyRates(year, month, normalRate, extraRate)
                populateDays()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatRateInput(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun durationToHours(durationMillis: Long): Double = durationMillis / 3_600_000.0

    private fun formatCurrency(value: Double): String {
        val format = NumberFormat.getCurrencyInstance(Locale("es", "ES"))
        return format.format(value)
    }

    private fun formatHM(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, m)
    }

    private fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60000L
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return String.format(Locale.getDefault(), "%d:%02d", h, m)
    }

    private fun resetWeek() {
        val editor = prefs.edit()
        for (i in 0 until 7) {
            editor.remove(key("intervals", i))
            editor.remove(key("ongoing", i))
        }
        editor.apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
