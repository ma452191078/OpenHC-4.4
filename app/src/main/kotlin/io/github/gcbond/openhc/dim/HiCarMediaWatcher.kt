package io.github.gcbond.openhc.dim

import android.util.Log
import org.json.JSONObject

/**
 * 把 HiCar 手机推送的音频元数据（dataType 515）转到仪表盘。
 *
 * 协议：车机发 514 查询能力，手机回 514 能力列表（`ResponseAAData`）；之后音频状态变化时手机推 515
 * `MediaData`（歌名/歌手/专辑/时长/播放状态），并每秒推一条 515 `ElapsedTime` 作为进度。
 * 515 的确认由协议层自动回，本类只负责取字段和推送。
 */
class HiCarMediaWatcher(private val dim: DimMediaClient) {

    private var title = ""
    private var artist = ""
    private var album = ""
    private var durationMs = 0L
    private var positionMs = 0L
    private var playing = false

    /** 手机回报的元数据能力（514 应答）。 */
    fun onCapability(json: String) {
        val text = json.trimEnd('\u0000')
        val ability = runCatching { JSONObject(text).optJSONObject("ResponseAAData") }.getOrNull() ?: return
        Log.i(
            TAG,
            "HiCar 元数据能力：媒体=${ability.optBoolean("MEDIA")}，通话=${ability.optBoolean("CALL")}，" +
                "导航=${ability.optBoolean("NAVIGATION")}，原文=${text.take(300)}",
        )
    }

    /** 一条 515 正文：完整曲目信息 `MediaData` 或每秒一次的进度 `ElapsedTime`（单位都是秒）。 */
    fun onMetadata(json: String) {
        val text = json.trimEnd('\u0000')
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val media = obj.optJSONObject("MediaData")
        if (media != null) {
            media.optString("Name").trim().takeIf { it.isNotEmpty() }?.let { title = it }
            artist = media.optString("Artist").trim()
            album = media.optString("AlbumName").trim()
            media.optInt("TotalTime", 0).coerceAtLeast(0).let { durationMs = it * 1000L }
            media.optInt("ElapsedTime", 0).coerceAtLeast(0).let { positionMs = it * 1000L }
            if (media.has("Status")) {
                // android.media.session.PlaybackState：3 播放、4/5 快进快退、6 缓冲，其余按暂停/停止。
                playing = media.optInt("Status") in 3..5 || media.optInt("Status") == 6
            }
            Log.i(TAG, "HiCar 曲目：$title - $artist（$album），时长 ${durationMs}ms，播放=$playing")
            push()
            return
        }
        val elapsed = obj.optInt("ElapsedTime", -1)
        if (elapsed >= 0) {
            positionMs = elapsed * 1000L
            if (title.isNotBlank()) dim.updateProgress(positionMs)
        }
    }

    /** 一条 517 正文：`type` = create/update/remove，`navData` 里是转向、道路、剩余距离等。 */
    fun onNavigation(json: String) {
        val text = json.trimEnd('\u0000')
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val nav = obj.optJSONObject("navData")
        if (nav == null) {
            Log.i(TAG, "HiCar 导航状态：${obj.optString("type")}（原文=${text.take(300)}）")
            return
        }
        Log.i(
            TAG,
            "HiCar 导航：${obj.optString("type")}｜当前道路=${nav.optString("CurrentRoadName")}｜" +
                "下一步=${nav.optString("RoadName")} ${nav.optInt("Distance", -1)}m｜" +
                "转向=${nav.optString("NaviTurnMode")}｜剩余=${nav.optInt("ToDistance", -1)}m/" +
                "${nav.optInt("RemainingTime", -1)}分｜终点=${nav.optString("DestLocation")}",
        )
    }

    private fun push() {
        if (title.isBlank()) return
        dim.update(
            DimMediaInfo(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                positionMs = positionMs,
                playing = playing,
                coverPath = null,
                // HiCar 走手机在线音源：类型 6（仪表上显示「在线」）。
                mediaType = DimMediaInfo.MEDIA_TYPE_ONLINE,
            ),
        )
    }

    private companion object {
        const val TAG = "OpenHC-HiCarMedia"
    }
}
