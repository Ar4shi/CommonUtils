package com.urcl.utils.uploader.model;

import com.google.gson.annotations.SerializedName;

/**
 * 对应 create 接口的JSON响应
 */
public class CreateResponse {

    // 关键信息在外层的data对象里
    @SerializedName("data")
    private Data data;

    @SerializedName("errno")
    private int errno;

    // 使用一个内部类来匹配嵌套的JSON结构
    public static class Data {
        @SerializedName("fs_id")
        private long fsid;

        @SerializedName("md5")
        private String md5;

        public long getFsid() {
            return fsid;
        }

        public String getMd5() {
            return md5;
        }
    }

    public Data getData() {
        return data;
    }

    public int getErrno() {
        return errno;
    }
}
