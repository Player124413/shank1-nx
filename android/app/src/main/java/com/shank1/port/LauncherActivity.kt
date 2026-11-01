package com.shank1.port

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The launcher: import the game's data, set the port's options, edit the touch controls, play.
 *
 * The import is a folder the player picks with the system picker (SAF) - never automatic, never from the APK, never
 * uploaded. It copies the files into the app's private storage and packs them (GameData.packInPlace), which is what
 * makes the engine's later launches fast.
 */
class LauncherActivity : Activity() {

    private lateinit var view: LauncherView
    private lateinit var prefs: Prefs
    private val importing = AtomicBoolean(false)
    private val cancelImport = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.writeToml()

        val root = FrameLayout(this)
        view = LauncherView(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        view.onItem = ::onItem
        applyInsets(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!importing.get()) refresh()
    }

    private fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun refresh() {
        val summary = GameData.summary(this)
        val status = when {
            summary == null -> getString(R.string.status_not_installed)
            summary.packed -> getString(
                R.string.status_installed_packed, GameData.formatBytes(summary.bytes), summary.files.toString(),
            )
            else -> getString(R.string.status_installed, GameData.formatBytes(summary.bytes), summary.files)
        }
        view.setState(
            LauncherView.State(
                installed = GameData.isInstalled(this),
                status = status,
                version = BuildConfig.VERSION_NAME,
            ),
        )
    }

    private fun onItem(item: LauncherView.Item) {
        when (item) {
            LauncherView.Item.PLAY -> startGame()
            LauncherView.Item.IMPORT -> pickGameFolder()
            LauncherView.Item.CONTROLS -> startActivity(Intent(this, ControlsActivity::class.java))
            LauncherView.Item.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            LauncherView.Item.ABOUT -> showAbout()
        }
    }

    private fun pickGameFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        try {
            startActivityForResult(intent, REQUEST_FOLDER)
        } catch (e: Exception) {
            toast(getString(R.string.status_install_failed, e.message ?: "no folder picker"))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { startImport(it) }
        }
    }

    private fun startImport(treeUri: Uri) {
        if (importing.getAndSet(true)) return
        cancelImport.set(false)
        view.setState(LauncherView.State(false, getString(R.string.status_checking), busy = true, progress = 0))

        thread(name = "shank1-import") {
            var failure: String? = null
            try {
                // The grant outlives this call: the copy may need to be redone after a reboot.
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (e: SecurityException) {
                    // Not persistable: the import still works while the grant lasts.
                }
                val (prefix, files) = GameData.scan(this, treeUri)
                if (files.isEmpty()) error("no files in $prefix")
                GameData.deleteAll(this)
                GameData.copyIn(
                    this, files,
                    progress = { bytes, file ->
                        runOnUiThread {
                            view.setState(
                                LauncherView.State(
                                    installed = false,
                                    status = getString(R.string.status_installing, file) + "  " +
                                        GameData.formatBytes(bytes),
                                    busy = true,
                                ),
                            )
                        }
                    },
                    cancelled = { cancelImport.get() },
                )
                runOnUiThread {
                    view.setState(
                        LauncherView.State(false, getString(R.string.status_packing, 0), busy = true),
                    )
                }
                GameData.packInPlace(
                    this,
                    progress = { percent ->
                        runOnUiThread {
                            view.setState(
                                LauncherView.State(
                                    false, getString(R.string.status_packing, percent), busy = true, progress = percent,
                                ),
                            )
                        }
                    },
                    cancelled = { cancelImport.get() },
                )
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                importing.set(false)
                runOnUiThread {
                    when {
                        failure != null && cancelImport.get() -> toast(getString(R.string.status_install_cancelled))
                        failure != null -> toast(getString(R.string.status_install_failed, failure))
                        !GameData.isInstalled(this) ->
                            toast(getString(R.string.status_install_failed, "default.xex not found"))
                    }
                    refresh()
                }
            }
        }
    }

    private fun startGame() {
        if (!GameData.isInstalled(this)) {
            pickGameFolder()
            return
        }
        prefs.writeToml()
        // The game gets its data and its options explicitly and takes over the screen.
        startActivity(
            Intent(this, GameActivity::class.java).apply {
                putExtra(GameActivity.EXTRA_DATA_ROOT, gameRootForEngine())
                putExtra(GameActivity.EXTRA_CONFIG, prefs.tomlFile().absolutePath)
            },
        )
    }

    /** The pack when it exists, the loose folder otherwise: the engine takes either. */
    private fun gameRootForEngine(): String {
        val pack = GameData.packFile(this)
        return if (pack.isFile) pack.absolutePath else GameData.looseRoot(this).absolutePath
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQUEST_FOLDER = 4001
    }
}
