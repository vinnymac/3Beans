/*
    Copyright 2023-2026 Hydr8gon

    This file is part of 3Beans.

    3Beans is free software: you can redistribute it and/or modify it
    under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    3Beans is distributed in the hope that it will be useful, but
    WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
    General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with 3Beans. If not, see <https://www.gnu.org/licenses/>.
*/

package com.hydra.threebeans

import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hydra.threebeans.databinding.ActivityEmulationBinding

class EmulationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmulationBinding
    private lateinit var renderer: EmulationRenderer
    private lateinit var secondaryDisplay: SecondaryDisplay
    private var cartPath = ""
    private var gameKey: String? = null
    private var coreStarted = false
    private var audioThread: Thread? = null
    private var keyMap: Map<Int, Int> = emptyMap()
    private val fpsUpdater = object : Runnable {
        override fun run() {
            binding.fpsText.text = getString(R.string.fps_format, NativeLibrary.getFps())
            binding.fpsText.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmulationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep the screen on and hide the system bars while emulating
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // The external display, when one is connected and enabled, takes a
        // screen away from the primary layout
        secondaryDisplay = SecondaryDisplay(this) { renderer.invalidateLayout() }
        secondaryDisplay.register()

        renderer = EmulationRenderer(
            pullsFrames = true,
            computeLayout = { w, h -> ScreenLayout.compute(w, h, primaryConfig()) },
            onLayout = { layout ->
                binding.overlay.post { binding.overlay.bottomScreen = layout.touch ?: Rect() }
            }
        )
        binding.glSurface.setEGLContextClientVersion(2)
        binding.glSurface.preserveEGLContextOnPause = true
        binding.glSurface.setRenderer(renderer)

        binding.menuButton.setOnClickListener { showMenu() }
        binding.editorSave.setOnClickListener { saveLayoutEdits() }
        binding.editorReset.setOnClickListener { binding.layoutEditor.reset() }
        binding.editorCancel.setOnClickListener { exitLayoutEditor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutEditor.isVisible) exitLayoutEditor() else showMenu()
            }
        })

        startEmulation()
    }

    // The screens shown on this display: both normally, or whatever the
    // external display isn't already showing
    private fun primaryConfig(): ScreenLayout.Config {
        val config = ScreenLayout.loadConfig(PreferenceManager.getDefaultSharedPreferences(this))
        val secondary = secondaryDisplay.activeScreens
        return when {
            secondary.isEmpty() -> config
            // With both (or the top) screen external, keep the touchscreen here
            secondary.contains(ScreenLayout.Screen.TOP) ->
                config.copy(screens = setOf(ScreenLayout.Screen.BOTTOM))
            else -> config.copy(screens = setOf(ScreenLayout.Screen.TOP))
        }
    }

    private fun startEmulation() {
        // Resolve system files, warning if any required ones are missing
        val missing = SystemFiles.prepare(this)
        if (missing.isNotEmpty()) {
            showFatalDialog(getString(R.string.error_boot_roms))
            return
        }

        // In folder mode, commit any overlay left by a previously crashed run
        // before booting. A drift result means the folder changed under unsynced
        // writes, so let the user resolve the conflict before continuing.
        if (SystemFiles.sdMode(this) == SystemFiles.MODE_FOLDER &&
            SystemFiles.prepareVirtualSd(this) == 2) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sd_conflict_title)
                .setMessage(R.string.sd_conflict_message)
                .setPositiveButton(R.string.sd_conflict_keep) { _, _ ->
                    SystemFiles.commitVirtualSd(this, allowDrift = true)
                    continueStart()
                }
                .setNegativeButton(R.string.sd_conflict_folder) { _, _ ->
                    SystemFiles.resetVirtualSd(this)
                    continueStart()
                }
                .setCancelable(false)
                .show()
            return
        }
        continueStart()
    }

    private fun continueStart() {
        // Resolve the cart ROM when one was selected
        cartPath = ""
        val cartUri = intent.getStringExtra(EXTRA_CART_URI)
        if (cartUri != null) {
            val path = SystemFiles.openCart(this, Uri.parse(cartUri))
            if (path == null) {
                showFatalDialog(getString(R.string.error_cart_open))
                return
            }
            cartPath = path
        }

        // Apply per-game overrides after system files saved the globals,
        // so they affect only this emulation session
        gameKey = intent.getStringExtra(EXTRA_GAME_KEY)
        gameKey?.let { PerGameSettings.apply(this, it) }

        FrameStore.reset()
        if (NativeLibrary.startCore(cartPath) != 0) {
            showFatalDialog(getString(R.string.error_boot_roms))
            return
        }
        coreStarted = true
    }

    override fun onResume() {
        super.onResume()
        binding.glSurface.onResume()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        binding.overlay.isEnabled = prefs.getBoolean("showOverlay", true)
        binding.overlay.applyPreferences(
            prefs.getInt("overlayScale", 100),
            prefs.getInt("overlayOpacity", 50),
            prefs.getBoolean("haptics", true)
        )
        binding.fpsText.isVisible = prefs.getBoolean("showFps", false)
        binding.fpsText.removeCallbacks(fpsUpdater)
        if (binding.fpsText.isVisible) binding.fpsText.post(fpsUpdater)

        // Layout settings or controller bindings may have changed while away
        keyMap = KeyMap.load(this)
        secondaryDisplay.update()
        renderer.invalidateLayout()

        if (coreStarted) {
            NativeLibrary.resumeCore()
            startAudio()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.glSurface.onPause()
        binding.fpsText.removeCallbacks(fpsUpdater)
        secondaryDisplay.dismiss()
        // Pause the core before stopping audio; the FPS limiter blocks the
        // core thread until the audio consumer drains its buffer
        if (coreStarted) NativeLibrary.pauseCore()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        secondaryDisplay.release()
        if (coreStarted) NativeLibrary.stopCore()
        // Propagate this session's SD writes back into the browsable folder.
        // The core flushed the overlay on stop; if this is interrupted the writes
        // are still safe and get committed on the next launch.
        if (coreStarted && SystemFiles.sdMode(this) == SystemFiles.MODE_FOLDER)
            SystemFiles.commitVirtualSd(this)
        SystemFiles.closeCart()
        // Drop any per-game overrides from the core's live settings
        NativeLibrary.loadSettings(SystemFiles.basePath(this).absolutePath)
    }

    private fun startAudio() {
        if (audioThread != null) return
        val thread = Thread {
            val minBuffer = AudioTrack.getMinBufferSize(
                48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(48000)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, 1024 * 4 * 2))
                .build()
            track.play()

            // The core paces itself against this consumer when the FPS
            // limiter is enabled, so keep pulling samples while running
            val samples = ShortArray(1024 * 2)
            while (!Thread.interrupted()) {
                NativeLibrary.readAudio(samples, 1024)
                track.write(samples, 0, samples.size)
            }
            track.stop()
            track.release()
        }
        thread.start()
        audioThread = thread
    }

    private fun stopAudio() {
        audioThread?.interrupt()
        audioThread?.join()
        audioThread = null
    }

    private fun showMenu() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val showOverlay = prefs.getBoolean("showOverlay", true)
        val items = arrayOf(
            getString(R.string.menu_resume),
            getString(R.string.menu_restart),
            getString(R.string.menu_swap_screens),
            getString(R.string.menu_edit_layout),
            getString(if (showOverlay) R.string.menu_hide_controls else R.string.menu_show_controls),
            getString(R.string.menu_settings),
            getString(R.string.menu_quit)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { dialog, which ->
                when (which) {
                    1 -> {
                        // startCore stops and replaces any live core itself
                        FrameStore.reset()
                        if (coreStarted && NativeLibrary.startCore(cartPath) == 0) {
                            NativeLibrary.resumeCore()
                        }
                    }
                    2 -> {
                        prefs.edit().putBoolean(
                            ScreenLayout.PREF_SWAP,
                            !prefs.getBoolean(ScreenLayout.PREF_SWAP, false)
                        ).apply()
                        renderer.invalidateLayout()
                        secondaryDisplay.dismiss()
                        secondaryDisplay.update()
                    }
                    3 -> enterLayoutEditor()
                    4 -> {
                        prefs.edit().putBoolean("showOverlay", !showOverlay).apply()
                        binding.overlay.isEnabled = !showOverlay
                        binding.overlay.invalidate()
                    }
                    5 -> {
                        // Per-game overrides are live in the core, so route
                        // core-setting edits to the override store rather
                        // than letting them leak into the global ini
                        val intent = android.content.Intent(this, SettingsActivity::class.java)
                        gameKey?.let {
                            intent.putExtra(SettingsActivity.EXTRA_GAME_KEY, it)
                            intent.putExtra(
                                SettingsActivity.EXTRA_GAME_NAME,
                                this.intent.getStringExtra(EXTRA_GAME_NAME) ?: it
                            )
                        }
                        startActivity(intent)
                    }
                    6 -> finish()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun enterLayoutEditor() {
        binding.layoutEditor.isVisible = true
        binding.editorBar.isVisible = true
        binding.overlay.isVisible = false
        binding.menuButton.isVisible = false
        binding.layoutEditor.post {
            binding.layoutEditor.begin(PreferenceManager.getDefaultSharedPreferences(this))
        }
    }

    private fun exitLayoutEditor() {
        binding.layoutEditor.isVisible = false
        binding.editorBar.isVisible = false
        binding.overlay.isVisible = true
        binding.menuButton.isVisible = true
    }

    // Persist the edited rectangles and switch this orientation's layout
    // mode to custom so the result is visible immediately
    private fun saveLayoutEdits() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        binding.layoutEditor.save(prefs)
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        prefs.edit().putString(
            if (portrait) ScreenLayout.PREF_PORTRAIT else ScreenLayout.PREF_MODE,
            (if (portrait) ScreenLayout.PortraitLayout.CUSTOM_LAYOUT.ordinal
            else ScreenLayout.LayoutMode.CUSTOM_LAYOUT.ordinal).toString()
        ).apply()
        renderer.invalidateLayout()
        exitLayoutEditor()
    }

    private fun showFatalDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    // Physical gamepad and keyboard input through the user's bindings
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = keyMap[event.keyCode]
        if (key == null || event.repeatCount > 0)
            return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> NativeLibrary.pressKey(key)
            KeyEvent.ACTION_UP -> NativeLibrary.releaseKey(key)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            event.action != MotionEvent.ACTION_MOVE
        )
            return super.onGenericMotionEvent(event)

        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        NativeLibrary.setLStick(
            (x * NativeLibrary.STICK_RANGE).toInt(),
            (-y * NativeLibrary.STICK_RANGE).toInt()
        )

        updateHat(event.getAxisValue(MotionEvent.AXIS_HAT_X), NativeLibrary.KEY_LEFT, NativeLibrary.KEY_RIGHT)
        updateHat(event.getAxisValue(MotionEvent.AXIS_HAT_Y), NativeLibrary.KEY_UP, NativeLibrary.KEY_DOWN)
        return true
    }

    private val hatState = HashMap<Int, Boolean>()

    private fun updateHat(value: Float, negativeKey: Int, positiveKey: Int) {
        setHatKey(negativeKey, value < -0.5f)
        setHatKey(positiveKey, value > 0.5f)
    }

    private fun setHatKey(key: Int, state: Boolean) {
        if (hatState[key] == state) return
        hatState[key] = state
        if (state) NativeLibrary.pressKey(key) else NativeLibrary.releaseKey(key)
    }

    companion object {
        const val EXTRA_CART_URI = "cartUri"
        const val EXTRA_GAME_KEY = "gameKey"
        const val EXTRA_GAME_NAME = "gameName"
    }
}
