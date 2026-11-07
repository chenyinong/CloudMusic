package com.cloudmusic.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    serverDefault: String,
    accountDefault: String,
    busy: Boolean,
    error: String?,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onConnect: (String, String, String) -> Unit,
    onSwitchToOneDrive: () -> Unit
) {
    var server by remember { mutableStateOf(serverDefault) }
    var account by remember { mutableStateOf(accountDefault) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        if (canGoBack) {
            TextButton(onClick = onBack) {
                Text("← 返回曲库")
            }
            Spacer(Modifier.height(4.dp))
        }

        Text("云音乐", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "直接从你的网盘播放音乐",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("服务器地址") },
            supportingText = { Text("坚果云保持默认即可") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text("账号（注册邮箱）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("应用密码") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = "显示密码"
                    )
                }
            },
            supportingText = { Text("不是登录密码，是网盘里单独生成的应用密码") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(6.dp))

        TextButton(onClick = { showHelp = !showHelp }) {
            Text(if (showHelp) "收起教程" else "怎么拿应用密码？看这里")
        }

        if (showHelp) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("坚果云获取应用密码", fontWeight = FontWeight.Bold)
                    Text("1. 电脑浏览器打开 jianguoyun.com 并登录")
                    Text("2. 点右上角账号名 → 账户信息 → 安全选项")
                    Text("3. 找到「第三方应用管理」→ 添加应用密码")
                    Text("4. 名称随便填（如 云音乐）→ 生成密码")
                    Text("5. 复制那串密码，粘贴到上面的「应用密码」框")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "服务器地址保持默认：https://dav.jianguoyun.com/dav/",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "任何标准 WebDAV 网盘（Nextcloud、群晖等）都支持，换地址即可。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onConnect(server, account, password) },
            enabled = !busy && account.isNotBlank() && password.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.size(10.dp))
                Text("连接中…")
            } else {
                Text("连接并扫描音乐库", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "仅供个人使用 · 不会上传你的任何文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSwitchToOneDrive, modifier = Modifier.fillMaxWidth()) {
            Text("用 OneDrive（微软账号）登录")
        }
    }
}