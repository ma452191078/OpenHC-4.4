package io.github.gcbond.openhc.wireless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 补全手机蓝牙配对：车机蓝牙名称的临时改名与恢复、配对进行到哪一步。 */
class LePairingTest {

    private val name = LePairingRules.PAIRING_NAME

    @Test
    fun temporaryNameFitsALegacyAdvertisement() {
        assertTrue(LePairingRules.fitsLegacyAdvertising(name))
        assertTrue(LePairingRules.fitsLegacyAdvertising("A".repeat(LePairingRules.MAX_NAME_BYTES)))
        assertFalse(LePairingRules.fitsLegacyAdvertising("A".repeat(LePairingRules.MAX_NAME_BYTES + 1)))
        assertFalse(LePairingRules.fitsLegacyAdvertising("车".repeat(8)), "按 UTF-8 字节算：一个汉字 3 字节")
    }

    @Test
    fun renamesOnlyWhenTheOriginalNameIsKnown() {
        assertEquals(LePairingRules.NamePlan.Rename("Car-0001"), LePairingRules.planName("Car-0001", saved = null))
        assertEquals(LePairingRules.NamePlan.Unknown, LePairingRules.planName(null, saved = null), "读不到名称")
        assertEquals(LePairingRules.NamePlan.Unknown, LePairingRules.planName("", saved = null))
        assertEquals(
            LePairingRules.NamePlan.Unknown,
            LePairingRules.planName(name, saved = null),
            "已经是临时名称、又不知道原来叫什么：改了以后没法改回去",
        )
    }

    @Test
    fun aLeftoverTemporaryNameReusesTheSavedOriginal() {
        assertEquals(LePairingRules.NamePlan.Reuse("Car-0001"), LePairingRules.planName(name, saved = "Car-0001"))
        assertEquals(LePairingRules.NamePlan.Unknown, LePairingRules.planName(name, saved = name), "记成了临时名称的不算数")
        assertEquals(
            LePairingRules.NamePlan.Rename("Car-0002"),
            LePairingRules.planName("Car-0002", saved = "Car-0001"),
            "记录过时了（名称早改回去、或车主改过）：以现在的为准",
        )
    }

    @Test
    fun restoreOnlyUndoesOurOwnRename() {
        assertEquals(LePairingRules.RestorePlan.Nothing, LePairingRules.restorePlan(null, name, bluetoothOn = true))
        assertEquals(LePairingRules.RestorePlan.Rename("Car-0001"), LePairingRules.restorePlan("Car-0001", name, bluetoothOn = true))
        assertEquals(LePairingRules.RestorePlan.Forget, LePairingRules.restorePlan("Car-0001", "Car-0001", bluetoothOn = true), "已经改回去了")
        assertEquals(
            LePairingRules.RestorePlan.Forget,
            LePairingRules.restorePlan("Car-0001", "MyCar", bluetoothOn = true),
            "车主自己改成了别的：不覆盖",
        )
        assertEquals(LePairingRules.RestorePlan.Forget, LePairingRules.restorePlan(name, name, bluetoothOn = true))
    }

    @Test
    fun restoreWaitsWhenTheNameCannotBeChangedNow() {
        assertEquals(LePairingRules.RestorePlan.Later, LePairingRules.restorePlan("Car-0001", name, bluetoothOn = false))
        assertEquals(LePairingRules.RestorePlan.Later, LePairingRules.restorePlan("Car-0001", null, bluetoothOn = true), "读不到名称")
    }

    @Test
    fun runtimePermissionsFromAndroidTwelve() {
        for (sdk in listOf(23, 26, 29, 30)) assertTrue(LePairingRules.requiredPermissions(sdk).isEmpty(), "安卓 API $sdk")
        for (sdk in listOf(31, 33, 36)) {
            assertEquals(
                listOf(WirelessPermissions.BLUETOOTH_CONNECT, WirelessPermissions.BLUETOOTH_ADVERTISE),
                LePairingRules.requiredPermissions(sdk),
                "安卓 API $sdk",
            )
        }
    }

    @Test
    fun bondStatesFollowTheSystemValues() {
        assertEquals(BondState.NONE, BondState.of(10))
        assertEquals(BondState.BONDING, BondState.of(11))
        assertEquals(BondState.BONDED, BondState.of(12))
        assertNull(BondState.of(Int.MIN_VALUE))
        assertNull(BondState.of(0))
    }

