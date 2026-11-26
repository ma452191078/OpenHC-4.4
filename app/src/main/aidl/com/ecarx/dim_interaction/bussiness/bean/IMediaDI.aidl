// 车机 DiServer 的媒体接口（IMediaDI）。事务码按方法声明顺序生成，必须与服务端一致：
// 1 getSupportSourceType / 2 updateMediaSourceList / 3 updateCurrentSourceType /
// 4 updateMediaInfo / 5 updatePlayStatus / 6 updateCurrentProgress / 7 registerListener
package com.ecarx.dim_interaction.bussiness.bean;

import com.ecarx.dim_interaction.bussiness.bean.IMediaDiListener;
import com.ecarx.dim_interaction.bussiness.bean.Media;

interface IMediaDI {
    String[] getSupportSourceType();
    void updateMediaSourceList(in int[] sourceTypeList);
    void updateCurrentSourceType(int sourceType);
    void updateMediaInfo(in Media media);
    void updatePlayStatus(in Media media);
    void updateCurrentProgress(long progress);
    void registerListener(IMediaDiListener listener);
}
