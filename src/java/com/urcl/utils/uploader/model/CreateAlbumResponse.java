package com.urcl.utils.uploader.model;

import com.google.gson.annotations.SerializedName;

public class CreateAlbumResponse {
    @SerializedName("album_id")
    private String albumId;

    @SerializedName("errno")
    private int errno;

    // 关键的tid在info对象中
    @SerializedName("info")
    private AlbumInfo info;

    public static class AlbumInfo {
        @SerializedName("tid")
        private String tid;

        public String getTid() {
            return tid;
        }
    }

    public String getAlbumId() {
        return albumId;
    }

    public int getErrno() {
        return errno;
    }

    public AlbumInfo getInfo() {
        return info;
    }
}