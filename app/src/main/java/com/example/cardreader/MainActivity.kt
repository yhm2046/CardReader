package com.example.cardreader

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        // 关键：同时监听 NFC-A (中国交通联合) 与 NFC-F (日本 FeliCa)
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        uiState.value = CardUiState.Reading

        val result = CardReader.readCard(tag)
        result.fold(
            onSuccess = { cardInfo ->
                uiState.value = CardUiState.Success(cardInfo)
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
    data class Success(val cardInfo: CardInfo) : CardUiState
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
                text = "中日交通卡查询",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (nfcEnabled) "支持 深圳通/全国一卡通、Suica、PASMO 等" else "请先在系统设置中开启 NFC",
                style = MaterialTheme.typography.bodyMedium,
                color = if (nfcEnabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
            )
        }

        // 核心显示区域
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
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
                            text = "请将卡片贴在手机背面",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is CardUiState.Reading -> {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                    is CardUiState.Success -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 动态显示识别出的卡种
                            SuggestionChip(
                                onClick = {},
                                label = { Text(uiState.cardInfo.type.label) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "卡内余额",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = uiState.cardInfo.type.currencySymbol,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = uiState.cardInfo.balance,
                                    fontSize = 46.sp,
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
            text = "NFC 位于后置摄像头横条正下方区域",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}