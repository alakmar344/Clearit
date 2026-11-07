package com.clearit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clearit.engine.Adjustment
import com.clearit.engine.AdjustmentGroup
import com.clearit.engine.Adjustments
import com.clearit.engine.AutoOptions
import com.clearit.engine.ImageAnalysis
import com.clearit.engine.Preset
import com.clearit.io.ExportFormat
import com.clearit.ui.CompareImageView
import com.clearit.ui.HistogramView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import android.widget.RadioGroup
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Tab(@StringRes val title: Int) {
        AUTO(R.string.tab_auto),
        PRESETS(R.string.tab_presets),
        LIGHT(R.string.tab_light),
        COLOR(R.string.tab_color),
        DETAIL(R.string.tab_detail),
        EFFECTS(R.string.tab_effects),
        TRANSFORM(R.string.tab_transform),
    }

    private class SliderRow(val root: View, val value: TextView, val slider: Slider)

    private val viewModel: EditorViewModel by viewModels()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var compareView: CompareImageView
    private lateinit var histogramView: HistogramView
    private lateinit var emptyState: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var infoLabel: TextView
    private lateinit var tabs: TabLayout
    private lateinit var panelContainer: LinearLayout

    private val panels = LinkedHashMap<Tab, View>()
    private val sliderRows = LinkedHashMap<Adjustment, SliderRow>()
    private val presetChips = LinkedHashMap<Preset, Chip>()
    private val controls = ArrayList<View>()
    private var trackingSlider: Slider? = null
    private var peeking = false

    private lateinit var analysisText: TextView
    private lateinit var autoStrength: Slider
    private lateinit var autoLight: Chip
    private lateinit var autoColor: Chip
    private lateinit var autoDetail: Chip
    private lateinit var upscaleGroup: MaterialButtonToggleGroup
    private lateinit var outputSizeText: TextView

    private var pendingExport: (() -> Unit)? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openImage(uri)
    }

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingExport
        pendingExport = null
        if (granted) action?.invoke() else snack(getString(R.string.storage_permission_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        compareView = findViewById(R.id.compareView)
        histogramView = findViewById(R.id.histogramView)
        emptyState = findViewById(R.id.emptyState)
        progress = findViewById(R.id.progress)
        infoLabel = findViewById(R.id.infoLabel)
        tabs = findViewById(R.id.tabs)
        panelContainer = findViewById(R.id.panelContainer)

        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { onMenuItem(it.itemId, it.isChecked) }
        findViewById<View>(R.id.openButton).setOnClickListener { launchPicker() }
        compareView.onPeekChanged = { peeking = it; renderInfo() }

        buildPanels()
        val savedTab = savedInstanceState?.getInt(STATE_TAB) ?: 0
        tabs.getTabAt(savedTab)?.select()
        showPanel(Tab.entries[tabs.selectedTabPosition.coerceAtLeast(0)])
        val prefs = getPreferences(MODE_PRIVATE)
        histogramView.visibility = if (prefs.getBoolean(PREF_HISTOGRAM, false)) View.VISIBLE else View.GONE
        toolbar.menu.findItem(R.id.action_histogram).isChecked = histogramView.visibility == View.VISIBLE

        observe()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tabs.selectedTabPosition)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------ intents & opening

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data
            else -> null
        }
        if (uri != null) viewModel.open(uri)
    }

    private fun launchPicker() {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun openImage(uri: Uri) {
        // Keep access across process death where the provider allows it.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        compareView.resetZoom()
        viewModel.open(uri)
    }

    // ------------------------------------------------------------------ panels

    private fun buildPanels() {
        for (tab in Tab.entries) {
            tabs.addTab(tabs.newTab().setText(tab.title))
            val panel = when (tab) {
                Tab.AUTO -> buildAutoPanel()
                Tab.PRESETS -> buildPresetPanel()
                Tab.LIGHT -> buildSliderPanel(AdjustmentGroup.LIGHT)
                Tab.COLOR -> buildSliderPanel(AdjustmentGroup.COLOR)
                Tab.DETAIL -> buildSliderPanel(AdjustmentGroup.DETAIL)
                Tab.EFFECTS -> buildSliderPanel(AdjustmentGroup.EFFECTS)
                Tab.TRANSFORM -> buildTransformPanel()
            }
            panel.visibility = View.GONE
            panelContainer.addView(panel)
            panels[tab] = panel
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(Tab.entries[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showPanel(tab: Tab) {
        panels.forEach { (t, v) -> v.visibility = if (t == tab) View.VISIBLE else View.GONE }
        findViewById<View>(R.id.panelScroll).scrollTo(0, 0)
    }

    private fun verticalPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun hintText(@StringRes text: Int, topMargin: Int = 8): TextView = TextView(this).apply {
        setText(text)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { this.topMargin = dp(topMargin) }
    }

    private fun titleText(@StringRes text: Int): TextView = TextView(this).apply {
        setText(text)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) }
    }

    private fun buildAutoPanel(): View = verticalPanel().apply {
        val button = MaterialButton(context).apply {
            setText(R.string.auto_enhance)
            setIconResource(R.drawable.ic_auto)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                viewModel.autoEnhance(
                    AutoOptions(
                        light = autoLight.isChecked,
                        color = autoColor.isChecked,
                        detail = autoDetail.isChecked,
                        strength = autoStrength.value / 100f,
                    )
                )
            }
        }
        controls += button
        addView(button)

        val strengthRow = layoutInflater.inflate(R.layout.item_adjustment, this, false)
        strengthRow.findViewById<TextView>(R.id.label).setText(R.string.auto_strength)
        val strengthValue = strengthRow.findViewById<TextView>(R.id.value)
        autoStrength = strengthRow.findViewById<Slider>(R.id.slider).apply {
            valueFrom = 0f
            valueTo = 150f
            stepSize = 5f
            value = 100f
            setLabelFormatter { getString(R.string.percent, it.roundToInt()) }
            addOnChangeListener { _, v, _ -> strengthValue.text = getString(R.string.percent, v.roundToInt()) }
        }
        strengthValue.text = getString(R.string.percent, 100)
        controls += autoStrength
        addView(strengthRow)

        addView(titleText(R.string.auto_scope))
        val chips = ChipGroup(context)
        fun chip(@StringRes text: Int) = Chip(context).apply {
            setText(text)
            isCheckable = true
            isChecked = true
            chips.addView(this)
        }
        autoLight = chip(R.string.auto_scope_light)
        autoColor = chip(R.string.auto_scope_color)
        autoDetail = chip(R.string.auto_scope_detail)
        addView(chips)

        addView(titleText(R.string.analysis_title))
        analysisText = hintText(R.string.auto_hint, 4)
        addView(analysisText)
    }

    private fun buildPresetPanel(): View = verticalPanel().apply {
        addView(hintText(R.string.presets_hint, 4))
        val group = ChipGroup(context).apply { isSingleSelection = true }
        for (preset in Preset.entries) {
            val chip = Chip(context).apply {
                setText(presetName(preset))
                isCheckable = true
                setOnClickListener { viewModel.applyPreset(preset) }
            }
            presetChips[preset] = chip
            controls += chip
            group.addView(chip)
        }
        addView(group)
    }

    private fun buildSliderPanel(group: AdjustmentGroup): View = verticalPanel().apply {
        for (field in Adjustment.inGroup(group)) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_adjustment, this, false)
            row.findViewById<TextView>(R.id.label).setText(adjustmentName(field))
            val valueView = row.findViewById<TextView>(R.id.value)
            val slider = row.findViewById<Slider>(R.id.slider).apply {
                valueFrom = field.min
                valueTo = field.max
                stepSize = field.step
                value = 0f
                contentDescription = getString(adjustmentName(field))
                setLabelFormatter { formatValue(field, it) }
                addOnChangeListener { s, v, fromUser ->
                    valueView.text = formatValue(field, v)
                    if (!fromUser) return@addOnChangeListener
                    viewModel.previewAdjustments(viewModel.state.value.adjustments.with(field, v))
                    if (trackingSlider !== s) viewModel.commit() // keyboard / accessibility change
                }
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) { trackingSlider = slider }
                    override fun onStopTrackingTouch(slider: Slider) {
                        trackingSlider = null
                        viewModel.commit()
                    }
                })
            }
            valueView.text = formatValue(field, 0f)
            row.findViewById<View>(R.id.labelRow).setOnLongClickListener {
                viewModel.setAdjustments(viewModel.state.value.adjustments.with(field, 0f))
                true
            }
            sliderRows[field] = SliderRow(row, valueView, slider)
            controls += slider
            addView(row)
        }
        addView(hintText(R.string.slider_hint, 8))
    }

    private fun buildTransformPanel(): View = verticalPanel().apply {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun iconButton(icon: Int, @StringRes description: Int, action: () -> Unit) {
            val b = MaterialButton(context, null, com.google.android.material.R.attr.materialIconButtonOutlinedStyle).apply {
                setIconResource(icon)
                contentDescription = getString(description)
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(8) }
            }
            controls += b
            row.addView(b)
        }
        iconButton(R.drawable.ic_rotate_left, R.string.rotate_left) { viewModel.rotateCounterClockwise() }
        iconButton(R.drawable.ic_rotate_right, R.string.rotate_right) { viewModel.rotateClockwise() }
        iconButton(R.drawable.ic_flip_horizontal, R.string.flip_horizontal) { viewModel.flipHorizontal() }
        iconButton(R.drawable.ic_flip_vertical, R.string.flip_vertical) { viewModel.flipVertical() }
        addView(row)

        addView(titleText(R.string.upscale_title))
        upscaleGroup = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for (factor in Adjustments.UPSCALE_FACTORS) {
            val b = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                tag = factor
                text = getString(R.string.upscale_factor, factor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            controls += b
            upscaleGroup.addView(b)
        }
        upscaleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked) viewModel.setUpscale(group.findViewById<View>(checkedId).tag as Int)
        }
        addView(upscaleGroup, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outputSizeText = hintText(R.string.upscale_factor, 8).apply {
            text = ""
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        addView(outputSizeText)
        addView(hintText(R.string.upscale_hint, 4))
    }

    // ------------------------------------------------------------------ state → UI

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.after.collect { compareView.after = it } }
                launch { viewModel.before.collect { compareView.before = it } }
                launch { viewModel.histogram.collect { histogramView.histogram = it } }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun render(state: EditorState) {
        val hasImage = state.hasImage
        emptyState.visibility = if (state.sourceUri == null) View.VISIBLE else View.GONE
        controls.forEach { it.isEnabled = hasImage && state.exportProgress == null }

        val adj = state.adjustments
        for ((field, row) in sliderRows) {
            if (row.slider === trackingSlider) continue
            val v = field.clamp(adj[field])
            if (abs(row.slider.value - v) > 1e-4f) row.slider.value = v
            row.value.text = formatValue(field, v)
        }
        presetChips.forEach { (preset, chip) -> chip.isChecked = preset == state.activePreset }

        val checkedFactor = upscaleGroup.checkedButtonId.takeIf { it != View.NO_ID }
            ?.let { upscaleGroup.findViewById<View>(it).tag as Int }
        if (checkedFactor != adj.upscale) {
            for (i in 0 until upscaleGroup.childCount) {
                val child = upscaleGroup.getChildAt(i)
                if (child.tag == adj.upscale) upscaleGroup.check(child.id)
            }
        }
        val output = state.outputSize(viewModel.maxProcessingPixels)
        outputSizeText.text = if (output == null) "" else {
            val unlimited = state.sourceSize?.let {
                val (w, h) = com.clearit.engine.ImageProcessor.outputSize(it.width, it.height, adj)
                w.toLong() * h
            } ?: 0L
            getString(if (output.pixels < unlimited) R.string.output_size_limited else R.string.output_size, output.toString())
        }

        state.analysis?.let { analysisText.text = describe(it) }

        val menu = toolbar.menu
        menu.findItem(R.id.action_undo).isEnabled = state.canUndo
        menu.findItem(R.id.action_redo).isEnabled = state.canRedo
        listOf(R.id.action_save, R.id.action_share, R.id.action_reset, R.id.action_compare)
            .forEach { menu.findItem(it).isEnabled = hasImage && state.exportProgress == null }

        val export = state.exportProgress
        when {
            export != null -> {
                if (progress.isIndeterminate) {
                    progress.visibility = View.INVISIBLE
                    progress.isIndeterminate = false
                }
                progress.visibility = View.VISIBLE
                progress.setProgressCompat((export * 100).roundToInt(), true)
            }
            state.loading || state.rendering -> {
                if (!progress.isIndeterminate) {
                    progress.visibility = View.INVISIBLE
                    progress.isIndeterminate = true
                }
                progress.visibility = View.VISIBLE
            }
            else -> progress.visibility = View.INVISIBLE
        }
        renderInfo()
    }

    private fun renderInfo() {
        val state = viewModel.state.value
        val export = state.exportProgress
        val text = when {
            export != null -> getString(R.string.exporting, (export * 100).roundToInt())
            peeking -> getString(R.string.info_original)
            else -> null
        }
        infoLabel.text = text
        infoLabel.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun describe(a: ImageAnalysis): String {
        val exposure = getString(
            when {
                a.median < 0.25f -> R.string.analysis_exposure_dark
                a.median > 0.72f -> R.string.analysis_exposure_bright
                else -> R.string.analysis_exposure_ok
            }
        )
        val color = getString(
            when {
                a.warmCast > 0.1f -> R.string.analysis_color_warm
                a.warmCast < -0.1f -> R.string.analysis_color_cool
                a.magentaCast < -0.07f -> R.string.analysis_color_green
                a.magentaCast > 0.07f -> R.string.analysis_color_magenta
                else -> R.string.analysis_color_neutral
            }
        )
        val noise = getString(
            when {
                a.noiseSigma < 1.5f -> R.string.analysis_level_low
                a.noiseSigma < 4f -> R.string.analysis_level_medium
                else -> R.string.analysis_level_high
            }
        )
        val detail = getString(
            when {
                a.sharpness < 4f -> R.string.analysis_level_low
                a.sharpness < 12f -> R.string.analysis_level_medium
                else -> R.string.analysis_level_high
            }
        )
        return listOf(
            getString(R.string.analysis_exposure, exposure, (a.median * 100).roundToInt()),
            getString(R.string.analysis_color, color),
            getString(R.string.analysis_noise, noise),
            getString(R.string.analysis_detail, detail),
            getString(R.string.analysis_clipping, a.clippedShadows * 100, a.clippedHighlights * 100),
            "",
            getString(R.string.auto_hint),
        ).joinToString("\n")
    }

    // ------------------------------------------------------------------ events

    private fun onEvent(event: EditorEvent) {
        when (event) {
            is EditorEvent.Saved -> {
                if (event.share) {
                    share(event.uri)
                } else {
                    val msg = getString(if (event.limitedByMemory) R.string.saved_limited else R.string.saved, event.size.toString())
                    Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_share) { share(event.uri) }
                        .show()
                }
            }
            is EditorEvent.Failed -> snack(getString(event.messageRes, event.detail ?: getString(R.string.error_unknown)))
            is EditorEvent.Info -> snack(getString(event.messageRes))
        }
    }

    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    private fun snack(message: String) = Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_LONG).show()

    // ------------------------------------------------------------------ menu

    private fun onMenuItem(id: Int, wasChecked: Boolean): Boolean {
        when (id) {
            R.id.action_open -> launchPicker()
            R.id.action_undo -> viewModel.undo()
            R.id.action_redo -> viewModel.redo()
            R.id.action_compare -> {
                val on = !wasChecked
                toolbar.menu.findItem(id).isChecked = on
                compareView.splitMode = on
            }
            R.id.action_save -> showExportDialog(share = false)
            R.id.action_share -> showExportDialog(share = true)
            R.id.action_histogram -> {
                val on = !wasChecked
                toolbar.menu.findItem(id).isChecked = on
                histogramView.visibility = if (on) View.VISIBLE else View.GONE
                getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_HISTOGRAM, on).apply()
            }
            R.id.action_reset -> viewModel.resetAll()
            R.id.action_help -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(R.string.ok, null)
                .show()
            R.id.action_about -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.ok, null)
                .show()
            else -> return false
        }
        return true
    }

    private fun showExportDialog(share: Boolean) {
        val state = viewModel.state.value
        if (!state.hasImage) {
            snack(getString(R.string.no_image))
            return
        }
        val prefs = getPreferences(MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_export, null)
        val formatGroup = view.findViewById<RadioGroup>(R.id.formatGroup)
        val qualityLabel = view.findViewById<TextView>(R.id.qualityLabel)
        val qualitySlider = view.findViewById<Slider>(R.id.qualitySlider)
        val info = view.findViewById<TextView>(R.id.exportInfo)
        val formatIds = mapOf(
            ExportFormat.JPEG to R.id.formatJpeg,
            ExportFormat.PNG to R.id.formatPng,
            ExportFormat.WEBP to R.id.formatWebp,
        )
        val savedFormat = runCatching { ExportFormat.valueOf(prefs.getString(PREF_FORMAT, null) ?: "JPEG") }
            .getOrDefault(ExportFormat.JPEG)
        formatGroup.check(formatIds.getValue(savedFormat))
        qualitySlider.value = prefs.getInt(PREF_QUALITY, 95).coerceIn(50, 100).toFloat()
        fun selectedFormat() = formatIds.entries.first { it.value == formatGroup.checkedRadioButtonId }.key
        fun refresh() {
            val lossy = selectedFormat().lossy
            qualitySlider.visibility = if (lossy) View.VISIBLE else View.GONE
            qualityLabel.text = if (lossy) {
                getString(R.string.export_quality, qualitySlider.value.roundToInt())
            } else {
                getString(R.string.export_quality_lossless)
            }
        }
        formatGroup.setOnCheckedChangeListener { _, _ -> refresh() }
        qualitySlider.addOnChangeListener { _, _, _ -> refresh() }
        refresh()
        info.text = getString(R.string.export_info, state.outputSize(viewModel.maxProcessingPixels)?.toString() ?: "")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_title)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (share) R.string.export_share else R.string.export_save) { _, _ ->
                val format = selectedFormat()
                val quality = qualitySlider.value.roundToInt()
                prefs.edit().putString(PREF_FORMAT, format.name).putInt(PREF_QUALITY, quality).apply()
                runWithStoragePermission { viewModel.export(format, quality, share) }
            }
            .show()
    }

    private fun runWithStoragePermission(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingExport = action
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun formatValue(field: Adjustment, v: Float): String = when (field) {
        Adjustment.EXPOSURE -> getString(R.string.value_exposure, v)
        else -> {
            val i = v.roundToInt()
            if (i > 0 && field.min < 0f) "+$i" else i.toString()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val STATE_TAB = "tab"
        private const val PREF_HISTOGRAM = "histogram"
        private const val PREF_FORMAT = "format"
        private const val PREF_QUALITY = "quality"

        @StringRes
        fun adjustmentName(field: Adjustment): Int = when (field) {
            Adjustment.EXPOSURE -> R.string.adj_exposure
            Adjustment.CONTRAST -> R.string.adj_contrast
            Adjustment.HIGHLIGHTS -> R.string.adj_highlights
            Adjustment.SHADOWS -> R.string.adj_shadows
            Adjustment.WHITES -> R.string.adj_whites
            Adjustment.BLACKS -> R.string.adj_blacks
            Adjustment.TEMPERATURE -> R.string.adj_temperature
            Adjustment.TINT -> R.string.adj_tint
            Adjustment.VIBRANCE -> R.string.adj_vibrance
            Adjustment.SATURATION -> R.string.adj_saturation
            Adjustment.CLARITY -> R.string.adj_clarity
            Adjustment.TEXTURE -> R.string.adj_texture
            Adjustment.DEHAZE -> R.string.adj_dehaze
            Adjustment.SHARPENING -> R.string.adj_sharpening
            Adjustment.NOISE_REDUCTION -> R.string.adj_noise_reduction
            Adjustment.COLOR_NOISE_REDUCTION -> R.string.adj_color_noise_reduction
            Adjustment.VIGNETTE -> R.string.adj_vignette
        }

        @StringRes
        fun presetName(preset: Preset): Int = when (preset) {
            Preset.ORIGINAL -> R.string.preset_original
            Preset.CLEARIT -> R.string.preset_clearit
            Preset.VIVID -> R.string.preset_vivid
            Preset.PORTRAIT -> R.string.preset_portrait
            Preset.LANDSCAPE -> R.string.preset_landscape
            Preset.LOW_LIGHT -> R.string.preset_low_light
            Preset.CRISP -> R.string.preset_crisp
            Preset.SOFT_MATTE -> R.string.preset_soft_matte
            Preset.WARM -> R.string.preset_warm
            Preset.COOL -> R.string.preset_cool
            Preset.BLACK_AND_WHITE -> R.string.preset_bw
        }
    }
}
