package com.horaslite.app

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.Color
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
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.horaslite.app.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import android.os.Build
import android.util.Patterns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.AdaptiveIconDrawable
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import android.util.Log
import androidx.annotation.RequiresApi
import com.horaslite.app.BuildConfig

class MainActivity : AppCompatActivity() {

    private var weekOffset = 0 // 0 = semana actual; -1 anterior; +1 siguiente; etc.
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("HorasLite", Context.MODE_PRIVATE) }
    private val dayNames = arrayOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
    @RequiresApi(Build.VERSION_CODES.O)
    private val pdfDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("es", "ES"))
    private val csvDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private val reportNotificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val reportIconBitmap: Bitmap by lazy { loadReportIconBitmap() }

    private val reportChannelId = "monthly_report"

    private enum class ReportFormat { PDF, CSV }

    data class DailyReport(
        val date: LocalDate,
        val normalMillis: Long,
        val extraMillis: Long,
        val totalMillis: Long
    )

    private data class MonthlyReport(
        val year: Int,
        val month: Int,
        val daily: List<DailyReport>,
        val normalRate: Double,
        val extraRate: Double,
        val totalNormalMillis: Long,
        val totalExtraMillis: Long
    ) {
        private fun durationToHours(durationMillis: Long): Double = durationMillis / 3_600_000.0

        val totalMillis: Long get() = totalNormalMillis + totalExtraMillis
        val normalPay: Double get() = durationToHours(totalNormalMillis) * normalRate
        val extraPay: Double get() = durationToHours(totalExtraMillis) * extraRate
        val totalPay: Double get() = normalPay + extraPay
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createReportNotificationChannel()

        binding.weekLabel.text = currentWeekLabel()
        populateDays()
        Updater.checkForUpdate(this)
        Toast.makeText(this, "💖 ¡Hola mamá! Tu semana empieza fuerte ☕", Toast.LENGTH_LONG).show()


        binding.resetButton.setOnClickListener {
            resetWeek()
            populateDays()
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
        val currentMonday = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(weekOffset.toLong())

        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                val selectedMonday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

                val baseMonday = LocalDate.now()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

                val diffDays = ChronoUnit.DAYS.between(baseMonday, selectedMonday)
                weekOffset = Math.floorDiv(diffDays, 7L).toInt()
                refreshWeek()
            },
            currentMonday.year,
            currentMonday.monthValue - 1,
            currentMonday.dayOfMonth
        )

        dialog.datePicker.firstDayOfWeek = Calendar.MONDAY
        dialog.datePicker.updateDate(currentMonday.year, currentMonday.monthValue - 1, currentMonday.dayOfMonth)
        dialog.show()
    }

    private fun key(prefix: String, dayIndex: Int) = "${currentWeekId()}:$prefix:$dayIndex"

    // Intervals stored as JSON array per day: [{s:intMinutes, e:intMinutes, extra:boolean, src:"manual"|"punch"}]
    // Quantity blocks: {type:"quantity", minutes:int, extra:boolean, src:"quantity"}
    private fun getIntervals(dayIndex: Int): JSONArray {
        val raw = prefs.getString(key("intervals", dayIndex), "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }
    private fun saveIntervals(dayIndex: Int, arr: JSONArray) {
        prefs.edit().putString(key("intervals", dayIndex), arr.toString()).apply()
    }

    private fun getStoredIntervals(weekId: String, dayIndex: Int): JSONArray {
        val raw = prefs.getString("$weekId:intervals:$dayIndex", "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun calculateDayTotals(intervals: JSONArray): Triple<Long, Long, Long> {
        var totalMillis = 0L
        var manualExtraMillis = 0L

        for (idx in 0 until intervals.length()) {
            val obj = intervals.getJSONObject(idx)
            val duration = intervalDurationMillis(obj)
            totalMillis += duration
            if (obj.optBoolean("extra", false)) {
                manualExtraMillis += duration
            }
        }

        val autoExtraMillis = max(0L, (totalMillis - manualExtraMillis) - 8 * 60 * 60 * 1000L)
        return Triple(totalMillis, manualExtraMillis, autoExtraMillis)
    }

    private fun getOngoingStartMillis(dayIndex: Int): Long = prefs.getLong(key("ongoing", dayIndex), 0L)
    private fun setOngoingStartMillis(dayIndex: Int, value: Long) { prefs.edit().putLong(key("ongoing", dayIndex), value).apply() }

    private fun populateDays() {
        val container: LinearLayout = binding.daysContainer
        container.removeAllViews()

        var weeklyTotal = 0L
        var weeklyManualExtra = 0L
        var weeklyAutoExtra = 0L

        val monthsInWeek = monthsInCurrentWeek()
        val (targetYear, targetMonth) = monthsInWeek.first()

        for (i in 0 until 7) {
            val item = LayoutInflater.from(this).inflate(R.layout.item_day, container, false) as LinearLayout
            val dayName = item.findViewById<TextView>(R.id.dayName)
            val intervalsContainer = item.findViewById<LinearLayout>(R.id.intervalsContainer)
            val totalTime = item.findViewById<TextView>(R.id.totalTime)
            val extraAutoTime = item.findViewById<TextView>(R.id.extraAutoTime)
            val extraManualTime = item.findViewById<TextView>(R.id.extraManualTime)
            val chipAddInterval = item.findViewById<Chip>(R.id.chipAddInterval)
            val chipAddQuantity = item.findViewById<Chip>(R.id.chipAddQuantity)
            val chipStartStop = item.findViewById<Chip>(R.id.chipStartStop)

            chipAddInterval.chipIcon = AppCompatResources.getDrawable(this, R.drawable.ic_add_interval)
            chipAddQuantity.chipIcon = AppCompatResources.getDrawable(this, R.drawable.ic_add_interval)

            dayName.text = dayNames[i]

            // Render intervals
            val intervals = getIntervals(i)
            intervalsContainer.removeAllViews()
            var dayTotalMillis = 0L
            var manualExtraMillis = 0L

            for (idx in 0 until intervals.length()) {
                val it = intervals.getJSONObject(idx)
                val dur = intervalDurationMillis(it)
                val extra = it.optBoolean("extra", false)
                dayTotalMillis += dur
                if (extra) manualExtraMillis += dur

                val intervalRow = LinearLayout(this)
                intervalRow.orientation = LinearLayout.HORIZONTAL
                intervalRow.setPadding(0, dp(4), 0, dp(4))
                intervalRow.gravity = Gravity.CENTER_VERTICAL

                val tv = TextView(this)
                tv.text = intervalLabel(it, dur)
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
                chipStartStop.text = getString(R.string.stop)
                chipStartStop.chipIcon = AppCompatResources.getDrawable(this, R.drawable.ic_stop_circle)
                chipStartStop.contentDescription = getString(R.string.stop)
            } else {
                chipStartStop.text = getString(R.string.start)
                chipStartStop.chipIcon = AppCompatResources.getDrawable(this, R.drawable.ic_play_circle)
                chipStartStop.contentDescription = getString(R.string.start)
            }
            chipStartStop.setOnClickListener {
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

            chipAddInterval.setOnClickListener {
                addManualIntervalDialog(i)
            }

            chipAddQuantity.setOnClickListener {
                addQuantityDialog(i)
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

        updateRatesButtons(monthsInWeek)
        updateMonthlySummaries(monthsInWeek)

    }

    private fun updateRatesButtons(months: List<Pair<Int, Int>>) {
        val container = binding.configureRatesContainer
        container.removeAllViews()

        months.forEachIndexed { index, (year, month) ->
            val button = MaterialButton(this)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                params.topMargin = dp(8)
            }
            button.layoutParams = params
            button.text = getString(R.string.configure_month_rates_for, formatMonthWithYear(year, month))
            button.setOnClickListener { showRatesDialog(year, month) }
            container.addView(button)
        }

        container.visibility = if (months.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateMonthlySummaries(months: List<Pair<Int, Int>>) {
        val container = binding.monthlySummaryContainer
        container.removeAllViews()

        months.forEachIndexed { index, (year, month) ->
            val (normalRate, extraRate) = getMonthlyRates(year, month)
            val report = collectMonthlyReport(year, month, normalRate, extraRate)
            val summaryText = buildMonthlySummary(report)
            val monthLabel = formatMonthWithYear(year, month)

            val monthLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { params ->
                    if (index > 0) {
                        params.topMargin = dp(12)
                    }
                }
            }

            val summaryView = TextView(this).apply {
                setPadding(0, 0, 0, 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                text = summaryText ?: if (report.totalMillis <= 0L) {
                    getString(R.string.report_summary_no_data, monthLabel)
                } else {
                    getString(R.string.report_summary_missing_rates_with_month, monthLabel)
                }
            }
            monthLayout.addView(summaryView)

            val button = MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                text = getString(R.string.generate_report_button, monthLabel)
                icon = AppCompatResources.getDrawable(this@MainActivity, R.drawable.ic_report)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = dp(8)
                isAllCaps = false
                setOnClickListener { showGenerateReportDialog(year, month) }
            }
            monthLayout.addView(button)

            container.addView(monthLayout)
        }

        container.visibility = if (months.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun collectMonthlyReport(year: Int, month: Int, normalRate: Double, extraRate: Double): MonthlyReport {
        val monthCal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val dailyReports = mutableListOf<DailyReport>()
        var totalNormalMillis = 0L
        var totalExtraMillis = 0L

        for (dayOfMonth in 1..daysInMonth) {
            val dayCal = monthCal.clone() as Calendar
            dayCal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            val weekId = "${dayCal.get(Calendar.YEAR)}-${dayCal.get(Calendar.WEEK_OF_YEAR)}"
            val dayOfWeek = dayCal.get(Calendar.DAY_OF_WEEK)
            val dayIndex = (dayOfWeek + 5) % 7

            val intervals = getStoredIntervals(weekId, dayIndex)
            val (totalMillis, manualExtraMillis, autoExtraMillis) = calculateDayTotals(intervals)
            val extraMillis = manualExtraMillis + autoExtraMillis
            val normalMillis = totalMillis - extraMillis

            val date = LocalDate.of(dayCal.get(Calendar.YEAR), dayCal.get(Calendar.MONTH) + 1, dayOfMonth)
            dailyReports.add(DailyReport(date, normalMillis, extraMillis, totalMillis))

            totalNormalMillis += normalMillis
            totalExtraMillis += extraMillis
        }

        return MonthlyReport(
            year = year,
            month = month,
            daily = dailyReports,
            normalRate = normalRate,
            extraRate = extraRate,
            totalNormalMillis = totalNormalMillis,
            totalExtraMillis = totalExtraMillis
        )
    }

    private fun buildMonthlySummary(report: MonthlyReport): String? {
        if (report.totalMillis <= 0L) return null
        if (report.normalRate == 0.0 && report.extraRate == 0.0) return null

        val monthName = formatMonthName(report.year, report.month)
        val mensajeCariñoso = when {
            report.totalExtraMillis > 0L -> "🫶 ¡Qué trabajadora, mamá!"
            report.totalMillis == 0L -> "💤 Descansa, te lo has ganado."
            else -> "🌼 Buen equilibrio, mamá."
        }

        val paySummary = "Ingresos: normales ${formatCurrency(report.normalPay)}, extra ${formatCurrency(report.extraPay)} (total ${formatCurrency(report.totalPay)})"

        return "$monthName: ${formatDuration(report.totalNormalMillis)} normales, " +
                "${formatDuration(report.totalExtraMillis)} extra (total ${formatDuration(report.totalMillis)})\n" +
                "$paySummary\n" +
                mensajeCariñoso
    }

    private fun monthsInCurrentWeek(): List<Pair<Int, Int>> {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset)
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

        val months = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until 7) {
            val monthPair = cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
            if (months.lastOrNull() != monthPair) {
                months.add(monthPair)
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return months
    }

    private fun formatMonthName(year: Int, month: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return SimpleDateFormat("MMMM", Locale("es", "ES"))
            .format(cal.time)
            .replaceFirstChar { it.uppercase() }
    }

    private fun formatMonthWithYear(year: Int, month: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        return SimpleDateFormat("MMMM yyyy", Locale("es", "ES"))
            .format(cal.time)
            .replaceFirstChar { it.uppercase() }
    }

    private fun intervalDurationMillis(obj: JSONObject): Long {
        return if (obj.optString("type") == "quantity") {
            max(0, obj.optInt("minutes", 0)) * 60_000L
        } else {
            val s = obj.optInt("s", 0)
            val e = obj.optInt("e", 0)
            max(0, e - s) * 60_000L
        }
    }

    private fun intervalLabel(obj: JSONObject, durMillis: Long): String {
        val extra = obj.optBoolean("extra", false)
        val flag = if (extra) " [EXTRA]" else ""
        val duration = formatDuration(durMillis)

        return if (obj.optString("type") == "quantity") {
            "Cantidad: $duration$flag  (mantén pulsado para alternar EXTRA)"
        } else {
            val s = formatHM(obj.optInt("s", 0))
            val e = formatHM(obj.optInt("e", 0))
            "$s - $e  ($duration)$flag  (mantén pulsado para alternar EXTRA)"
        }
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

    private fun addQuantityDialog(dayIndex: Int) {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(16)
            setPadding(padding, padding, padding, 0)
        }

        val hoursInput = EditText(context).apply {
            hint = getString(R.string.quantity_hours_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val minutesInput = EditText(context).apply {
            hint = getString(R.string.quantity_minutes_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        layout.addView(hoursInput)
        layout.addView(minutesInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.add_quantity_title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val hours = max(0, hoursInput.text.toString().trim().toIntOrNull() ?: 0)
                val minutes = minutesInput.text.toString().trim().toIntOrNull() ?: 0

                if (minutes < 0 || minutes >= 60) {
                    Toast.makeText(context, getString(R.string.quantity_invalid_minutes), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val totalMinutes = hours * 60 + minutes
                if (totalMinutes <= 0) {
                    Toast.makeText(context, getString(R.string.quantity_invalid_total), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                dialog.dismiss()
                AlertDialog.Builder(context)
                    .setTitle("¿Contar como horas EXTRA?")
                    .setMessage("Puedes marcar esta cantidad como horas extra manualmente.")
                    .setPositiveButton("Sí") { _, _ -> addQuantity(dayIndex, totalMinutes, true) }
                    .setNegativeButton("No") { _, _ -> addQuantity(dayIndex, totalMinutes, false) }
                    .show()
            }
        }

        dialog.show()
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

    private fun addQuantity(dayIndex: Int, minutes: Int, extra: Boolean) {
        if (minutes <= 0) return
        val arr = getIntervals(dayIndex)
        val obj = JSONObject()
        obj.put("type", "quantity")
        obj.put("minutes", minutes)
        obj.put("extra", extra)
        obj.put("src", "quantity")
        arr.put(obj)
        saveIntervals(dayIndex, arr)
        populateDays()
    }

    private fun monthKey(year: Int, month: Int): String = String.format(Locale.US, "%04d-%02d", year, month + 1)

    private fun getMonthlyRates(year: Int, month: Int): Pair<Double, Double> {
        val key = monthKey(year, month)
        // Keys intentionally unchanged so that previously stored month rates remain valid.
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

        val monthName = formatMonthWithYear(year, month)

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

    private fun showGenerateReportDialog(year: Int, month: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_generate_report, null)
        val formatGroup = view.findViewById<android.widget.RadioGroup>(R.id.formatGroup)
        val emailInputLayout = view.findViewById<TextInputLayout>(R.id.emailInputLayout)
        val emailEditText = view.findViewById<TextInputEditText>(R.id.emailEditText)
        formatGroup.check(R.id.formatPdf)

        val monthLabel = formatMonthWithYear(year, month)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.report_dialog_title, monthLabel))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.report_dialog_confirm, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val selectedFormat = when (formatGroup.checkedRadioButtonId) {
                    R.id.formatCsv -> ReportFormat.CSV
                    else -> ReportFormat.PDF
                }
                val email = emailEditText.text?.toString()?.trim().orEmpty()
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    emailInputLayout.error = getString(R.string.report_dialog_error_email)
                    return@setOnClickListener
                }
                emailInputLayout.error = null
                dialog.dismiss()
                startReportGeneration(year, month, selectedFormat, email)
            }
        }

        dialog.show()
    }

    private fun startReportGeneration(year: Int, month: Int, format: ReportFormat, email: String) {
        val notificationId = (System.currentTimeMillis() and 0xFFFFFF).toInt()
        val monthLabel = formatMonthWithYear(year, month)
        val builder = NotificationCompat.Builder(this, reportChannelId)
            .setSmallIcon(R.drawable.ic_report)
            .setContentTitle(getString(R.string.report_notification_title, monthLabel))
            .setContentText(getString(R.string.report_notification_generating))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, true)

        reportNotificationManager.notify(notificationId, builder.build())

        lifecycleScope.launch {
            try {
                val (normalRate, extraRate) = getMonthlyRates(year, month)
                val report = withContext(Dispatchers.Default) {
                    collectMonthlyReport(year, month, normalRate, extraRate)
                }

                if (report.totalMillis <= 0L) {
                    val message = getString(R.string.report_dialog_error_no_data, monthLabel)
                    builder.setOngoing(false)
                        .setProgress(0, 0, false)
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    reportNotificationManager.notify(notificationId, builder.build())
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    return@launch
                }

                builder.setProgress(100, 40, false)
                    .setContentText(getString(R.string.report_notification_generating_file))
                reportNotificationManager.notify(notificationId, builder.build())

                val file = withContext(Dispatchers.IO) {
                    when (format) {
                        ReportFormat.PDF -> generatePdfReport(report)
                        ReportFormat.CSV -> generateCsvReport(report)
                    }
                }

                builder.setProgress(100, 80, false)
                    .setContentText(getString(R.string.report_notification_sending, email))
                reportNotificationManager.notify(notificationId, builder.build())

                val delivered = withContext(Dispatchers.Main) {
                    deliverReportByEmail(email, file, format, monthLabel)
                }

                if (!delivered) {
                    val errorText = getString(R.string.report_notification_no_email_client)
                    builder.setOngoing(false)
                        .setProgress(0, 0, false)
                        .setContentText(errorText)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(errorText))
                    reportNotificationManager.notify(notificationId, builder.build())
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.report_toast_no_email_client),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val doneMessage = getString(R.string.report_notification_done, email)
                builder.setOngoing(false)
                    .setProgress(0, 0, false)
                    .setContentText(doneMessage)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(doneMessage))
                reportNotificationManager.notify(notificationId, builder.build())

                Toast.makeText(this@MainActivity, getString(R.string.report_toast_sent, email), Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e("MainActivity", "Error generating report", t)
                builder.setOngoing(false)
                    .setProgress(0, 0, false)
                    .setContentText(getString(R.string.report_notification_error))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.report_notification_error)))
                reportNotificationManager.notify(notificationId, builder.build())
                Toast.makeText(this@MainActivity, getString(R.string.report_toast_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun generatePdfReport(report: MonthlyReport): File {
        val reportsDir = File(getExternalFilesDir(null), "reports").apply { if (!exists()) mkdirs() }
        val fileName = "HorasLite_${monthKey(report.year, report.month)}_${System.currentTimeMillis()}.pdf"
        val file = File(reportsDir, fileName)

        val workedDays = report.daily.filter { it.totalMillis > 0L }
        val baseHeight = 842
        val rowHeight = 28
        val pageHeight = max(baseHeight, 260 + workedDays.size * rowHeight)

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, pageHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 14f
        }
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 13f
        }

        val iconBitmap = Bitmap.createScaledBitmap(reportIconBitmap, 96, 96, true)
        canvas.drawBitmap(iconBitmap, 40f, 40f, null)

        val monthLabel = formatMonthWithYear(report.year, report.month)
        canvas.drawText(getString(R.string.report_pdf_title, monthLabel), 160f, 90f, titlePaint)
        canvas.drawText(
            getString(
                R.string.report_pdf_totals,
                formatDuration(report.totalNormalMillis),
                formatDuration(report.totalExtraMillis),
                formatDuration(report.totalMillis)
            ),
            40f,
            150f,
            subtitlePaint
        )
        canvas.drawText(
            getString(
                R.string.report_pdf_pay_totals,
                formatCurrency(report.normalPay),
                formatCurrency(report.extraPay),
                formatCurrency(report.totalPay)
            ),
            40f,
            180f,
            subtitlePaint
        )

        var currentY = 220f
        val headerPaint = Paint(textPaint).apply { isFakeBoldText = true }
        canvas.drawText(getString(R.string.report_pdf_daily_header), 40f, currentY, headerPaint)
        currentY += 30f

        if (workedDays.isEmpty()) {
            canvas.drawText(getString(R.string.report_pdf_no_worked_days), 40f, currentY, textPaint)
            currentY += 30f
        } else {
            val regularPaint = Paint(textPaint)
            workedDays.forEach { day ->
                val line = getString(
                    R.string.report_pdf_daily_line,
                    day.date.format(pdfDateFormatter),
                    formatDuration(day.normalMillis),
                    formatDuration(day.extraMillis),
                    formatDuration(day.totalMillis)
                )
                canvas.drawText(line, 40f, currentY, regularPaint)
                currentY += rowHeight
            }
        }

        currentY += 20f
        canvas.drawText(
            getString(R.string.report_pdf_footer, LocalDate.now().format(pdfDateFormatter)),
            40f,
            currentY,
            subtitlePaint
        )

        document.finishPage(page)
        FileOutputStream(file).use { output ->
            document.writeTo(output)
        }
        document.close()
        if (iconBitmap != reportIconBitmap) {
            iconBitmap.recycle()
        }

        return file
    }

    private fun generateCsvReport(report: MonthlyReport): File {
        val reportsDir = File(getExternalFilesDir(null), "reports").apply { if (!exists()) mkdirs() }
        val fileName = "HorasLite_${monthKey(report.year, report.month)}_${System.currentTimeMillis()}.csv"
        val file = File(reportsDir, fileName)

        val workedDays = report.daily.filter { it.totalMillis > 0L }

        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { writer ->
            writer.write(getString(R.string.report_csv_header))
            writer.newLine()

            workedDays.forEach { day ->
                val normalPay = report.normalRate * durationToHours(day.normalMillis)
                val extraPay = report.extraRate * durationToHours(day.extraMillis)
                val totalPay = normalPay + extraPay
                writer.write(
                    listOf(
                        day.date.format(csvDateFormatter),
                        formatHoursDecimal(day.normalMillis),
                        formatHoursDecimal(day.extraMillis),
                        formatHoursDecimal(day.totalMillis),
                        formatDecimal(normalPay),
                        formatDecimal(extraPay),
                        formatDecimal(totalPay)
                    ).joinToString(";")
                )
                writer.newLine()
            }

            writer.newLine()
            writer.write(
                listOf(
                    getString(R.string.report_csv_totals_label),
                    formatHoursDecimal(report.totalNormalMillis),
                    formatHoursDecimal(report.totalExtraMillis),
                    formatHoursDecimal(report.totalMillis),
                    formatDecimal(report.normalPay),
                    formatDecimal(report.extraPay),
                    formatDecimal(report.totalPay)
                ).joinToString(";")
            )
            writer.newLine()
        }

        return file
    }

    private fun deliverReportByEmail(
        email: String,
        file: File,
        format: ReportFormat,
        monthLabel: String
    ): Boolean {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(this, authority, file)
        val mimeType = when (format) {
            ReportFormat.PDF -> "application/pdf"
            ReportFormat.CSV -> "text/csv"
        }

        val subject = getString(R.string.report_email_subject, monthLabel)
        val body = getString(R.string.report_email_body, monthLabel)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, file.name, uri)
        }

        return try {
            val chooser = Intent.createChooser(
                sendIntent,
                getString(R.string.report_email_chooser_title)
            ).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            Log.e("MainActivity", "Unable to launch email client", e)
            false
        }
    }

    private fun loadReportIconBitmap(): Bitmap {
        val drawable = AppCompatResources.getDrawable(this, R.mipmap.ic_launcher)
            ?: throw IllegalStateException("App icon resource missing")
        val desiredSize = (resources.displayMetrics.density * 96f).toInt().coerceAtLeast(72)

        val baseBitmap = when {
            drawable is BitmapDrawable && drawable.bitmap != null -> drawable.bitmap
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable -> {
                val bitmap = Bitmap.createBitmap(desiredSize, desiredSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
            else -> {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else desiredSize
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else desiredSize
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        }

        return if (baseBitmap.width == desiredSize && baseBitmap.height == desiredSize) {
            baseBitmap
        } else {
            Bitmap.createScaledBitmap(baseBitmap, desiredSize, desiredSize, true)
        }
    }

    private fun formatHoursDecimal(millis: Long): String = String.format(Locale.US, "%.2f", durationToHours(millis))

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.2f", value)

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

    private fun createReportNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = getString(R.string.report_notification_channel_name)
            val channelDescription = getString(R.string.report_notification_channel_desc)
            val channel = NotificationChannel(reportChannelId, channelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = channelDescription
            }
            reportNotificationManager.createNotificationChannel(channel)
        }
    }
}
