// DiServer.onBind 返回的整包接口。只声明媒体子接口：事务码 1 = getIMediaDI，与服务端一致。
// （服务端 onBind 只在首个客户端绑定时调用一次，之后所有绑定方拿到的都是当时缓存的 binder，
//  可能是 IAdapterDI 也可能是 IMediaDI，客户端两种都要兼容。）
package com.ecarx.dim_interaction.bussiness.bean;

import com.ecarx.dim_interaction.bussiness.bean.IMediaDI;

interface IAdapterDI {
    IMediaDI getIMediaDI();
}
