package io.github.gcbond.openhc

import android.content.Context
import io.github.gcbond.openhc.core.common.identity.IdentityAssets
import io.github.gcbond.openhc.identity.IdentityJson
import io.github.gcbond.openhc.identity.IdentityResolution
import io.github.gcbond.openhc.identity.StoredText
import io.github.gcbond.openhc.settings.BrandPreferences
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 读车机的连接参数：安装包里的 `openhc-identity.json`（不进仓库，构建时打进安装包，见 app/build.gradle.kts）。
 * 缺少或不能用（例如有人把文件改坏了）时报出来，见 [IdentityResolution]。
 */
object IdentityLoader {

    sealed interface Result {
        class Loaded(val assets: IdentityAssets) : Result
        object Missing : Result

        /** [reason] 不含密钥内容。 */
        class Invalid(val reason: String) : Result
    }

    fun load(context: Context): Result = applyBrandName(context, IdentityResolution.resolve(readBundled(context)))

    /**
     * 「设置 → 车辆品牌」选了车标且该车标带名称时，覆盖身份里的 `deviceName`（手机端显示的车机名称）。
     * 只改显示名，不动 `brand` 和车型编号；「默认」或未选时原样返回。
     */
    private fun applyBrandName(context: Context, result: Result): Result {
        val loaded = result as? Result.Loaded ?: return result
        val deviceName = BrandPreferences(context.applicationContext).brand?.deviceName ?: return result
        val assets = loaded.assets
        if (assets.host.deviceName == deviceName) return result
        return Result.Loaded(
            IdentityAssets(
                host = assets.host.copy(deviceName = deviceName),
                nearbyKeys = assets.nearbyKeys,
                authService = assets.authService,
                authEnvelopeVersion = assets.authEnvelopeVersion,
            ),
        )
    }

    private fun readBundled(context: Context): StoredText = try {
        context.assets.open(IdentityAssets.ASSET_FILE_NAME).use { StoredText.Present(IdentityJson.decodeUtf8(IdentityJson.readLimited(it))) }
    } catch (e: FileNotFoundException) {
        StoredText.Absent
    } catch (e: IOException) {
        StoredText.Unreadable(e.message ?: e.javaClass.simpleName)
    }
}