    @Test
    fun normalPairingFlow() {
        var s = LePairingStatus()
        assertEquals(LePairingStage.PREPARING, s.stage)
        s = reduce(s, LePairingSignal.Advertising)
        assertEquals(LePairingStage.ADVERTISING, s.stage)
        s = reduce(s, LePairingSignal.Bond(BondState.NONE, BondState.BONDING))
        assertEquals(LePairingStage.BONDING, s.stage)
        s = reduce(s, LePairingSignal.Bond(BondState.BONDING, BondState.BONDED))
        assertEquals(LePairingStage.PAIRED, s.stage)
        assertTrue(s.finished)
    }

    @Test
    fun anEncryptedReadAlsoMeansPaired() {
        // 手机早有经典蓝牙配对时，系统可能不再报配对状态：读到了要加密的特征同样算配好了。
        val s = reduce(reduce(LePairingStatus(), LePairingSignal.Advertising), LePairingSignal.EncryptedRead)
        assertEquals(LePairingStage.PAIRED, s.stage)
    }

    @Test
    fun aCancelledBondCanBeRetried() {
        var s = reduce(LePairingStatus(), LePairingSignal.Advertising)
        s = reduce(s, LePairingSignal.Bond(BondState.NONE, BondState.BONDING))
        s = reduce(s, LePairingSignal.Bond(BondState.BONDING, BondState.NONE))
        assertEquals(LePairingStage.BOND_FAILED, s.stage)
        assertFalse(s.finished, "入口还开着，手机上可以再点一次")
        s = reduce(s, LePairingSignal.Bond(BondState.NONE, BondState.BONDING))
        assertEquals(LePairingStage.BONDING, s.stage)
        s = reduce(s, LePairingSignal.Bond(BondState.BONDING, BondState.BONDED))
        assertEquals(LePairingStage.PAIRED, s.stage)
    }

    @Test
    fun unrelatedUnbondsAreIgnored() {
        val advertising = reduce(LePairingStatus(), LePairingSignal.Advertising)
        assertEquals(advertising, reduce(advertising, LePairingSignal.Bond(BondState.BONDED, BondState.NONE)), "别的设备被删了配对")
        assertEquals(advertising, reduce(advertising, LePairingSignal.Bond(null, BondState.NONE)))
    }

    @Test
    fun pairedIsFinal() {
        val paired = reduce(reduce(LePairingStatus(), LePairingSignal.Advertising), LePairingSignal.EncryptedRead)
        for (signal in listOf(
            LePairingSignal.Bond(BondState.BONDING, BondState.NONE),
            LePairingSignal.Failed(LePairingFailure.BLUETOOTH_OFF),
            LePairingSignal.Expired,
            LePairingSignal.Advertising,
        )) {
            assertEquals(paired, reduce(paired, signal), "$signal")
        }
    }

    @Test
    fun failuresAreFinalAndKeepTheReason() {
        val failed = reduce(LePairingStatus(), LePairingSignal.Failed(LePairingFailure.ADVERTISING_BUSY, "广播实例用完了"))
        assertEquals(LePairingStage.FAILED, failed.stage)
        assertEquals(LePairingFailure.ADVERTISING_BUSY, failed.failure)
        assertEquals("广播实例用完了", failed.detail)
        assertTrue(failed.finished)
        for (signal in listOf(LePairingSignal.Advertising, LePairingSignal.EncryptedRead, LePairingSignal.Bond(BondState.BONDING, BondState.BONDED))) {
            assertEquals(failed, reduce(failed, signal), "$signal")
        }
    }

    @Test
    fun leavingTooLongClosesTheEntrance() {
        val bonding = reduce(reduce(LePairingStatus(), LePairingSignal.Advertising), LePairingSignal.Bond(BondState.NONE, BondState.BONDING))
        val expired = reduce(bonding, LePairingSignal.Expired)
        assertEquals(LePairingStage.EXPIRED, expired.stage)
        assertTrue(expired.finished)
        assertEquals(expired, reduce(expired, LePairingSignal.Bond(BondState.BONDING, BondState.BONDED)))
    }

    @Test
    fun advertisingOnlyMovesOnFromPreparing() {
        val bonding = reduce(reduce(LePairingStatus(), LePairingSignal.Advertising), LePairingSignal.Bond(BondState.NONE, BondState.BONDING))
        assertEquals(bonding, reduce(bonding, LePairingSignal.Advertising))
        // 配对状态比广播回调先到（手机已经在列表里点了）也照收。
        assertEquals(LePairingStage.BONDING, reduce(LePairingStatus(), LePairingSignal.Bond(BondState.NONE, BondState.BONDING)).stage)
    }

    private fun reduce(status: LePairingStatus, signal: LePairingSignal) = LePairingReducer.reduce(status, signal)
}
