package com.urcl.utils.uploader.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class ListFileResponse {

    @SerializedName("list")
    private List<FileInfo> list;

    @SerializedName("errno")
    private int errno;

    public List<FileInfo> getList() {
        return list;
    }

    public int getErrno() {
        return errno;
    }

    public static class FileInfo {
        @SerializedName("tid")
        private String tid;

        public String getTid() {
            return tid;
        }
    }
}
