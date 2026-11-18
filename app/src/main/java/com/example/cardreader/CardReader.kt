package com.example.cardreader

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcF
import android.util.Log
import java.io.IOException
import java.util.Locale

/** 一个账户的余额。amount 为 null 表示这个账户读取失败，原因写在 note 里。 */
data class Balance(
    val label: String,
    val amount: String?,
    val symbol: String,
    val note: String? = null
)

/** 一张卡：卡种名称 + 若干个账户。 */
data class CardInfo(
    val title: String,
    val balances: List<Balance>
)

object CardReader {

    // ================= 中国交通联合 / 深圳通（IsoDep）=================
    // TagInfo 扫描显示卡内有两个应用：MOT_T_EP（电子钱包）、MOT_T_CASH（电子现金）
    private val T_UNION_APPS = listOf(
        "电子钱包 (MOT_T_EP)" to byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x06, 0x32, 0x01, 0x01, 0x05
        ),
        "电子现金 (MOT_T_CASH)" to byteArrayOf(
            0xA0.toByte(), 0x00, 0x00, 0x06, 0x32, 0x01, 0x01, 0x06
        )
    )

    // 读余额指令（按顺序尝试，哪个成功用哪个）
    private val CMD_BALANCE_EP = byteArrayOf(0x80.toByte(), 0x5C, 0x00, 0x02, 0x04)      // 电子钱包余额
    private val CMD_BALANCE_CASH = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x9F.toByte(), 0x79, 0x00) // 电子现金余额(GET DATA 9F79)
    private val CMD_BALANCE_ED = byteArrayOf(0x80.toByte(), 0x5C, 0x00, 0x01, 0x04)      // 电子存折余额

    // ================= FeliCa（NfcF）=================
    private const val SYSTEM_JAPAN_IC = 0x0003          // Suica / PASMO / ICOCA 等
    private const val SYSTEM_OCTOPUS = 0x8008           // 香港八达通
    private const val SERVICE_JAPAN_HISTORY = 0x090F    // 日本交通卡：乘车历史（块内带余额）
    private const val SERVICE_OCTOPUS_BALANCE = 0x0117  // 八达通：余额
    private const val OCTOPUS_OFFSET = 500              // 八达通余额偏移（单位 0.1 港元；2017-10 起）

    private enum class FelicaKind(val systemCode: Int, val serviceCode: Int) {
        JAPAN(SYSTEM_JAPAN_IC, SERVICE_JAPAN_HISTORY),
        OCTOPUS(SYSTEM_OCTOPUS, SERVICE_OCTOPUS_BALANCE)
    }

    fun readCard(tag: Tag): Result<CardInfo> {
        val techList = tag.techList.toList()
        val hasFelica = techList.contains(NfcF::class.java.name)
        val hasIsoDep = techList.contains(IsoDep::class.java.name)

        val titles = mutableListOf<String>()
        val balances = mutableListOf<Balance>()
        val errors = mutableListOf<String>()

        // FeliCa 与 IsoDep 都尝试，成功的结果合并显示（同一张卡可能有多个账户）
        if (hasFelica) {
            readFelicaCard(tag).fold(
                onSuccess = { titles += it.title; balances += it.balances },
                onFailure = { errors += (it.message ?: "FeliCa 读取失败") }
            )
        }
        if (hasIsoDep) {
            readChineseCard(tag).fold(
                onSuccess = { titles += it.title; balances += it.balances },
                onFailure = { errors += (it.message ?: "IsoDep 读取失败") }
            )
        }

        if (balances.any { it.amount != null }) {
            return Result.success(CardInfo(titles.joinToString(" + "), balances))
        }
        val msg = if (errors.isEmpty()) "未能识别的卡片类型（非 IsoDep / NfcF）" else errors.joinToString("\n")
        return Result.failure(Exception(msg))
    }

    // --- 中国卡片：依次读取 电子钱包 / 电子现金 两个账户 ---
    private fun readChineseCard(tag: Tag): Result<CardInfo> {
        val isoDep = IsoDep.get(tag) ?: return Result.failure(Exception("无法获取 IsoDep 接口"))
        return try {
            isoDep.connect()
            isoDep.timeout = 3000

            val balances = mutableListOf<Balance>()
            for ((label, aid) in T_UNION_APPS) {
                val sel = isoDep.transceive(selectCmd(aid))
                if (!isSuccess(sel)) {
                    balances += Balance(label, null, "¥", "无法选择该应用 (SW=${sw(sel)})")
                    continue
                }
                val (cents, debug) = readBalanceCents(isoDep)
                balances += if (cents != null) {
                    Balance(label, String.format(Locale.US, "%.2f", cents / 100.0), "¥")
                } else {
                    // 读不出余额：把这个应用里能读到的东西都探测一遍，结果输出到 Logcat（标签 CardReader）
                    val note = if (DEBUG_PROBE) {
                        "余额读取失败 $debug\n诊断: ${probeApp(isoDep, label, sel)}"
                    } else {
                        "余额读取失败 $debug"
                    }
                    Balance(label, null, "¥", note)
                }
            }

            if (balances.any { it.amount != null }) {
                // 正常模式下，读不出余额的账户（例如没有启用的应用）不显示
                val shown = if (DEBUG_PROBE) balances else balances.filter { it.amount != null }
                Result.success(CardInfo("深圳通 / 交通联合", shown))
            } else {
                Result.failure(Exception(balances.joinToString("\n") { "${it.label}: ${it.note}" }))
            }
        } catch (e: IOException) {
            Result.failure(Exception("读卡中断，请贴紧卡片: ${e.message}"))
        } finally {
            try { isoDep.close() } catch (_: IOException) {}
        }
    }

    /** 依次尝试几种读余额指令。返回 (余额，单位分；失败为 null, 调试信息)。 */
    private fun readBalanceCents(isoDep: IsoDep): Pair<Long?, String> {
        val debug = StringBuilder()

        // 1) 电子钱包：805C0002 04，返回 4 字节余额（分）
        val r1 = isoDep.transceive(CMD_BALANCE_EP)
        if (isSuccess(r1) && r1.size >= 6) return Pair(bigEndian(r1, 0, 4), "")
        debug.append("[805C0002 SW=${sw(r1)}]")

        // 2) 电子现金：GET DATA 9F79，返回 9F79 06 + 6 字节 BCD 余额（分）
        val r2 = isoDep.transceive(CMD_BALANCE_CASH)
        if (isSuccess(r2) && r2.size >= 11 &&
            r2[0] == 0x9F.toByte() && r2[1] == 0x79.toByte() && r2[2] == 0x06.toByte()
        ) {
            val v = bcd(r2, 3, 6)
            if (v != null) return Pair(v, "")
        }
        debug.append("[CA9F79 SW=${sw(r2)}]")

        // 3) 电子存折：805C0001 04
        val r3 = isoDep.transceive(CMD_BALANCE_ED)
        if (isSuccess(r3) && r3.size >= 6) return Pair(bigEndian(r3, 0, 4), "")
        debug.append("[805C0001 SW=${sw(r3)}]")

        return Pair(null, debug.toString())
    }

    // --- FeliCa：日本交通卡 / 香港八达通 ---
    private fun readFelicaCard(tag: Tag): Result<CardInfo> {
        val nfcF = NfcF.get(tag) ?: return Result.failure(Exception("无法获取 NfcF 接口"))
        return try {
            nfcF.connect()
            nfcF.timeout = 2000

            val hint = nfcF.systemCode?.takeIf { it.size == 2 }?.let {
                ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
            }
            val order = if (hint == SYSTEM_OCTOPUS) {
                listOf(FelicaKind.OCTOPUS, FelicaKind.JAPAN)
            } else {
                listOf(FelicaKind.JAPAN, FelicaKind.OCTOPUS)
            }

            // 第一轮：直接用发现卡片时拿到的 IDm
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
                if (idm != null && polled.contentEquals(idm)) continue
                val block = readBlock0(nfcF, polled, kind.serviceCode) ?: continue
                return Result.success(parseBlock(kind, block))
            }

            Result.failure(Exception("FeliCa：不是日本交通卡或香港八达通"))
        } catch (e: IOException) {
            Result.failure(Exception("FeliCa 通信中断，请贴紧卡片: ${e.message}"))
        } finally {
            try { nfcF.close() } catch (_: IOException) {}
        }
    }

    private fun pollIdm(nfcF: NfcF, systemCode: Int): ByteArray? {
        return try {
            val cmd = byteArrayOf(
                0x06, 0x00,
                ((systemCode shr 8) and 0xFF).toByte(), (systemCode and 0xFF).toByte(),
                0x01, 0x00
            )
            val res = nfcF.transceive(cmd)
            if (res.size >= 18 && res[1] == 0x01.toByte()) res.copyOfRange(2, 10) else null
        } catch (e: IOException) {
            null
        }
    }

    /** Read Without Encryption：读某服务的第 0 块（16 字节）；服务代码在报文里是小端序。 */
    private fun readBlock0(nfcF: NfcF, idm: ByteArray, serviceCode: Int): ByteArray? {
        val cmd = ByteArray(16)
        cmd[0] = 16
        cmd[1] = 0x06
        System.arraycopy(idm, 0, cmd, 2, 8)
        cmd[10] = 0x01
        cmd[11] = (serviceCode and 0xFF).toByte()
        cmd[12] = ((serviceCode shr 8) and 0xFF).toByte()
        cmd[13] = 0x01
        cmd[14] = 0x80.toByte()
        cmd[15] = 0x00

        val res = nfcF.transceive(cmd)
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
                // 日本交通卡：块内第 10、11 字节，小端序，单位：日元
                val yen = (block[10].toInt() and 0xFF) or ((block[11].toInt() and 0xFF) shl 8)
                CardInfo(
                    "日本交通卡 (Suica / PASMO / ICOCA 等)",
                    listOf(Balance("卡内余额", yen.toString(), "円"))
                )
            }
            FelicaKind.OCTOPUS -> {
                // 八达通：块内前 4 字节，大端序，单位 0.1 港元，需减去偏移量
                val raw = bigEndian(block, 0, 4)
                val hkd = (raw - OCTOPUS_OFFSET) / 10.0
                CardInfo(
                    "香港八达通 Octopus",
                    listOf(Balance("卡内余额", String.format(Locale.US, "%.1f", hkd), "HK$"))
                )
            }
        }
    }


    private const val TAG = "CardReader"

    /** 诊断开关：true 时，读不出余额的账户会做一轮探测并显示详细信息；false 时只显示读得到的账户。 */
    private const val DEBUG_PROBE = false

    /**
     * 诊断：对当前已选中的应用，尝试 GET DATA / GPO / READ RECORD，把原始返回全部写入 Logcat。
     * 返回一段简短摘要（只列出成功读到的内容），显示在界面上。
     */
    private fun probeApp(isoDep: IsoDep, label: String, selectResp: ByteArray): String {
        val found = mutableListOf<String>()

        fun tx(name: String, cmd: ByteArray): ByteArray? {
            return try {
                val r = isoDep.transceive(cmd)
                Log.d(TAG, "$label | $name -> ${hex(r)}")
                r
            } catch (e: IOException) {
                Log.d(TAG, "$label | $name -> IOException ${e.message}")
                null
            }
        }

        Log.d(TAG, "$label | SELECT 响应: ${hex(selectResp)}")

        // 1) GET DATA：常见的余额、限额、计数器等数据
        for (t in listOf(0x9F79, 0x9F77, 0x9F78, 0x9F5D, 0x9F4F, 0x9F13, 0x9F36, 0x9F17)) {
            val cmd = byteArrayOf(
                0x80.toByte(), 0xCA.toByte(),
                ((t shr 8) and 0xFF).toByte(), (t and 0xFF).toByte(), 0x00
            )
            val r = tx("GETDATA %04X".format(t), cmd)
            if (r != null && isSuccess(r)) found += "%04X=%s".format(t, hex(r.copyOf(r.size - 2)))
        }

        // 2) GPO（简化，PDOL 为空）
        val gpo = tx("GPO", byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0x00, 0x00, 0x02, 0x83.toByte(), 0x00, 0x00))
        if (gpo != null && isSuccess(gpo)) found += "GPO=" + hex(gpo.copyOf(gpo.size - 2))

        // 3) READ RECORD：SFI 1~31，记录 1（成功则继续读到记录 5）
        for (sfi in 1..31) {
            for (rec in 1..5) {
                val cmd = byteArrayOf(0x00, rec.toByte(), ((sfi shl 3) or 4).toByte(), 0x00)
                val r = tx("READREC sfi=$sfi rec=$rec", cmd)
                if (r != null && isSuccess(r)) {
                    found += "S%d.R%d=%s".format(sfi, rec, hex(r.copyOf(r.size - 2)).take(40))
                } else {
                    break
                }
            }
        }

        // 4) READ BINARY：SFI 0x15（常见的卡片信息文件）
        val bin = tx("READBIN sfi=0x15", byteArrayOf(0x00, 0xB0.toByte(), 0x95.toByte(), 0x00, 0x00))
        if (bin != null && isSuccess(bin)) found += "B15=" + hex(bin.copyOf(bin.size - 2)).take(40)

        return if (found.isEmpty()) "无可读数据（详见 Logcat 标签 CardReader）"
        else found.take(8).joinToString(" ") + "（完整内容见 Logcat 标签 CardReader）"
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    // ================= 工具函数 =================
    private fun selectCmd(aid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid

    private fun isSuccess(res: ByteArray): Boolean {
        if (res.size < 2) return false
        return res[res.size - 2] == 0x90.toByte() && res[res.size - 1] == 0x00.toByte()
    }

    /** 状态字（最后两个字节）转成文字，便于排查。 */
    private fun sw(res: ByteArray): String {
        if (res.size < 2) return "无响应"
        return String.format(
            Locale.US, "%02X%02X",
            res[res.size - 2].toInt() and 0xFF, res[res.size - 1].toInt() and 0xFF
        )
    }

    private fun bigEndian(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    /** BCD 转数字；出现非法数位返回 null。 */
    private fun bcd(b: ByteArray, off: Int, len: Int): Long? {
        var v = 0L
        for (i in off until off + len) {
            val hi = (b[i].toInt() shr 4) and 0x0F
            val lo = b[i].toInt() and 0x0F
            if (hi > 9 || lo > 9) return null
            v = v * 100 + hi * 10 + lo
        }
        return v
    }
}