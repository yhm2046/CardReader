package com.example.cardreader

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcF
import java.io.ByteArrayOutputStream
import java.io.IOException

enum class CardType(val label: String, val currencySymbol: String) {
    CHINA_T_UNION("深圳通 / 交通联合", "¥"),
    JAPAN_IC("日本交通卡 (Suica / PASMO 等)", "円")
}

data class CardInfo(
    val type: CardType,
    val balance: String
)

object CardReader {

    // 交通部全国一卡通（China T-Union）AID
    private val T_UNION_AID = byteArrayOf(
        0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x08.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x06.toByte(), 0x32.toByte(), 0x01.toByte(), 0x01.toByte(), 0x05.toByte()
    )

    // 读取电子钱包余额指令 (GET BALANCE)
    private val CMD_GET_BALANCE = byteArrayOf(
        0x80.toByte(), 0x5C.toByte(), 0x00.toByte(), 0x02.toByte(), 0x04.toByte()
    )

    fun readCard(tag: Tag): Result<CardInfo> {
        val techList = tag.techList.toList()

        return when {
            // 1. 优先判断是否为日本 FeliCa (Suica / PASMO / ICOCA)
            techList.contains(NfcF::class.java.name) -> {
                readJapaneseCard(tag)
            }
            // 2. 判断是否为中国 IsoDep (深圳通 / 交通联合)
            techList.contains(IsoDep::class.java.name) -> {
                readChineseCard(tag)
            }
            else -> {
                Result.failure(Exception("未能识别的卡片类型（非 IsoDep / NfcF）"))
            }
        }
    }

    // --- 中国卡片读取逻辑 (IsoDep / PBOC) ---
    private fun readChineseCard(tag: Tag): Result<CardInfo> {
        val isoDep = IsoDep.get(tag) ?: return Result.failure(Exception("无法获取 IsoDep 接口"))
        return try {
            isoDep.connect()
            isoDep.timeout = 2000

            val selectResponse = isoDep.transceive(T_UNION_AID)
            if (!isSuccess(selectResponse)) {
                return Result.failure(Exception("非标准交通联合/深圳通卡片"))
            }

            val balanceResponse = isoDep.transceive(CMD_GET_BALANCE)
            if (isSuccess(balanceResponse) && balanceResponse.size >= 6) {
                val rawCent = ((balanceResponse[0].toInt() and 0xFF) shl 24) or
                        ((balanceResponse[1].toInt() and 0xFF) shl 16) or
                        ((balanceResponse[2].toInt() and 0xFF) shl 8) or
                        (balanceResponse[3].toInt() and 0xFF)
                val yuan = rawCent / 100.0
                Result.success(CardInfo(CardType.CHINA_T_UNION, String.format("%.2f", yuan)))
            } else {
                Result.failure(Exception("读取余额指令执行失败"))
            }
        } catch (e: IOException) {
            Result.failure(Exception("读卡中断，请贴紧卡片: ${e.message}"))
        } finally {
            try { isoDep.close() } catch (_: IOException) {}
        }
    }

    // --- 日本交通卡读取逻辑 (NfcF / FeliCa) ---
    private fun readJapaneseCard(tag: Tag): Result<CardInfo> {
        val nfcF = NfcF.get(tag) ?: return Result.failure(Exception("无法获取 NfcF 接口"))
        return try {
            nfcF.connect()
            nfcF.timeout = 2000

            // 1. 获取 IDm (卡片制造识别码，tag.id 通常即为 IDm)
            val idm = tag.id
            if (idm.size < 8) {
                return Result.failure(Exception("无法获取有效的卡片 IDm"))
            }

            // 2. 构造 Read Without Encryption 指令
            // 针对交通服务 Service Code: 0x090F (小端序存储为 0x0F, 0x09)
            // 读取 Block 0 (第一条历史交易记录)
            val outputStream = ByteArrayOutputStream()
            outputStream.write(0x10) // 帧长度预留 (16 字节)
            outputStream.write(0x06) // 读命令码 (Read Without Encryption)
            outputStream.write(idm, 0, 8) // 8 字节 IDm
            outputStream.write(0x01) // 包含的服务数量: 1
            outputStream.write(0x0F) // Service Code List (0x090F 低字节)
            outputStream.write(0x09) // Service Code List (0x090F 高字节)
            outputStream.write(0x01) // 需读取的 Block 数量: 1
            outputStream.write(0x80) // Block List Element (两字节模式标识)
            outputStream.write(0x00) // Block Number: 0 (最新记录)

            val cmd = outputStream.toByteArray()
            cmd[0] = cmd.size.toByte() // 填入准确帧长度

            val response = nfcF.transceive(cmd)

            // 3. 校验响应报文
            // 响应头: [0:长度, 1:0x07, 2-9:IDm, 10:状态标志1(0x00), 11:状态标志2(0x00), 12:块数量(0x01), 13-28:块数据(16字节)]
            if (response.size >= 29 && response[10] == 0x00.toByte() && response[11] == 0x00.toByte()) {
                // Suica/PASMO 余额存储于第 13 字节开始的数据块中，偏移量为 +11 和 +12 (小端序 Little-Endian)
                val balanceOffset = 13 + 11
                val b0 = response[balanceOffset].toInt() and 0xFF
                val b1 = response[balanceOffset + 1].toInt() and 0xFF
                val balanceYen = b0 or (b1 shl 8)

                Result.success(CardInfo(CardType.JAPAN_IC, balanceYen.toString()))
            } else {
                Result.failure(Exception("Suica/PASMO 数据块读取失败"))
            }
        } catch (e: IOException) {
            Result.failure(Exception("FeliCa 通信中断: ${e.message}"))
        } finally {
            try { nfcF.close() } catch (_: IOException) {}
        }
    }

    private fun isSuccess(res: ByteArray): Boolean {
        if (res.size < 2) return false
        val sw1 = res[res.size - 2]
        val sw2 = res[res.size - 1]
        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }
}