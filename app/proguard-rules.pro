# R8 保留规则。Kotlin、协程、AndroidX 自带 consumer 规则；这里只补本仓库直接用到、
# 且可能被按名字查找触及的部分。

-keepattributes Signature, InnerClasses, *Annotation*, Exceptions

# BouncyCastle：协议层调用 org.bouncycastle.math.ec.rfc7748.X25519 和
# org.bouncycastle.math.ec.rfc8032.Ed25519 的静态方法，以及轻量级 AES-GCM
# （GCMBlockCipher + AESEngine，替代安卓 4.4 上不可用的 JCA GCM），其余实现交给 R8 裁剪。
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.math.ec.rfc8032.** { *; }
# GCMBlockCipher.newInstance 内部按名字挑选乘法器实现，保留这一族类以免被裁掉。
-keep class org.bouncycastle.crypto.modes.gcm.** { *; }
-keep class org.bouncycastle.crypto.modes.GCMBlockCipher { *; }
-keep class org.bouncycastle.crypto.params.AEADParameters { *; }
# 被裁掉的 BC 类里还有对 javax/OSGi 等非安卓类的引用，关闭相关缺失告警。
-dontwarn org.bouncycastle.**

# 应用内更新：安卓 4.4 上用 BC 的 JSSE 连 GitHub。Provider 内部是按类名注册/加载协议实现和套件的，
# 这些类没有直接引用，R8 看不见，必须保留。
-keep class org.bouncycastle.jsse.** { *; }
-keep class org.bouncycastle.tls.** { *; }
-keep class org.bouncycastle.util.** { *; }
# JSSE 通过 JCA 按名字取摘要/加解密/MAC/签名实现（SHA-512、AES-GCM、HmacSHA256 等），整包保留。
-keep class org.bouncycastle.jcajce.provider.** { *; }

# kotlinx.serialization：只用 JsonElement 的解析/输出（直接引用 serializer，没有 @Serializable
# 反射序列化），但运行时内部会按名字查找伴生对象，保留这些成员。
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
