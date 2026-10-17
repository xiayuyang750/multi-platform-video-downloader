package com.ytdlp.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ytdlp.android.license.LicenseStore

/**
 * 输入授权密钥的对话框。
 *
 * 弹框时机只有两种：用户点「解析」但试用次数已经用完；或者服务端明确判定
 * 这份授权失效（停用 / 换绑）。用户主动从设置页进来也会看到同一个框。
 */
@Composable
fun LicenseDialog(
    state: LicenseUi,
    onActivate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!state.dialog) return

    // 预填上次用过的密钥：换机/被停用的人往往只是需要重输一遍，
    // 没必要让他再去翻聊天记录。
    var input by rememberSaveable { mutableStateOf(state.savedKey) }
    val working = state.working

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = tone.surface,
        title = {
            Text(
                "输入授权密钥",
                fontSize = Font.cardTitle,
                fontWeight = FontWeight.SemiBold,
                color = tone.text,
            )
        },
        text = {
            Column {
                Text(
                    "未激活可以免费解析 ${LicenseStore.TRIAL_TOTAL} 次，用完需要输入密钥继续使用。",
                    fontSize = Font.meta,
                    lineHeight = 20.sp,
                    color = tone.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("WM-XXXX-XXXX-XXXX", fontSize = Font.meta) },
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onActivate(input) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tone.accent,
                        unfocusedBorderColor = tone.border,
                        focusedContainerColor = tone.bg,
                        unfocusedContainerColor = tone.bg,
                        focusedTextColor = tone.text,
                        unfocusedTextColor = tone.text,
                        cursorColor = tone.accent,
                    ),
                )

                if (state.error.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.error,
                        fontSize = Font.meta,
                        lineHeight = 20.sp,
                        color = tone.danger,
                    )
                }

                if (working) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = tone.accent,
                        )
                        Text("正在激活…", fontSize = Font.meta, color = tone.textMuted)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "首次激活需要连接境外授权服务器。国内网络请先开启 VPN，激活成功后即可关闭。",
                    fontSize = Font.hint,
                    lineHeight = 18.sp,
                    color = tone.textMuted,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "一个密钥只能绑定一台手机。换手机时联系开发者解绑，不用重新购买。",
                    fontSize = Font.hint,
                    lineHeight = 18.sp,
                    color = tone.textMuted,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = if (working) "激活中…" else "激活",
                onClick = { onActivate(input) },
                enabled = !working,
            )
        },
        dismissButton = {
            GhostButton(
                text = "以后再说",
                onClick = onDismiss,
                enabled = !working,
            )
        },
    )
}
