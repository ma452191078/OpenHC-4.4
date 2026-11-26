package io.github.gcbond.openhc.wireless

// 设置「补全手机蓝牙配对」的规则：车机蓝牙名称怎么临时改、怎么改回去，配对进行到哪一步。本文件是纯逻辑，不引用安卓类，
// 由 JVM 单元测试覆盖；接线见 LePairingSession（安卓的蓝牙名称、配对状态广播）和 platform 的 LePairingPeripheral。
//
// 为什么要补：车机蓝牙开了 LE 隐私时，手机靠 BLE 广播发现车机后拿到的是随机地址，只做过经典蓝牙配对的手机解不回车机的
// 身份地址，免扫码回连必然失败（扫码不受影响：二维码里直接带车机的蓝牙地址）。让手机在自己的蓝牙设置里和车机补一次
// LE 配对、拿到车机的身份解析密钥，手机就能认出车机。补上以后免扫码能不能通，仍要看具体的车机和手机。
//
// 流程：车机蓝牙名称临时改成 [LePairingRules.PAIRING_NAME]（手机蓝牙列表里认得出来），挂一个读要加密的 GATT 特征、
// 发带名称的可连接广播；车主在手机蓝牙设置里点它配对。结束（完成、取消、离开太久）时把名称改回去。原名称改名前先落盘，
// 进程中途没了，下次启动、或蓝牙重新打开时再改回去。

/** 车机蓝牙名称的处理规则。 */
object LePairingRules {
    /** 临时名称：手机蓝牙列表里认这个。ASCII，11 字节，放得进传统广播（见 [fitsLegacyAdvertising]）。 */
    const val PAIRING_NAME = "OpenHC-Pair"

    /** 传统广播里设备名最多放得下的字节数（UTF-8）：31 字节减去 Flags 3、16 位服务 UUID 4、名称字段头 2。 */
    const val MAX_NAME_BYTES = 22

    fun fitsLegacyAdvertising(name: String): Boolean = name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES

    /**
     * 要的运行时权限：安卓 12（31）起改名、读名称、开 GATT 服务端、收配对状态广播要「附近的设备」里的 BLUETOOTH_CONNECT，
     * 发广播要 BLUETOOTH_ADVERTISE；更早用清单里的普通蓝牙权限，不用申请。
     */
    fun requiredPermissions(sdkInt: Int): List<String> = if (sdkInt >= ANDROID_12) {
        listOf(WirelessPermissions.BLUETOOTH_CONNECT, WirelessPermissions.BLUETOOTH_ADVERTISE)
    } else {
        emptyList()
    }

    private const val ANDROID_12 = 31

    /** 开始前对车机蓝牙名称怎么办。 */
    sealed class NamePlan {
        /** 记下 [original]，改成临时名称。 */
        data class Rename(val original: String) : NamePlan()

        /** 名称已经是临时名称（上次没改回去），原名称记着：不用再改，结束时改回 [original]。 */
        data class Reuse(val original: String) : NamePlan()

        /** 读不到现在的名称，或名称已经是临时名称却不知道原来叫什么：不改名，也不开入口（以后没法改回去）。 */
        object Unknown : NamePlan() {
            override fun toString() = "Unknown"
        }
    }

    /**
     * [current] 是车机现在的蓝牙名称（读不到为 null），[saved] 是之前记下、还没改回去的原名称。
     * 先尝试按 [restorePlan] 改回去，再来问这里。
     */
    fun planName(current: String?, saved: String?): NamePlan = when {
        current.isNullOrEmpty() -> NamePlan.Unknown
        current == PAIRING_NAME -> if (saved.isNullOrEmpty() || saved == PAIRING_NAME) NamePlan.Unknown else NamePlan.Reuse(saved)
        // 记着的原名称和现在的不同、现在的又不是临时名称：那条记录已经过时（名称早改回去了，或车主自己改过），以现在的为准。
        else -> NamePlan.Rename(current)
    }

    /** 记着的原名称要不要改回去。 */
    sealed class RestorePlan {
        /** 没有要改回去的。 */
        object Nothing : RestorePlan() {
            override fun toString() = "Nothing"
        }

        /** 不用改了，删掉记录：名称已经是原来的，或者车主自己改成了别的（不覆盖车主的改动）。 */
        object Forget : RestorePlan() {
            override fun toString() = "Forget"
        }

        /** 改回 [name]。 */
        data class Rename(val name: String) : RestorePlan()

        /** 现在改不了（蓝牙关着、读不到名称），以后再说。 */
        object Later : RestorePlan() {
            override fun toString() = "Later"
        }
    }

    /** [saved] 是记着的原名称，[current] 是车机现在的蓝牙名称（读不到为 null）。 */
    fun restorePlan(saved: String?, current: String?, bluetoothOn: Boolean): RestorePlan = when {
        saved.isNullOrEmpty() -> RestorePlan.Nothing
        // 原名称记成了临时名称：改回去没有意义，也找不到真正的原名称。
        saved == PAIRING_NAME -> RestorePlan.Forget
        !bluetoothOn || current == null -> RestorePlan.Later
        current == PAIRING_NAME -> RestorePlan.Rename(saved)
        else -> RestorePlan.Forget
    }
}

