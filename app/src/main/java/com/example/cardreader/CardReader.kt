package com.example.cardreader

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcF
import java.io.IOException
import java.util.Locale

enum class CardType(val label: String, val currencySymbol: String) {
    CHINA_T_UNION("深圳通 / 全国通", "¥"),
    JAPAN_IC("日本交通卡 (Suica / PASMO / ICOCA 等)", "円"),
    OCTOPUS("香港八达通 Octopus", "HK$")
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

    // ---- FeliCa 常量 ----
    private const val SYSTEM_JAPAN_IC = 0x0003          // Suica / PASMO / ICOCA 等日本交通卡
    private const val SYSTEM_OCTOPUS = 0x8008           // 香港八达通
    private const val SERVICE_JAPAN_HISTORY = 0x090F    // 日本交通卡：乘车历史（每块里带余额）
    private const val SERVICE_OCTOPUS_BALANCE = 0x0117  // 八达通：余额
    private const val OCTOPUS_OFFSET = 500              // 八达通余额偏移（单位 0.1 港元，即 50 港元；2017-10 起）

    private enum class FelicaKind(val systemCode: Int, val serviceCode: Int) {
        JAPAN(SYSTEM_JAPAN_IC, SERVICE_JAPAN_HISTORY),
        OCTOPUS(SYSTEM_OCTOPUS, SERVICE_OCTOPUS_BALANCE)
    }

