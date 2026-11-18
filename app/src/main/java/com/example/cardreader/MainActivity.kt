package com.example.cardreader

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

enum class ScanMode(val label: String) {
    AUTO_DUAL("自动双账户"),
    ONLY_FELICA("仅八达通/Suica"),
    ONLY_ISODEP("仅深圳通/交联")
}

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private var uiState = mutableStateOf<CardUiState>(CardUiState.Waiting)
    private var scanModeState = mutableStateOf(ScanMode.AUTO_DUAL)
    private var statusHintState = mutableStateOf<String?>(null)

    @Volatile
    private var pendingCardInfo: CardInfo? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 超时未探测到第二张芯片时，恢复默认双模监听
    private val restoreDefaultModeRunnable = Runnable {
        if (pendingCardInfo != null) {
            pendingCardInfo = null
            statusHintState.value = "未探测到八达通芯片响应（可尝试切换顶部“仅八达通”模式重刷）"
            applyReaderMode(scanModeState.value)
        }
    }

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
                        uiState = uiState.value,
                        scanMode = scanModeState.value,
                        statusHint = statusHintState.value,
                        onScanModeChange = { newMode ->
                            mainHandler.removeCallbacksAndMessages(null)
                            pendingCardInfo = null
                            statusHintState.value = null
                            scanModeState.value = newMode
                            applyReaderMode(newMode)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pendingCardInfo = null
        applyReaderMode(scanModeState.value)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacksAndMessages(null)
        pendingCardInfo = null
        nfcAdapter?.disableReaderMode(this)
    }

    private fun applyReaderMode(mode: ScanMode) {
        val adapter = nfcAdapter ?: return
        val flags = when (mode) {
            ScanMode.AUTO_DUAL -> {
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            }
            ScanMode.ONLY_FELICA -> {
                NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            }
            ScanMode.ONLY_ISODEP -> {
                NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            }
        }
        adapter.enableReaderMode(this, this, flags, null)
    }

    /**
     * 核心修复：先彻底关闭 ReaderMode 切断射频场 150ms，使卡片芯片断电复位，
     * 再开启纯 NFC-F 模式唤醒同一张卡上的八达通 FeliCa 芯片。
     */
    private fun cycleRfFieldToFelica() {
        val adapter = nfcAdapter ?: return
        adapter.disableReaderMode(this)

        mainHandler.postDelayed({
            val felicaFlags = NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                    NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
            adapter.enableReaderMode(this, this, felicaFlags, null)
        }, 150L)

        mainHandler.removeCallbacks(restoreDefaultModeRunnable)
        mainHandler.postDelayed(restoreDefaultModeRunnable, 2800L)
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return
        val hasFelica = tag.techList.contains(NfcF::class.java.name)

        if (pendingCardInfo == null) {
            uiState.value = CardUiState.Reading
            statusHintState.value = null
        }

        val result = CardReader.readCard(tag)
        result.fold(
            onSuccess = { cardInfo ->
                val prev = pendingCardInfo
                if (prev != null) {
                    // 第二阶段（八达通 FeliCa）读取成功，与第一阶段（深圳通）合并显示！
                    mainHandler.removeCallbacks(restoreDefaultModeRunnable)
                    val mergedTitles = (prev.title.split(" + ") + cardInfo.title.split(" + "))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(" + ")
                    val mergedBalances = (prev.balances + cardInfo.balances)
                        .distinctBy { "${it.label}_${it.symbol}" }

                    pendingCardInfo = null
                    statusHintState.value = "双芯片账户读取完成"
                    uiState.value = CardUiState.Success(CardInfo(mergedTitles, mergedBalances))

                    // 读完后延迟恢复正常双模监听
                    mainHandler.postDelayed({
                        if (scanModeState.value == ScanMode.AUTO_DUAL) {
                            applyReaderMode(ScanMode.AUTO_DUAL)
                        }
                    }, 500L)
                } else {
                    // 第一阶段读取成功
                    val hasHkd = cardInfo.balances.any { it.symbol == "HK$" }
                    if (scanModeState.value == ScanMode.AUTO_DUAL && !hasFelica && !hasHkd) {
                        // 刚读完深圳通 (NFC-A)，保存结果并立刻断电重启射频场为纯 NFC-F 探测八达通
                        pendingCardInfo = cardInfo
                        statusHintState.value = "已读出深圳通，正在切换射频读取八达通，请保持卡片贴紧..."
                        uiState.value = CardUiState.Success(cardInfo)
                        mainHandler.post { cycleRfFieldToFelica() }
                    } else {
                        statusHintState.value = null
                        uiState.value = CardUiState.Success(cardInfo)
                    }
                }
            },
            onFailure = { error ->
                if (pendingCardInfo == null) {
                    uiState.value = CardUiState.Error(error.localizedMessage ?: "未知错误")
                }
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
fun CardReaderScreen(
    nfcEnabled: Boolean,
    uiState: CardUiState,
    scanMode: ScanMode,
    statusHint: String?,
    onScanModeChange: (ScanMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部标题与模式切换
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 36.dp)
        ) {
            Text(
                text = "中日交通卡查询",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (nfcEnabled) "支持 深圳通、八达通、互通行二合一、Suica 等" else "请先在系统设置中开启 NFC",
                style = MaterialTheme.typography.bodyMedium,
                color = if (nfcEnabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 射频模式切换按钮（方便排查二合一卡的 FeliCa 芯片）
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScanMode.entries.forEach { mode ->
                    FilterChip(
                        selected = scanMode == mode,
                        onClick = { onScanModeChange(mode) },
                        label = { Text(mode.label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // 核心显示区域
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                when (uiState) {
                    is CardUiState.Waiting -> {
                        Text(
                            text = "请将卡片贴在手机背面并保持不动",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is CardUiState.Reading -> {
                        CircularProgressIndicator(strokeWidth = 3.dp)
                    }
                    is CardUiState.Success -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text(uiState.cardInfo.title) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            uiState.cardInfo.balances.forEach { b ->
                                Text(
                                    text = b.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                if (b.amount != null) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = b.symbol,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = b.amount,
                                            fontSize = 38.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (!b.note.isNullOrEmpty()) {
                                        Text(
                                            text = b.note,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                } else {
                                    Text(
                                        text = b.note ?: "读取失败",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            if (!statusHint.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = statusHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
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
            text = "提示：刷二合一卡时请贴紧保持 1~2 秒，等待射频自动切换读出双余额",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}