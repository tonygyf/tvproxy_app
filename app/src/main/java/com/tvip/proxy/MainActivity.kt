package com.tvip.proxy

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tvip.proxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 展示一次性授权命令，包名直接从当前App读取，不用手打容易出错
        binding.textGrantCommand.text =
            "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

        // 回填上次保存的订阅地址
        binding.editSubUrl.setText(SettingsStore.getSubscriptionUrl(this) ?: "")

        binding.btnConnect.setOnClickListener {
            val url = binding.editSubUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "请先输入订阅地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SettingsStore.setSubscriptionUrl(this, url)

            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_START
            }
            startForegroundService(intent)
        }

        binding.btnDisconnect.setOnClickListener {
            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_STOP
            }
            startForegroundService(intent)
        }

        binding.btnRefreshNodes.setOnClickListener {
            refreshNodeList()
        }

        binding.btnConnect.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        binding.textStatus.text = "状态：${ProxyService.lastStatus}"
        ProxyService.statusListener = { status ->
            runOnUiThread {
                binding.textStatus.text = "状态：$status"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ProxyService.statusListener = null
    }

    private fun refreshNodeList() {
        val result = ProxyManager.listNodes()
        binding.nodeListContainer.removeAllViews()

        if (result == null) {
            Toast.makeText(this, "还没有可用节点，先确认代理已连接成功", Toast.LENGTH_SHORT).show()
            return
        }

        val (groupName, nodes) = result
        val current = ProxyManager.currentNode()

        for (node in nodes) {
            val button = Button(this).apply {
                text = if (node.name == current) "● ${node.name}（当前使用）" else node.name
                gravity = Gravity.START
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundResource(R.drawable.tv_button_bg)
                val marginParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                layoutParams = marginParams
                setPadding(32, 24, 32, 24)
                setOnClickListener {
                    val ok = ProxyManager.selectNode(groupName, node.name)
                    if (ok) {
                        SettingsStore.setSelectedNode(this@MainActivity, node.name)
                        Toast.makeText(
                            this@MainActivity,
                            "已切换到 ${node.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshNodeList()
                    } else {
                        Toast.makeText(this@MainActivity, "切换失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binding.nodeListContainer.addView(button)
        }
    }
}