    fun readCard(tag: Tag): Result<CardInfo> {
        val techList = tag.techList.toList()
        val hasFelica = techList.contains(NfcF::class.java.name)
        val hasIsoDep = techList.contains(IsoDep::class.java.name)

        // 1. FeliCa：日本交通卡 或 香港八达通
        if (hasFelica) {
            val result = readFelicaCard(tag)
            if (result.isSuccess || !hasIsoDep) return result
        }
        // 2. IsoDep：深圳通 / 交通联合
        if (hasIsoDep) {
            return readChineseCard(tag)
        }
        return Result.failure(Exception("未能识别的卡片类型（非 IsoDep / NfcF）"))
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
                val rawCent = ((balanceResponse[0].toLong() and 0xFF) shl 24) or
                        ((balanceResponse[1].toLong() and 0xFF) shl 16) or
                        ((balanceResponse[2].toLong() and 0xFF) shl 8) or
                        (balanceResponse[3].toLong() and 0xFF)
                val yuan = rawCent / 100.0
                Result.success(CardInfo(CardType.CHINA_T_UNION, String.format(Locale.US, "%.2f", yuan)))
            } else {
                Result.failure(Exception("读取余额指令执行失败"))
            }
        } catch (e: IOException) {
            Result.failure(Exception("读卡中断，请贴紧卡片: ${e.message}"))
        } finally {
            try { isoDep.close() } catch (_: IOException) {}
        }
    }

    // --- FeliCa 卡读取逻辑：日本交通卡 / 香港八达通 ---
    private fun readFelicaCard(tag: Tag): Result<CardInfo> {
        val nfcF = NfcF.get(tag) ?: return Result.failure(Exception("无法获取 NfcF 接口"))
        return try {
            nfcF.connect()
            nfcF.timeout = 2000

            // 用系统给出的 System Code 判断先试哪种卡（试错顺序），不影响最终结果
            val hint = nfcF.systemCode?.takeIf { it.size == 2 }?.let {
                ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
            }
            val order = if (hint == SYSTEM_OCTOPUS) {
                listOf(FelicaKind.OCTOPUS, FelicaKind.JAPAN)
            } else {
                listOf(FelicaKind.JAPAN, FelicaKind.OCTOPUS)
            }

            // 第一轮：直接用发现卡片时拿到的 IDm（tag.id）
            val idm = tag.id
            if (idm != null && idm.size == 8) {
                for (kind in order) {
                    val block = readBlock0(nfcF, idm, kind.serviceCode) ?: continue
                    return Result.success(parseBlock(kind, block))
                }
            }

            // 第二轮：按各自的 System Code 重新 Polling 取 IDm，再读一次
            for (kind in order) {
                val polled = pollIdm(nfcF, kind.systemCode) ?: continue
                if (idm != null && polled.contentEquals(idm)) continue // 同一个 IDm 已试过
                val block = readBlock0(nfcF, polled, kind.serviceCode) ?: continue
                return Result.success(parseBlock(kind, block))
            }

            Result.failure(Exception("未能读取：不是日本交通卡（Suica/PASMO/ICOCA 等）或香港八达通"))
        } catch (e: IOException) {
            Result.failure(Exception("FeliCa 通信中断，请贴紧卡片: ${e.message}"))
        } finally {
            try { nfcF.close() } catch (_: IOException) {}
        }
    }

    /** Polling：指定 System Code，返回该系统的 IDm；卡片不响应则返回 null。 */
    private fun pollIdm(nfcF: NfcF, systemCode: Int): ByteArray? {
        return try {
            val cmd = byteArrayOf(
                0x06, 0x00,
                ((systemCode shr 8) and 0xFF).toByte(), (systemCode and 0xFF).toByte(),
                0x01, 0x00
            )
            val res = nfcF.transceive(cmd)
            // 响应：[长度, 0x01, IDm(8), PMm(8), ...]
            if (res.size >= 18 && res[1] == 0x01.toByte()) res.copyOfRange(2, 10) else null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Read Without Encryption：读某个服务的第 0 块（16 字节）。
     * 服务代码在报文中是小端序（0x090F -> 0F 09，0x0117 -> 17 01）。
     * 失败（卡片返回错误状态）返回 null。
     */
    private fun readBlock0(nfcF: NfcF, idm: ByteArray, serviceCode: Int): ByteArray? {
        val cmd = ByteArray(16)
        cmd[0] = 16                                   // 总长度
        cmd[1] = 0x06                                 // Read Without Encryption
        System.arraycopy(idm, 0, cmd, 2, 8)           // IDm
        cmd[10] = 0x01                                // 服务数量
        cmd[11] = (serviceCode and 0xFF).toByte()     // 服务代码低字节
        cmd[12] = ((serviceCode shr 8) and 0xFF).toByte() // 服务代码高字节
        cmd[13] = 0x01                                // 块数量
        cmd[14] = 0x80.toByte()                       // Block List Element（2 字节模式）
        cmd[15] = 0x00                                // 块号 0

        val res = nfcF.transceive(cmd)
        // 响应：[长度, 0x07, IDm(8), 状态1, 状态2, 块数, 块数据(16)...]
        return if (res.size >= 29 && res[1] == 0x07.toByte() &&
            res[10] == 0.toByte() && res[11] == 0.toByte()
        ) {
            res.copyOfRange(13, 29)
        } else {
            null
        }
    }

    private fun parseBlock(kind: FelicaKind, block: ByteArray): CardInfo {
        return when (kind) {
            FelicaKind.JAPAN -> {
                // 日本交通卡：余额在块内第 10、11 字节，小端序，单位：日元
                val yen = (block[10].toInt() and 0xFF) or ((block[11].toInt() and 0xFF) shl 8)
                CardInfo(CardType.JAPAN_IC, yen.toString())
            }
            FelicaKind.OCTOPUS -> {
                // 八达通：块内前 4 字节，大端序，单位 0.1 港元，需减去偏移量
                val raw = ((block[0].toLong() and 0xFF) shl 24) or
                        ((block[1].toLong() and 0xFF) shl 16) or
                        ((block[2].toLong() and 0xFF) shl 8) or
                        (block[3].toLong() and 0xFF)
                val hkd = (raw - OCTOPUS_OFFSET) / 10.0
                CardInfo(CardType.OCTOPUS, String.format(Locale.US, "%.1f", hkd))
            }
        }
    }

    private fun isSuccess(res: ByteArray): Boolean {
        if (res.size < 2) return false
        val sw1 = res[res.size - 2]
        val sw2 = res[res.size - 1]
        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }
}