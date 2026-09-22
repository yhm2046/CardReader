package com.example.cardreader

import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.io.IOException

object CardReader {
    // 交通部全国一卡通（China T-Union）AID
    private val T_UNION_AID = byteArrayOf(
        0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x08.toByte(), 0xA0.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x06.toByte(), 0x32.toByte(), 0x01.toByte(), 0x01.toByte(), 0x05.toByte()
    )

    // 读取电子钱包余额命令 (GET BALANCE)
    private val CMD_GET_BALANCE = byteArrayOf(
        0x80.toByte(), 0x5C.toByte(), 0x00.toByte(), 0x02.toByte(), 0x04.toByte()
    )

    fun readBalance(tag: Tag): Result<String> {
        val isoDep = IsoDep.get(tag) ?: return Result.failure(Exception("卡片不支持 IsoDep/PBOC 规范"))

        return try {
            isoDep.connect()
            isoDep.timeout = 3000

            // 1. 选择交通部应用目录
            val selectResponse = isoDep.transceive(T_UNION_AID)
            if (!isSuccess(selectResponse)) {
                return Result.failure(Exception("无法识别深圳通/全国交通卡应用"))
            }

            // 2. 读取电子钱包余额
            val balanceResponse = isoDep.transceive(CMD_GET_BALANCE)
            if (isSuccess(balanceResponse) && balanceResponse.size >= 6) {
                // 响应结构: [B0, B1, B2, B3, 0x90, 0x00]
                // 4 字节大端无符号整数（单位：分）
                val rawCent = ((balanceResponse[0].toInt() and 0xFF) shl 24) or
                        ((balanceResponse[1].toInt() and 0xFF) shl 16) or
                        ((balanceResponse[2].toInt() and 0xFF) shl 8) or
                        (balanceResponse[3].toInt() and 0xFF)
                val yuan = rawCent / 100.0
                Result.success(String.format("%.2f", yuan))
            } else {
                Result.failure(Exception("读取余额指令执行失败"))
            }
        } catch (e: IOException) {
            Result.failure(Exception("读卡中断，请重新贴紧卡片: ${e.message}"))
        } finally {
            try {
                isoDep.close()
            } catch (_: IOException) {}
        }
    }

    private fun isSuccess(res: ByteArray): Boolean {
        if (res.size < 2) return false
        val sw1 = res[res.size - 2]
        val sw2 = res[res.size - 1]
        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }
}