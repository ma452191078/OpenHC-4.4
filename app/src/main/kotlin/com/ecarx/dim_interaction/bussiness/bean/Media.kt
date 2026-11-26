package com.ecarx.dim_interaction.bussiness.bean

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

/**
 * 仪表盘（DIM）媒体信息包。
 *
 * 字段顺序和类型必须与车机 DiServer 里的同名类完全一致（序列化是纯顺序读写，错一位就全错）。
 * 这里的取值约定见 [io.github.gcbond.openhc.dim.DimMediaClient]：媒体类型用 0–8 原始值，
 * 封面用 file:// Uri，服务端自己读文件并缩放到 70×70。
 */
class Media : Parcelable {
    /** 媒体类型：0 本地 / 1 USB / 2 蓝牙 / 3 FM / 4 AM / 5 AUX / 6 在线 / 7 USB2 / 8 播客。 */
    var baseMediaType = 0
    /** 0 暂停，1 播放。 */
    var basePlayState = 0
    var baseCurrentTime = 0L
    var baseTotalTime = 0L
    var playIcon1 = 0
    var albumCover2: Uri? = null
    var currentSource8: String? = null
    var albumName9: String? = null
    var songTitle10: String? = null
    var artistName11: String? = null
    var sourceIcon17 = 0
    var templateName18: String? = null

    constructor()

    @Suppress("DEPRECATION")
    constructor(source: Parcel) {
        baseMediaType = source.readInt()
        basePlayState = source.readInt()
        baseCurrentTime = source.readLong()
        baseTotalTime = source.readLong()
        playIcon1 = source.readInt()
        albumCover2 = source.readParcelable(Uri::class.java.classLoader)
        currentSource8 = source.readString()
        albumName9 = source.readString()
        songTitle10 = source.readString()
        artistName11 = source.readString()
        sourceIcon17 = source.readInt()
        templateName18 = source.readString()
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(baseMediaType)
        dest.writeInt(basePlayState)
        dest.writeLong(baseCurrentTime)
        dest.writeLong(baseTotalTime)
        dest.writeInt(playIcon1)
        dest.writeParcelable(albumCover2, flags)
        dest.writeString(currentSource8)
        dest.writeString(albumName9)
        dest.writeString(songTitle10)
        dest.writeString(artistName11)
        dest.writeInt(sourceIcon17)
        dest.writeString(templateName18)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<Media> = object : Parcelable.Creator<Media> {
            override fun createFromParcel(source: Parcel): Media = Media(source)
            override fun newArray(size: Int): Array<Media?> = arrayOfNulls(size)
        }
    }
}
