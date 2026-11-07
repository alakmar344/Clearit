package com.clearit.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clearit.EditorViewModel
import com.clearit.MainActivity
import com.clearit.R
import com.clearit.engine.Adjustment
import com.clearit.engine.AutoOptions
import com.clearit.engine.Preset
import com.clearit.io.ExportFormat
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Minimal in-memory MediaStore so the real save path can run under Robolectric. */
class FakeMediaProvider : ContentProvider() {
    companion object {
        val files = LinkedHashMap<Uri, File>()
        val inserted = ArrayList<Uri>()
        lateinit var dir: File
        private var nextId = 1
        fun register(file: File): Uri {
            val uri = Uri.parse("content://media/external/images/media/${nextId++}")
            files[uri] = file
            return uri
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri) = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val item = register(File(dir, "out_$nextId"))
        inserted += item
        return item
    }
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = if (files.remove(uri) != null) 1 else 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 1
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = files[uri] ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode) or ParcelFileDescriptor.MODE_CREATE)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {

    private lateinit var inputUri: Uri

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        FakeMediaProvider.dir = context.cacheDir
        FakeMediaProvider.files.clear()
        FakeMediaProvider.inserted.clear()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        for (y in 0 until 160) for (x in 0 until 240) {
            // dim, blue-tinted greyish gradient: auto should brighten it and warm it up
            bitmap.setPixel(x, y, Color.rgb(60 + x / 5, 68 + y / 5, 88 + x / 5))
        }
        val file = File(context.cacheDir, "input.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        inputUri = FakeMediaProvider.register(file)
    }

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    @Test
    fun `bitmap io decodes, converts and saves`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(com.clearit.io.ImageSize(240, 160), com.clearit.io.BitmapIO.readSize(context, inputUri))
        val bitmap = com.clearit.io.BitmapIO.decode(context, inputUri, maxLongEdge = 120)
        assertEquals(120, bitmap.width)
        assertEquals(80, bitmap.height)
        val image = com.clearit.io.BitmapIO.toPixelImage(bitmap)
        val back = com.clearit.io.BitmapIO.toBitmap(image)
        val saved = com.clearit.io.BitmapIO.saveToGallery(context, back, ExportFormat.PNG, 100, inputUri)
        assertTrue(FakeMediaProvider.files[saved]!!.length() > 0)
    }

    @Test
    fun `launches with empty state and every tab panel`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.emptyState).visibility)
                val tabs = activity.findViewById<TabLayout>(R.id.tabs)
                assertEquals(7, tabs.tabCount)
                for (i in 0 until tabs.tabCount) {
                    tabs.getTabAt(i)!!.select()
                    shadowOf(Looper.getMainLooper()).idle()
                }
                assertTrue(activity.findViewById<View>(R.id.openButton).isShown)
            }
        }
    }

    @Test
    fun `opens a photo, auto enhances, syncs sliders, undoes and exports upscaled`() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(inputUri, "image/png")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var vm: EditorViewModel
            lateinit var activity: MainActivity
            scenario.onActivity {
                activity = it
                vm = ViewModelProvider(it)[EditorViewModel::class.java]
            }
            waitFor { vm.state.value.hasImage && vm.after.value != null && !vm.state.value.rendering }
            assertEquals(240, vm.state.value.sourceSize!!.width)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.emptyState).visibility)
            assertNotNull(vm.state.value.analysis)

            // Auto
            vm.autoEnhance(AutoOptions())
            waitFor { !vm.state.value.rendering && vm.state.value.canUndo }
            val auto = vm.state.value.adjustments
            assertTrue("auto exposure ${auto.exposure}", auto.exposure > 0f)
            assertTrue("auto temperature ${auto.temperature}", auto.temperature > 0f)

            // UI sliders mirror the state
            activity.findViewById<TabLayout>(R.id.tabs).getTabAt(2)!!.select()
            shadowOf(Looper.getMainLooper()).idle()
            val sliders = ArrayList<Slider>()
            fun collect(v: View) {
                if (v is Slider && v.isShown) sliders += v
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
            collect(activity.findViewById(R.id.panelContainer))
            assertEquals(6, sliders.size) // Light tab
            assertEquals(auto[Adjustment.EXPOSURE], sliders[0].value, 1e-4f)

            // Preset, undo, redo
            vm.applyPreset(Preset.BLACK_AND_WHITE)
            waitFor { !vm.state.value.rendering }
            assertEquals(-100f, vm.state.value.adjustments.saturation, 0f)
            vm.undo()
            assertEquals(auto, vm.state.value.adjustments)
            vm.redo()
            assertEquals(-100f, vm.state.value.adjustments.saturation, 0f)

            // Rotate + upscale, then export
            vm.rotateClockwise()
            vm.setUpscale(2)
            waitFor { !vm.state.value.rendering }
            assertEquals(320 to 480, vm.state.value.outputSize(vm.maxProcessingPixels)!!.let { it.width to it.height })
            vm.export(ExportFormat.JPEG, 90, share = false)
            waitFor { vm.state.value.exportProgress == null && FakeMediaProvider.inserted.isNotEmpty() }
            waitFor { vm.state.value.exportProgress == null }
            val saved = FakeMediaProvider.files[FakeMediaProvider.inserted.single()]!!
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(saved.absolutePath, options)
            assertEquals(320, options.outWidth)
            assertEquals(480, options.outHeight)
        }
    }
}