/** 系统的配对状态（与 BluetoothDevice.BOND_NONE / BOND_BONDING / BOND_BONDED 对应）。 */
enum class BondState {
    NONE,
    BONDING,
    BONDED,
    ;

    companion object {
        /** 系统广播里的取值：10 没配对、11 正在配对、12 已配对；别的值返回 null。 */
        fun of(value: Int): BondState? = when (value) {
            10 -> NONE
            11 -> BONDING
            12 -> BONDED
            else -> null
        }
    }
}

/** 补配对进行到哪一步。 */
enum class LePairingStage {
    /** 正在改名、开服务和广播。 */
    PREPARING,

    /** 入口开好了，等车主在手机上点它配对。 */
    ADVERTISING,

    /** 有设备正在和车机配对（车机或手机上会弹配对确认）。 */
    BONDING,

    /** 配对完成：系统报了已配对，或者手机在加密链路上读到了特征。之后不再变。 */
    PAIRED,

    /** 这一次配对没完成（取消、超时）。入口还开着，可以在手机上再点一次。 */
    BOND_FAILED,

    /** 开不了入口，或中途出错（原因见 [LePairingStatus.failure]）。之后不再变。 */
    FAILED,

    /** 离开本应用太久，入口已经自动关了、名称已改回。之后不再变。 */
    EXPIRED,
}

/** 开不了入口、或中途出错的原因。 */
enum class LePairingFailure {
    NO_BLUETOOTH,
    BLUETOOTH_OFF,

    /** 安卓 12 起缺「附近的设备」权限。 */
    PERMISSIONS,

    /** 车机原来的蓝牙名称不知道（见 [LePairingRules.NamePlan.Unknown]）。 */
    NAME_UNKNOWN,

    /** 原来的名称没能落盘：不改名。 */
    NAME_NOT_SAVED,

    /** 系统不让改名。 */
    RENAME_REFUSED,

    /** GATT 服务开不了。 */
    GATT,

    /** 车机不支持 BLE 广播。 */
    ADVERTISING_UNSUPPORTED,

    /** 广播实例用完了（本应用等手机回连时也在发广播）。 */
    ADVERTISING_BUSY,

    /** 广播开不了（别的原因）。 */
    ADVERTISING,
}

/** 补配对的界面状态。[detail] 是出错时给用户看的补充说明（系统给的原因）。 */
data class LePairingStatus(
    val stage: LePairingStage = LePairingStage.PREPARING,
    val failure: LePairingFailure? = null,
    val detail: String? = null,
) {
    /** 不会再变了。 */
    val finished: Boolean
        get() = stage == LePairingStage.PAIRED || stage == LePairingStage.FAILED || stage == LePairingStage.EXPIRED
}

/** 状态归并的输入。 */
sealed class LePairingSignal {
    data class Failed(val failure: LePairingFailure, val detail: String? = null) : LePairingSignal()

    /** 服务、广播都开好了。 */
    object Advertising : LePairingSignal()

    /** 系统报某台设备的配对状态从 [previous] 变成了 [current]（[previous] 读不到为 null）。 */
    data class Bond(val previous: BondState?, val current: BondState) : LePairingSignal()

    /** 有手机在加密链路上读了特征。 */
    object EncryptedRead : LePairingSignal()

    /** 离开本应用太久，入口自动关了。 */
    object Expired : LePairingSignal()
}

/**
 * 状态归并：纯函数。
 *
 * - 配对状态广播不一定会来：手机和车机早有经典蓝牙配对时，系统可能只把 LE 的密钥补进同一条配对记录、不再报一遍；
 *   所以「手机在加密链路上读到了特征」同样算完成。单凭手机经 LE 连上不算。
 * - 已配对后再来的「没配对」（比如别的设备被删）不改结果；正在配对时变回「没配对」才算这一次没配成。
 */
object LePairingReducer {
    fun reduce(status: LePairingStatus, signal: LePairingSignal): LePairingStatus {
        if (status.finished) return status
        return when (signal) {
            is LePairingSignal.Failed -> LePairingStatus(LePairingStage.FAILED, signal.failure, signal.detail)
            LePairingSignal.Advertising ->
                if (status.stage == LePairingStage.PREPARING) status.copy(stage = LePairingStage.ADVERTISING) else status
            is LePairingSignal.Bond -> onBond(status, signal)
            LePairingSignal.EncryptedRead -> status.copy(stage = LePairingStage.PAIRED)
            LePairingSignal.Expired -> status.copy(stage = LePairingStage.EXPIRED)
        }
    }

    private fun onBond(status: LePairingStatus, signal: LePairingSignal.Bond): LePairingStatus = when (signal.current) {
        BondState.BONDING -> status.copy(stage = LePairingStage.BONDING)
        BondState.BONDED -> status.copy(stage = LePairingStage.PAIRED)
        BondState.NONE ->
            if (status.stage == LePairingStage.BONDING && signal.previous == BondState.BONDING) {
                status.copy(stage = LePairingStage.BOND_FAILED)
            } else {
                status
            }
    }
}
