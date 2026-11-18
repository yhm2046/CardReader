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

    private const val TAG = "CardReader"

    // ================= 1. 中国交通联合 / 深圳通（IsoDep） =================
    private data class IsoAppSpec(
        val label: String,
        val cardTitle: String,
        val symbol: String,
        val preSelectCmds: List<ByteArray> = emptyList(),
        val selectCmd: ByteArray
    )

    private fun selectByName(aid: ByteArray): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid

    private fun selectByFileId(fidHi: Int, fidLo: Int): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x00, 0x02, fidHi.toByte(), fidLo.toByte())

    private val PSE_1PAY = "1PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)
    private val AID_T_UNION_EP = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x06, 0x32, 0x01, 0x01, 0x05)
    private val AID_T_UNION_CASH = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x06, 0x32, 0x01, 0x01, 0x06)
    private val AID_SZT = "PAY.SZT".toByteArray(Charsets.US_ASCII)

    /**
     * 只保留真实存在的深圳通与交通联合应用，避免发送过多无效 APDU 拖慢切换到 FeliCa 的速度
     */
    private val ISO_APP_SPECS = listOf(
        IsoAppSpec(
            label = "深圳通 / 交通联合 (电子钱包)",
            cardTitle = "深圳通",
            symbol = "¥",
            selectCmd = selectByName(AID_T_UNION_EP)
        ),
        IsoAppSpec(
            label = "深圳通 (人民币钱包)",
            cardTitle = "深圳通",
            symbol = "¥",
            selectCmd = selectByName(AID_SZT)
        ),
        IsoAppSpec(
            label = "深圳通 (传统钱包)",
            cardTitle = "深圳通",
            symbol = "¥",
            preSelectCmds = listOf(selectByName(PSE_1PAY)),
            selectCmd = selectByFileId(0x10, 0x01)
        ),
        IsoAppSpec(
            label = "电子现金 (MOT_T_CASH)",
            cardTitle = "交通联合",
            symbol = "¥",
            selectCmd = selectByName(AID_T_UNION_CASH)
        )
    )

    private val CMD_BALANCE_EP = byteArrayOf(0x80.toByte(), 0x5C, 0x00, 0x02, 0x04)
    private val CMD_BALANCE_ED = byteArrayOf(0x80.toByte(), 0x5C, 0x00, 0x01, 0x04)
    private val CMD_BALANCE_CASH = byteArrayOf(0x80.toByte(), 0xCA.toByte(), 0x9F.toByte(), 0x79, 0x00)

    // ================= 2. FeliCa（NfcF：香港八达通 / 日本交通卡） =================
    private const val SYSTEM_OCTOPUS = 0x8008           // 香港八达通 System Code
    private const val SYSTEM_JAPAN_IC = 0x0003          // 日本交通卡 System Code
    private const val SYSTEM_WILDCARD = 0xFFFF          // 通配符唤醒
    private const val SERVICE_OCTOPUS_BALANCE = 0x0117  // 八达通余额服务 (只读无加密)
    private const val SERVICE_JAPAN_HISTORY = 0x090F    // 日本交通卡历史与余额服务
    private const val OCTOPUS_OFFSET = 500              // 八达通标准偏移量 (50.0 HKD, 2017年10月后及换卡后通用；老版互通行若未升级可能为 350)

    private enum class FelicaKind(val systemCode: Int, val serviceCode: Int) {
        OCTOPUS(SYSTEM_OCTOPUS, SERVICE_OCTOPUS_BALANCE),
        JAPAN(SYSTEM_JAPAN_IC, SERVICE_JAPAN_HISTORY)
    }

    fun readCard(tag: Tag): Result<CardInfo> {
        val techList = tag.techList.toList()
        Log.d(TAG, ">>> 捕获 Tag: ID=${hex(tag.id ?: byteArrayOf())}, TechList=$techList")

        val hasFelica = techList.contains(NfcF::class.java.name)
        val hasIsoDep = techList.contains(IsoDep::class.java.name)

        val titles = linkedSetOf<String>()
        val balances = mutableListOf<Balance>()
        val errors = mutableListOf<String>()

        if (hasFelica) {
            readFelicaCard(tag).fold(
                onSuccess = {
                    titles += it.title
                    balances += it.balances
                },
                onFailure = { errors += (it.message ?: "FeliCa 读取失败") }
            )
        }

        if (hasIsoDep) {
            readIsoDepCard(tag).fold(
                onSuccess = {
                    titles += it.title
                    balances += it.balances
                },
                onFailure = { errors += (it.message ?: "IsoDep 读取失败") }
            )
        }

        if (balances.any { it.amount != null }) {
            return Result.success(CardInfo(titles.joinToString(" + "), balances))
        }
        val msg = if (errors.isEmpty()) "未能识别的卡片类型（非 IsoDep / NfcF）" else errors.joinToString("\n")
        return Result.failure(Exception(msg))
    }

    // --- 读取深圳通 / 交通联合 (IsoDep) ---
    private fun readIsoDepCard(tag: Tag): Result<CardInfo> {
        val isoDep = IsoDep.get(tag) ?: return Result.failure(Exception("无法获取 IsoDep 接口"))
        return try {
            isoDep.connect()
            isoDep.timeout = 1500 // 缩短超时，确保快速释放射频给第二阶段 FeliCa

            val titles = linkedSetOf<String>()
            val foundBalances = mutableListOf<Balance>()
            val debugNotes = mutableListOf<String>()
            var mainWalletRead = false

            for (spec in ISO_APP_SPECS) {
                // 如果主人民币钱包已读出，跳过其他同质深圳通入口，只额外看是否有电子现金
                if (mainWalletRead && spec.label != "电子现金 (MOT_T_CASH)") {
                    continue
                }

                for (preCmd in spec.preSelectCmds) {
                    isoDep.transceive(preCmd)
                }

                val sel = isoDep.transceive(spec.selectCmd)
                if (!isSuccess(sel)) {
                    debugNotes += "${spec.label}: 未启用 (SW=${sw(sel)})"
                    continue
                }

                val (cents, debug) = readBalanceCents(isoDep)
                if (cents != null) {
                    val formatted = String.format(Locale.US, "%.2f", cents / 100.0)
                    foundBalances += Balance(spec.label, formatted, spec.symbol)
                    titles += spec.cardTitle
                    if (spec.label != "电子现金 (MOT_T_CASH)") {
                        mainWalletRead = true
                    }
                } else {
                    debugNotes += "${spec.label}: 余额读取失败 $debug"
                }
            }

            if (foundBalances.isNotEmpty()) {
                Result.success(CardInfo(titles.joinToString(" + "), foundBalances))
            } else {
                Result.failure(Exception(debugNotes.joinToString("\n")))
            }
        } catch (e: IOException) {
            Result.failure(Exception("IsoDep 读卡中断: ${e.message}"))
        } finally {
            try { isoDep.close() } catch (_: IOException) {}
        }
    }

    private fun readBalanceCents(isoDep: IsoDep): Pair<Long?, String> {
        val debug = StringBuilder()

        val r1 = isoDep.transceive(CMD_BALANCE_EP)
        if (isSuccess(r1) && r1.size >= 6) return Pair(bigEndianSigned(r1, 0), "")
        debug.append("[805C0002 SW=${sw(r1)}]")

        val r2 = isoDep.transceive(CMD_BALANCE_CASH)
        if (isSuccess(r2) && r2.size >= 11 &&
            r2[0] == 0x9F.toByte() && r2[1] == 0x79.toByte() && r2[2] == 0x06.toByte()
        ) {
            val v = bcd(r2, 3, 6)
            if (v != null) return Pair(v, "")
        }
        debug.append("[CA9F79 SW=${sw(r2)}]")

        val r3 = isoDep.transceive(CMD_BALANCE_ED)
        if (isSuccess(r3) && r3.size >= 6) return Pair(bigEndianSigned(r3, 0), "")
        debug.append("[805C0001 SW=${sw(r3)}]")

        return Pair(null, debug.toString())
    }

    // --- 读取 FeliCa（香港八达通 / 日本交通卡） ---
    private fun readFelicaCard(tag: Tag): Result<CardInfo> {
        val nfcF = NfcF.get(tag) ?: return Result.failure(Exception("无法获取 NfcF 接口"))
        return try {
            nfcF.connect()
            nfcF.timeout = 2000

            val titles = linkedSetOf<String>()
            val balances = mutableListOf<Balance>()
            val defaultIdm = tag.id

            Log.d(TAG, "FeliCa 连接成功: tag.id=${hex(defaultIdm ?: byteArrayOf())}, systemCode=${hex(nfcF.systemCode ?: byteArrayOf())}")

            for (kind in FelicaKind.entries) {
                var block: ByteArray? = null

                // 1) 优先使用目标卡种的专属 System Code (如八达通 0x8008) 发起 SENSF_REQ Polling 获取真实 IDm
                val polledIdm = pollIdm(nfcF, kind.systemCode)
                if (polledIdm != null) {
                    Log.d(TAG, "FeliCa Polling(${ "%04X".format(kind.systemCode) }) 成功, IDm=${hex(polledIdm)}")
                    block = readBlock0(nfcF, polledIdm, kind.serviceCode)
                }

                // 2) 如果专属 System Code 未响应，尝试用通配符 0xFFFF 唤醒
                if (block == null) {
                    val wildcardIdm = pollIdm(nfcF, SYSTEM_WILDCARD)
                    if (wildcardIdm != null) {
                        block = readBlock0(nfcF, wildcardIdm, kind.serviceCode)
                    }
                }

                // 3) 最后回退使用 tag.id 直接读取
                if (block == null && defaultIdm != null && defaultIdm.size == 8) {
                    block = readBlock0(nfcF, defaultIdm, kind.serviceCode)
                }

                if (block != null) {
                    val info = parseBlock(kind, block)
                    titles += info.title
                    balances += info.balances
                }
            }

            if (balances.isNotEmpty()) {
                Result.success(CardInfo(titles.joinToString(" + "), balances))
            } else {
                Result.failure(Exception("FeliCa 芯片已响应，但未找到八达通(0x0117)或日本交通卡数据"))
            }
        } catch (e: IOException) {
            Result.failure(Exception("FeliCa 通信中断: ${e.message}"))
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
            if (res != null && res.size >= 18 && res[1] == 0x01.toByte()) {
                res.copyOfRange(2, 10)
            } else {
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun readBlock0(nfcF: NfcF, idm: ByteArray, serviceCode: Int): ByteArray? {
        return try {
            val cmd = ByteArray(16)
            cmd[0] = 16
            cmd[1] = 0x06 // Read Without Encryption
            System.arraycopy(idm, 0, cmd, 2, 8)
            cmd[10] = 0x01 // 服务数量 = 1
            cmd[11] = (serviceCode and 0xFF).toByte()        // Little-endian 低字节 (0x17)
            cmd[12] = ((serviceCode shr 8) and 0xFF).toByte() // Little-endian 高字节 (0x01)
            cmd[13] = 0x01 // 块数量 = 1
            cmd[14] = 0x80.toByte() // 2字节块描述符标志
            cmd[15] = 0x00 // 第 0 块

            val res = nfcF.transceive(cmd)
            Log.d(TAG, "FeliCa ReadBlock0(service=${ "%04X".format(serviceCode) }) -> ${hex(res ?: byteArrayOf())}")
            if (res != null && res.size >= 29 && res[1] == 0x07.toByte() &&
                res[10] == 0.toByte() && res[11] == 0.toByte()
            ) {
                res.copyOfRange(13, 29)
            } else {
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun parseBlock(kind: FelicaKind, block: ByteArray): CardInfo {
        return when (kind) {
            FelicaKind.JAPAN -> {
                val yen = (block[10].toInt() and 0xFF) or ((block[11].toInt() and 0xFF) shl 8)
                CardInfo(
                    "日本交通卡 (Suica / PASMO / ICOCA)",
                    listOf(Balance("日本交通卡余额", yen.toString(), "円"))
                )
            }
            FelicaKind.OCTOPUS -> {
                // 八达通前4字节大端序，单位0.1港元
                // 注：2017年10月前发行的老版互通行如果从未在八达通机器上做过押金偏移升级，偏移量可能是 350 (HK$35)；新版统一为 500 (HK$50)
                val raw = bigEndianSigned(block, 0)
                val hkd500 = (raw - OCTOPUS_OFFSET) / 10.0
                val hkd350 = (raw - 350) / 10.0
                val note = "如为2017年前未升级老卡，余额可能为 HK$ ${String.format(Locale.US, "%.1f", hkd350)}"
                CardInfo(
                    "香港八达通 Octopus",
                    listOf(Balance("八达通余额 (港币)", String.format(Locale.US, "%.1f", hkd500), "HK$", note))
                )
            }
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun isSuccess(res: ByteArray): Boolean {
        if (res.size < 2) return false
        return res[res.size - 2] == 0x90.toByte() && res[res.size - 1] == 0x00.toByte()
    }

    private fun sw(res: ByteArray): String {
        if (res.size < 2) return "无响应"
        return String.format(
            Locale.US, "%02X%02X",
            res[res.size - 2].toInt() and 0xFF, res[res.size - 1].toInt() and 0xFF
        )
    }

    private fun bigEndianSigned(b: ByteArray, off: Int): Long {
        return ((b[off].toInt() shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)).toLong()
    }

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