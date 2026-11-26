// 仪表盘反控回调：方向盘切音源。事务码 1 = switchSource，与服务端一致。
package com.ecarx.dim_interaction.bussiness.bean;

interface IMediaDiListener {
    void switchSource(int sourceType);
}
