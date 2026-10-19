package com.authbox.android

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject

object RecoveryKit {
    fun create(origin: String, username: String, backup: String, recoveryKey: String, codes: List<String>): String {
        ApiClient(origin)
        require(username.matches(Regex("[a-z][a-z0-9_]{2,31}")))
        recoveryHashes(codes)
        val header = VaultCrypto.restore(backup, recoveryKey, "recovery").use { it.snapshot.copy() }
        return canonical(obj("magic" to "AUTHBOX-RECOVERY-KIT", "version" to 1, "backupFormatVersion" to 1,
            "origin" to origin, "username" to username, "vaultId" to header.getString("vaultId"), "createdAt" to utc(),
            "vaultRecoveryKey" to recoveryKey, "accountRecoveryCodes" to JSONArray(codes), "instructions" to JSONArray(listOf(
                "这是明文恢复材料，请保存到手机以外的安全位置，不要发送给他人；NAS 自动备份不包含此材料。",
                "ABAC1 账号恢复码用于重设平台登录密码并撤销旧设备，每个只能使用一次。",
                "ABR1 保险库恢复密钥只用于解密对应保险库备份，不能作为平台登录凭据。",
                "本文件不包含保险库备份或登录密码；断云恢复还需要单独保存完整加密备份。",
                "注册成功后材料才对应有效账号；重新生成、轮换恢复码或密钥后请更新材料，保留旧备份的对应密钥。"))))
    }

    fun inspect(encoded: String): JSONObject {
        require(encoded.toByteArray(Charsets.UTF_8).size <= 16384)
        val value = parseObject(encoded)
        fields(value, "magic", "version", "backupFormatVersion", "origin", "username", "vaultId", "createdAt", "vaultRecoveryKey", "accountRecoveryCodes", "instructions")
        require(text(value, "magic") == "AUTHBOX-RECOVERY-KIT" && number(value, "version") == 1L && number(value, "backupFormatVersion") == 1L)
        ApiClient(text(value, "origin")); uuid(text(value, "vaultId")); timestamp(text(value, "createdAt"))
        require(text(value, "username").matches(Regex("[a-z][a-z0-9_]{2,31}")))
        require(text(value, "vaultRecoveryKey").matches(Regex("ABR1-[A-Z2-7]{52}-[A-Za-z0-9_-]{8}")))
        val codes = value.getJSONArray("accountRecoveryCodes")
        recoveryHashes((0 until codes.length()).map { codes.getString(it) })
        val instructions = value.getJSONArray("instructions")
        require(instructions.length() in 1..10 && (0 until instructions.length()).all { instructions.get(it) is String })
        return value
    }

    fun write(context: Context, uri: Uri, encoded: String) {
        require(uri.scheme == "content") { "请选择系统文件选择器中的保存位置" }
        inspect(encoded)
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        try {
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
            val readBack = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                try {
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 16384) { "恢复材料文件过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } finally { buffer.fill(0) }
            }
            try { require(readBack.contentEquals(bytes)) { "恢复材料读回复核失败，请重新生成并保存" } }
            finally { readBack.fill(0) }
        } finally { bytes.fill(0) }
    }
}
