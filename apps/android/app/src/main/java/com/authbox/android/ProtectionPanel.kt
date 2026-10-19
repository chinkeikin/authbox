package com.authbox.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable internal fun ProtectionPanel() {
    val context = LocalContext.current.applicationContext
    val settings = remember { ProtectionSettings(context) }
    var background by remember { mutableStateOf(settings.backgroundSeconds) }
    var idle by remember { mutableStateOf(settings.idleSeconds) }
    var clipboard by remember { mutableStateOf(settings.clipboardSeconds) }
    Column {
        Text("本机锁定与复制", style = MaterialTheme.typography.titleMedium)
        ProtectionChoice("后台锁定", background, listOf(0 to "立即", 15 to "15 秒", 30 to "30 秒", 60 to "60 秒")) { background = it; settings.backgroundSeconds = it }
        ProtectionChoice("闲置锁定", idle, listOf(60 to "1 分钟", 300 to "5 分钟", 900 to "15 分钟")) { idle = it; settings.idleSeconds = it }
        ProtectionChoice("验证码剪贴板清理", clipboard, listOf(15 to "15 秒", 30 to "30 秒", 60 to "60 秒")) { clipboard = it; settings.clipboardSeconds = it }
        Text("后台立即隐藏明文，短时返回可保留普通保险库解锁；熄屏和敏感操作仍立即锁定。系统限制后台读取剪贴板时，返回应用再核对清理，不覆盖后来复制的内容。")
    }
}

@Composable private fun ProtectionChoice(label: String, selected: Int, options: List<Pair<Int, String>>, change: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedButton({ expanded = true }) { Text("$label：${options.single { it.first == selected }.second}") }
    DropdownMenu(expanded, { expanded = false }) { options.forEach { (value, text) -> DropdownMenuItem({ Text(text) }, { change(value); expanded = false }) } }
}
