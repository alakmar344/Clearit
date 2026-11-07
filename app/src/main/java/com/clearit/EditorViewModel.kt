package com.clearit

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.clearit.engine.Adjustments
import com.clearit.engine.AutoEnhancer
import com.clearit.engine.AutoOptions
import com.clearit.engine.Geometry
import com.clearit.engine.Histogram
import com.clearit.engine.ImageAnalysis
import com.clearit.engine.ImageAnalyzer
import com.clearit.engine.ImageProcessor
import com.clearit.engine.PixelImage
import com.clearit.engine.Preset
import com.clearit.engine.Resampler
import com.clearit.io.BitmapIO
import com.clearit.io.ExportFormat
import com.clearit.io.ImageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

data class EditorState(
    val sourceUri: Uri? = null,
    /** Full-resolution size of the opened image (orientation corrected). */
    val sourceSize: ImageSize? = null,
    val loading: Boolean = false,
    val rendering: Boolean = false,
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val analysis: ImageAnalysis? = null,
    /** Non-null while an export is running (0..1). */
    val exportProgress: Float? = null,
    val activePreset: Preset? = null,
) {
    val hasImage: Boolean get() = sourceUri != null && !loading

    /** Expected export size, already limited by the device memory budget. */
    fun outputSize(maxPixels: Long): ImageSize? {
        val size = sourceSize ?: return null
        val (w, h) = ImageProcessor.outputSize(size.width, size.height, adjustments)
        val pixels = w.toLong() * h
        if (pixels <= maxPixels) return ImageSize(w, h)
        val s = sqrt(maxPixels.toDouble() / pixels)
        return ImageSize((w * s).toInt(), (h * s).toInt())
    }
}

sealed interface EditorEvent {
    data class Saved(val uri: Uri, val size: ImageSize, val limitedByMemory: Boolean, val share: Boolean) : EditorEvent
    data class Failed(val messageRes: Int, val detail: String?) : EditorEvent
    data class Info(val messageRes: Int) : EditorEvent
}

/**
 * Holds the open image and edit state, renders previews and exports.
 *
 * Previews are rendered from a ≤1600 px copy of the photo (and a ≤800 px "draft" copy while a
 * slider is being dragged, for responsiveness). Because every engine radius scales with image
 * size, the preview predicts the full-resolution export faithfully.
 */
class EditorViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _before = MutableStateFlow<Bitmap?>(null)
    val before: StateFlow<Bitmap?> = _before.asStateFlow()

    private val _after = MutableStateFlow<Bitmap?>(null)
    val after: StateFlow<Bitmap?> = _after.asStateFlow()

    private val _histogram = MutableStateFlow<Histogram?>(null)
    val histogram: StateFlow<Histogram?> = _histogram.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val maxProcessingPixels: Long = BitmapIO.maxProcessingPixels(application)

    private var previewSource: PixelImage? = null
    private var draftSource: PixelImage? = null
    private var beforeGeometryKey: String? = null

    private val history = ArrayList<Adjustments>()
    private var historyIndex = -1

    private var loadJob: Job? = null
    private var renderJob: Job? = null
    private var exportJob: Job? = null

    init {
        // Restore after process death.
        val uri = saved.get<String>(KEY_URI)?.let(Uri::parse)
        if (uri != null) open(uri, Adjustments.decode(saved.get<String>(KEY_ADJUSTMENTS)))
    }

    // ------------------------------------------------------------------ opening

    fun open(uri: Uri, initial: Adjustments = Adjustments.NEUTRAL) {
        loadJob?.cancel()
        renderJob?.cancel()
        _state.value = EditorState(sourceUri = uri, loading = true, adjustments = initial)
        _before.value = null
        _after.value = null
        _histogram.value = null
        previewSource = null
        draftSource = null
        beforeGeometryKey = null
        history.clear()
        history += initial
        historyIndex = 0
        saved[KEY_URI] = uri.toString()
        saved[KEY_ADJUSTMENTS] = initial.encode()

        loadJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val loaded = withContext(Dispatchers.IO) {
                    val size = BitmapIO.readSize(app, uri)
                    val bitmap = BitmapIO.decode(app, uri, maxLongEdge = PREVIEW_LONG_EDGE)
                    val image = BitmapIO.toPixelImage(bitmap)
                    bitmap.recycle()
                    Triple(size ?: ImageSize(image.width, image.height), image, ImageAnalyzer.analyze(image))
                }
                previewSource = loaded.second
                draftSource = Resampler.fitLongEdge(loaded.second, DRAFT_LONG_EDGE)
                _state.update {
                    it.copy(loading = false, sourceSize = loaded.first, analysis = loaded.third)
                        .withHistoryFlags()
                }
                render(draft = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                saved.remove<String>(KEY_URI)
                _state.value = EditorState()
                _events.send(EditorEvent.Failed(R.string.error_open_failed, e.localizedMessage))
            }
        }
    }

    // ------------------------------------------------------------------ editing

    /** Live update while a slider is dragged: fast draft render, no history entry. */
    fun previewAdjustments(adjustments: Adjustments) {
        if (adjustments == _state.value.adjustments) return
        _state.update { it.copy(adjustments = adjustments, activePreset = null) }
        render(draft = true)
    }

    /** Finishes an interactive change: records history and renders the full preview. */
    fun commit() {
        val current = _state.value.adjustments
        if (historyIndex < 0 || history[historyIndex] != current) {
            while (history.size > historyIndex + 1) history.removeAt(history.size - 1)
            history += current
            if (history.size > MAX_HISTORY) history.removeAt(0)
            historyIndex = history.size - 1
        }
        saved[KEY_ADJUSTMENTS] = current.encode()
        _state.update { it.withHistoryFlags() }
        render(draft = false)
    }

    fun setAdjustments(adjustments: Adjustments, preset: Preset? = null) {
        _state.update { it.copy(adjustments = adjustments, activePreset = preset) }
        commit()
    }

    fun applyPreset(preset: Preset) =
        setAdjustments(_state.value.adjustments.withSlidersFrom(preset.adjustments), preset)

    fun autoEnhance(options: AutoOptions) {
        val analysis = _state.value.analysis ?: return
        val suggestion = AutoEnhancer.suggest(analysis, options)
        setAdjustments(_state.value.adjustments.withSlidersFrom(suggestion))
        viewModelScope.launch { _events.send(EditorEvent.Info(R.string.auto_applied)) }
    }

    fun resetAll() = setAdjustments(Adjustments.NEUTRAL)

    fun rotateClockwise() = setAdjustments(_state.value.adjustments.rotatedClockwise())
    fun rotateCounterClockwise() = setAdjustments(_state.value.adjustments.rotatedCounterClockwise())
    fun flipHorizontal() = _state.value.adjustments.let { setAdjustments(it.copy(flipHorizontal = !it.flipHorizontal)) }
    fun flipVertical() = _state.value.adjustments.let { setAdjustments(it.copy(flipVertical = !it.flipVertical)) }
    fun setUpscale(factor: Int) {
        if (factor != _state.value.adjustments.upscale) setAdjustments(_state.value.adjustments.copy(upscale = factor))
    }

    fun undo() {
        if (historyIndex <= 0) return
        historyIndex--
        restoreHistory()
    }

    fun redo() {
        if (historyIndex >= history.size - 1) return
        historyIndex++
        restoreHistory()
    }

    private fun restoreHistory() {
        val adj = history[historyIndex]
        saved[KEY_ADJUSTMENTS] = adj.encode()
        _state.update { it.copy(adjustments = adj, activePreset = null).withHistoryFlags() }
        render(draft = false)
    }

    private fun EditorState.withHistoryFlags() =
        copy(canUndo = historyIndex > 0, canRedo = historyIndex in 0 until history.size - 1)

    // ------------------------------------------------------------------ rendering

    private fun render(draft: Boolean) {
        val source = (if (draft) draftSource else previewSource) ?: return
        val adjustments = _state.value.adjustments
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            _state.update { it.copy(rendering = true) }
            val context = this.coroutineContext
            try {
                updateBefore(adjustments)
                val result = ImageProcessor.process(source, adjustments, includeUpscale = false) {
                    context.ensureActive()
                }
                context.ensureActive()
                val bitmap = BitmapIO.toBitmap(result)
                _after.value = bitmap
                if (!draft) _histogram.value = Histogram.of(result)
                _state.update { it.copy(rendering = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(rendering = false) }
                _events.send(EditorEvent.Failed(R.string.error_out_of_memory, null))
            }
        }
    }

    /** The "before" image only changes with geometry; it is cached per orientation. */
    private fun updateBefore(adjustments: Adjustments) {
        val source = previewSource ?: return
        val key = "${adjustments.rotation}|${adjustments.flipHorizontal}|${adjustments.flipVertical}"
        if (key == beforeGeometryKey && _before.value != null) return
        val rotated = Geometry.apply(source, adjustments.rotation, adjustments.flipHorizontal, adjustments.flipVertical)
        _before.value = BitmapIO.toBitmap(rotated)
        beforeGeometryKey = key
    }

    // ------------------------------------------------------------------ export

    fun export(format: ExportFormat, quality: Int, share: Boolean) {
        val uri = _state.value.sourceUri ?: return
        if (exportJob?.isActive == true) return
        val adjustments = _state.value.adjustments
        val sourceSize = _state.value.sourceSize
        _state.update { it.copy(exportProgress = 0f) }
        exportJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val result = withContext(Dispatchers.Default) {
                    val scale = adjustments.upscale
                    val sourceBudget = maxProcessingPixels / (scale * scale)
                    val bitmap = BitmapIO.decode(app, uri, maxPixels = sourceBudget)
                    val limited = sourceSize != null && bitmap.width.toLong() * bitmap.height < sourceSize.pixels
                    // The decoded source is only reachable inside run{} so it can be collected
                    // as soon as the pipeline no longer needs it.
                    val output = run {
                        val image = BitmapIO.toPixelImage(bitmap)
                        bitmap.recycle()
                        _state.update { it.copy(exportProgress = 0.05f) }
                        ImageProcessor.process(image, adjustments, includeUpscale = true) { f ->
                            _state.update { it.copy(exportProgress = 0.05f + f * 0.85f) }
                        }
                    }
                    val outBitmap = BitmapIO.toBitmap(output)
                    _state.update { it.copy(exportProgress = 0.92f) }
                    val savedUri = withContext(Dispatchers.IO) {
                        BitmapIO.saveToGallery(app, outBitmap, format, quality, uri)
                    }
                    val size = ImageSize(outBitmap.width, outBitmap.height)
                    outBitmap.recycle()
                    EditorEvent.Saved(savedUri, size, limited, share)
                }
                _events.send(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _events.send(EditorEvent.Failed(R.string.error_out_of_memory, null))
            } catch (e: Throwable) {
                _events.send(EditorEvent.Failed(R.string.error_save_failed, e.localizedMessage))
            } finally {
                _state.update { it.copy(exportProgress = null) }
            }
        }
    }

    companion object {
        const val PREVIEW_LONG_EDGE = 1600
        const val DRAFT_LONG_EDGE = 800
        const val MAX_HISTORY = 100
        private const val KEY_URI = "uri"
        private const val KEY_ADJUSTMENTS = "adjustments"
    }
}
