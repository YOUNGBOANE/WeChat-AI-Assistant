package com.wxplain.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ConfigActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val createFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) writeExport(uri)
    }

    private val openFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) readImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.menu_backup)
            setBackgroundColor(0xFF1B5E20.toInt())
            setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        })
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        body.addView(TextView(this).apply {
            text = getString(R.string.backup_page_hint)
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 0, 0, dp(16))
        })
        body.addView(actionCard(getString(R.string.backup_export), getString(R.string.backup_export_hint)) {
            createFile.launch(defaultFileName())
        })
        body.addView(actionCard(getString(R.string.backup_import), getString(R.string.backup_import_hint)) {
            openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        })
        root.addView(body)
        setContentView(root)
    }

    private fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        return "wxplain-config-$stamp.json"
    }

    private fun writeExport(uri: Uri) {
        io.execute {
            val result = runCatching {
                val json = ConfigBackup.exportJson(ConfigBackup.snapshot(this))
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入文件")
            }
            main.post {
                result.fold(
                    onSuccess = { toast("已导出") },
                    onFailure = { toast(it.message ?: "导出失败") },
                )
            }
        }
    }

    private fun readImport(uri: Uri) {
        io.execute {
            val result = runCatching {
                val raw = contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > MAX_BYTES) error("文件太大")
                    bytes.toString(Charsets.UTF_8)
                } ?: error("无法读取文件")
                ConfigBackup.parse(raw)
            }
            main.post {
                result.fold(
                    onSuccess = { confirmImport(it) },
                    onFailure = { toast(it.message ?: "导入失败") },
                )
            }
        }
    }

    private fun confirmImport(config: AssistantConfig) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_import))
            .setMessage(getString(R.string.backup_import_confirm) + "\n\n" + ConfigBackup.summary(config))
            .setPositiveButton("导入") { _, _ ->
                ConfigBackup.apply(this, config)
                toast("已导入")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun actionCard(title: String, hint: String, onClick: () -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(16), dp(18))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(0xFF212121.toInt())
        })
        col.addView(TextView(this).apply {
            text = hint
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = ">"
            textSize = 20f
            setTextColor(0xFF9E9E9E.toInt())
        })
        card.addView(row)
        return card
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
