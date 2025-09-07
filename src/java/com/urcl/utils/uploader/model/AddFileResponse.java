package com.urcl.utils.uploader.model;

import com.google.gson.annotations.SerializedName;

public class AddFileResponse {
    @SerializedName("errno")
    private int errno;

    public int getErrno() {
        return errno;
    }
}