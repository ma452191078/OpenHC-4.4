package io.github.gcbond.openhc.core.nearby

// 本层沿用的名字，实现在 core:common，各层共用同一份。

/** CRC-16/ARC。控制帧用它校验从帧头 0x7F 到载荷末尾的全部字节，结果小端放在帧尾。 */
internal typealias Crc16Arc = io.github.gcbond.openhc.core.common.codec.Crc16Arc

/** AES-128-CFB（CFB128），控制帧载荷加密用，见 [NearbyFrameCipher]。 */
internal typealias AesCfb = io.github.gcbond.openhc.core.common.crypto.AesCfb
