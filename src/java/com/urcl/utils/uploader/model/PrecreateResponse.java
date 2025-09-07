package com.urcl.utils.uploader.model;

import com.google.gson.annotations.SerializedName;

/**
 * [已更新] 对应 precreate 接口的JSON响应，现在可以处理多种成功格式
 */
public class PrecreateResponse {

    // 格式一：需要上传时返回
    @SerializedName("uploadid")
    private String uploadid;

    // 格式二：秒传成功时可能返回 (旧格式)
    @SerializedName("fs_id")
    private Long fsId;

    // 格式三：秒传成功时在data对象内返回 (新发现的格式)
    @SerializedName("data")
    private Data data;

    @SerializedName("errno")
    private int errno;

    // 内部类，用于匹配 data: { ... } 结构
    public static class Data {
        @SerializedName("fs_id")
        private long fsid;

        public long getFsid() {
            return fsid;
        }
    }

    public String getUploadid() {
        return uploadid;
    }

    /**
     * [已更新] 智能获取fs_id，无论它是在顶层还是在data对象内
     * @return 文件的fs_id，如果不存在则返回null
     */
    public Long getFsId() {
        if (fsId != null && fsId > 0) {
            return fsId; // 从顶层获取
        }
        if (data != null && data.getFsid() > 0) {
            return data.getFsid(); // 从data对象内获取
        }
        return null;
    }

    public int getErrno() {
        return errno;
    }

    /**
     * 判断是否需要上传文件内容
     * @return true 如果需要上传
     */
    public boolean isUploadNeeded() {
        return errno == 0 && uploadid != null && !uploadid.isEmpty();
    }

    /**
     * [已更新] 判断是否秒传成功，现在会检查两种可能性
     * @return true 如果服务器已有该文件
     */
    public boolean isSecondPass() {
        Long foundFsId = getFsId();
        return errno == 0 && foundFsId != null && foundFsId > 0;
    }
}