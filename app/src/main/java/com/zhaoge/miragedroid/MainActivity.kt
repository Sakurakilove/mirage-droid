package com.zhaoge.miragedroid

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 临时的 M0 空壳：仅用于验证 CI 能产出 APK。
 * 后续替换为设备库（Hub）-> 设备页 -> 屏幕镜像/控制台/表盘管理。
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "幻影模拟器 MirageDroid\nM0 空壳构建\n\nqemu 核心与界面在后续里程碑接入。"
            textSize = 18f
            setPadding(64, 160, 64, 64)
        }
        setContentView(tv)
    }
}
