package com.urcl.utils.uploader.model;

import java.io.File;

/**
 * 一个简单的数据类，用于封装一个待处理的相册任务信息
 */
public class AlbumInfo {
    private final String albumId;
    private final String tid;
    private final File folder;

    public AlbumInfo(String albumId, String tid, File folder) {
        this.albumId = albumId;
        this.tid = tid;
        this.folder = folder;
    }

    public String getAlbumId() {
        return albumId;
    }

    public String getTid() {
        return tid;
    }

    public File getFolder() {
        return folder;
    }
}
