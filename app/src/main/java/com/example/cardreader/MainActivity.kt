package com.example.cardreader

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private var uiState = mutableStateOf<CardUiState>(CardUiState.Waiting)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CardReaderScreen(
                        nfcEnabled = nfcAdapter?.isEnabled == true,
                        uiState = uiState.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册前台 ReaderMode，仅监听 NFC-A / ISO 14443-3A 卡片
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    // 后台 NFC 线程回调
    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        uiState.value = CardUiState.Reading

        val result = CardReader.readBalance(tag)
        result.fold(
            onSuccess = { balance ->
                uiState.value = CardUiState.Success(balance)
            },
            onFailure = { error ->
                uiState.value = CardUiState.Error(error.localizedMessage ?: "未知错误")
            }
        )
    }
}

sealed interface CardUiState {
    data object Waiting : CardUiState
    data object Reading : CardUiState
    data class Success(val balance: String) : CardUiState
    data class Error(val message: String) : CardUiState
}

@Composable
fun CardReaderScreen(nfcEnabled: Boolean, uiState: CardUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部标题
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 48.dp)
        ) {
            Text(
                text = "深圳通 / 八达通",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (nfcEnabled) "请将卡片贴在手机背面 NFC 感应区" else "检测到 NFC 未开启，请前往系统设置打开",
                style = MaterialTheme.typography.bodyMedium,
                color = if (nfcEnabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
            )
        }

        // 核心显示区域（卡片样式）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                when (uiState) {
                    is CardUiState.Waiting -> {
                        Text(
                            text = "等待刷卡...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is CardUiState.Reading -> {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                    is CardUiState.Success -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "卡内余额",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "¥",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = uiState.balance,
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    is CardUiState.Error -> {
                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 底部提示
        Text(
            text = "手机芯片感应区位于后置摄像头模组附近",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}