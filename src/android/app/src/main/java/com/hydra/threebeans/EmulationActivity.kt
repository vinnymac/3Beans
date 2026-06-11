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
    private var cartPath = ""
    private var coreStarted = false
    private var audioThread: Thread? = null
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

        binding.glSurface.setEGLContextClientVersion(2)
        binding.glSurface.preserveEGLContextOnPause = true
        binding.glSurface.setRenderer(EmulationRenderer { layout ->
            binding.overlay.post { binding.overlay.bottomScreen = layout.bottom }
        })

        binding.menuButton.setOnClickListener { showMenu() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = showMenu()
        })

        startEmulation()
    }

    private fun startEmulation() {
        // Resolve system files, warning if any required ones are missing
        val missing = SystemFiles.prepare(this)
        if (missing.isNotEmpty()) {
            showFatalDialog(getString(R.string.error_boot_roms))
            return
        }

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

        if (coreStarted) {
            NativeLibrary.resumeCore()
            startAudio()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.glSurface.onPause()
        binding.fpsText.removeCallbacks(fpsUpdater)
        // Pause the core before stopping audio; the FPS limiter blocks the
        // core thread until the audio consumer drains its buffer
        if (coreStarted) NativeLibrary.pauseCore()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (coreStarted) NativeLibrary.stopCore()
        SystemFiles.closeCart()
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
        val showOverlay = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("showOverlay", true)
        val items = arrayOf(
            getString(R.string.menu_resume),
            getString(R.string.menu_restart),
            getString(if (showOverlay) R.string.menu_hide_controls else R.string.menu_show_controls),
            getString(R.string.menu_settings),
            getString(R.string.menu_quit)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { dialog, which ->
                when (which) {
                    1 -> {
                        if (coreStarted && NativeLibrary.startCore(cartPath) == 0) {
                            NativeLibrary.resumeCore()
                        }
                    }
                    2 -> {
                        PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putBoolean("showOverlay", !showOverlay).apply()
                        binding.overlay.isEnabled = !showOverlay
                        binding.overlay.invalidate()
                    }
                    3 -> startActivity(android.content.Intent(this, SettingsActivity::class.java))
                    4 -> finish()
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showFatalDialog(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    // Physical gamepad and keyboard input
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = KEY_MAP[event.keyCode]
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

        private val KEY_MAP = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to NativeLibrary.KEY_A,
            KeyEvent.KEYCODE_BUTTON_B to NativeLibrary.KEY_B,
            KeyEvent.KEYCODE_BUTTON_X to NativeLibrary.KEY_X,
            KeyEvent.KEYCODE_BUTTON_Y to NativeLibrary.KEY_Y,
            KeyEvent.KEYCODE_BUTTON_L1 to NativeLibrary.KEY_L,
            KeyEvent.KEYCODE_BUTTON_R1 to NativeLibrary.KEY_R,
            KeyEvent.KEYCODE_BUTTON_START to NativeLibrary.KEY_START,
            KeyEvent.KEYCODE_BUTTON_SELECT to NativeLibrary.KEY_SELECT,
            KeyEvent.KEYCODE_DPAD_UP to NativeLibrary.KEY_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to NativeLibrary.KEY_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to NativeLibrary.KEY_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to NativeLibrary.KEY_RIGHT
        )
    }
}
