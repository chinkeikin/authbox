package com.authbox.android

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.CameraPreview
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable internal fun ImportControls(state: ScreenState, preview: ImportPreview, onQr: (String) -> Unit,
    onImages: () -> Unit, onConfirm: (Boolean, Boolean) -> Unit, onCancel: () -> Unit, interaction: ((Boolean) -> Unit)? = null) {
    var manual by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var camera by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(false) }
    var skip by remember { mutableStateOf(false) }
    DisposableEffect(manual, camera) {
        interaction?.invoke(manual || camera)
        onDispose { interaction?.invoke(false) }
    }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        interaction?.invoke(false)
        Toast.makeText(context, if (granted) "相机权限已允许，请解锁后再次扫码" else "未允许相机，可选择图片或手动输入", Toast.LENGTH_LONG).show()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton({
            interaction?.invoke(true)
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera = true
            else permission.launch(Manifest.permission.CAMERA)
        }, enabled = state.canEdit && !state.busy) { Text("相机扫码") }
        OutlinedButton({ interaction?.invoke(true); onImages() }, enabled = state.canEdit && !state.busy) { Text("二维码图片") }
        TextButton({ interaction?.invoke(true); manual = true }, enabled = state.canEdit && !state.busy) { Text("粘贴二维码") }
    }
    if (manual) AlertDialog(onDismissRequest = { manual = false; text = "" }, title = { Text("粘贴二维码内容") },
        text = { OutlinedTextField(text, { text = it }, label = { Text("otpauth 或迁移 URI") }, maxLines = 5) },
        confirmButton = { TextButton({ onQr(text); text = ""; manual = false }) { Text("加入导入预览") } },
        dismissButton = { TextButton({ text = ""; manual = false }) { Text("取消") } })
    if (camera) CameraDialog({ value -> onQr(value); camera = false }, { camera = false })
    if (preview.rows.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) {
            Text("导入批次：${preview.received}/${preview.expected}，${preview.rows.size} 条，重复 ${preview.rows.count { it.duplicate }} 条，不支持 ${preview.rows.count { it.payload == null }} 条")
            Text(if (preview.complete) "批次完整，确认后保存到本机" else "批次尚未收齐，请继续扫描剩余二维码")
            TextButton({ expanded = true }) { Text("查看并确认导入") }
            TextButton(onCancel, enabled = !state.busy) { Text("取消当前导入") }
        } }
        if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("确认导入") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${preview.received}/${preview.expected} 张；重复项默认跳过。回收站中的相同条目也算重复，不会自动覆盖或复活。")
                preview.rows.forEach { row -> Text("${row.label} · ${if (row.payload == null) row.reason else if (row.duplicate) "重复" else "支持"}") }
                Row { Checkbox(keep, { keep = it }); Text("重复项也保留为独立账号", Modifier.padding(top = 12.dp)) }
                if (preview.rows.any { it.payload == null }) Row { Checkbox(skip, { skip = it }); Text("已知不支持条目，将跳过", Modifier.padding(top = 12.dp)) }
            }
        }, confirmButton = { TextButton({ onConfirm(keep, skip); expanded = false }, enabled = preview.complete && !state.busy &&
            (preview.rows.all { it.payload != null } || skip) && preview.rows.any { it.payload != null && (!it.duplicate || keep) }) { Text("确认保存导入") } },
            dismissButton = { TextButton({ expanded = false }) { Text("返回继续扫描") } })
    }
}

@Composable private fun CameraDialog(onQr: (String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf("正在打开相机…") }
    val view = remember {
        DecoratedBarcodeView(context).apply {
            setDecoderFactory(DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)))
            setStatusText("")
            barcodeView.addStateListener(object : CameraPreview.StateListener {
                override fun previewStarted() { status = "相机已就绪，请对准二维码" }
                override fun previewStopped() {}
                override fun previewSized() {}
                override fun cameraClosed() {}
                override fun cameraError(error: Exception) { status = "相机不可用，可选择本地图片导入" }
            })
            decodeSingle(object : BarcodeCallback {
                override fun barcodeResult(result: BarcodeResult) { onQr(result.text) }
                override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
            })
        }
    }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) view.pause() else if (event == Lifecycle.Event.ON_RESUME) view.resume() }
        lifecycle.addObserver(observer); view.resume()
        onDispose { lifecycle.removeObserver(observer); view.pause() }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Text(status)
            Text("只在本机识别，不会打开二维码中的链接。锁定会取消尚未确认的导入。")
            AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
            OutlinedButton(dismiss) { Text("关闭相机") }
        } }
    }
}
