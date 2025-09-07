package com.urcl.utils.uploader;

import com.urcl.utils.uploader.clients.BaiduPhotoApiClient;
import com.urcl.utils.uploader.model.CreateAlbumResponse;
import com.urcl.utils.uploader.model.CreateResponse;
import com.urcl.utils.uploader.model.PrecreateResponse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class BaiduPhotoUploader {

    public void batchUpload(String root_folder_path, String bdstoken, String cookie) {
        File rootFolder = new File(root_folder_path);
        if (!rootFolder.isDirectory()) {
            System.err.println("错误: 提供的根路径不是一个文件夹! " + root_folder_path);
            return;
        }

        File[] subFolders = rootFolder.listFiles(File::isDirectory);
        if (subFolders == null || subFolders.length == 0) {
            System.out.println("在根目录中没有找到任何子文件夹。");
            return;
        }

        System.out.println("发现 " + subFolders.length + " 个子文件夹，将为每一个创建一个相册并上传照片...");

        BaiduPhotoApiClient apiClient = new BaiduPhotoApiClient(cookie, bdstoken);

        for (File folder : subFolders) {
            System.out.println("\n=======================================================");
            System.out.println("====== 开始处理文件夹: " + folder.getName() + " ======");
            System.out.println("=======================================================");

            try {
                processAlbumFolder(apiClient, folder);
            } catch (Exception e) {
                System.err.println("!!! 处理文件夹 " + folder.getName() + " 时发生严重错误: " + e.getMessage());
            }

            try {
                System.out.println("====== 文件夹 '" + folder.getName() + "' 处理完毕，暂停3秒... ======");
                Thread.sleep(3000); // 每个相册处理完后，暂停3秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("\n\n所有文件夹处理完毕！");
    }

    /**
     * [已更新] 处理单个子文件夹：创建相册 -> 上传所有图片 -> 批量添加到相册
     */
    private void processAlbumFolder(BaiduPhotoApiClient apiClient, File folder) {
        String albumTitle = folder.getName();
        int successCount = 0;
        List<Long> uploadedFsids = new ArrayList<>(); // 用于收集上传成功的fsid

        File[] filesToUpload = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") ||
                        name.toLowerCase().endsWith(".jpeg") ||
                        name.toLowerCase().endsWith(".png"));

        if (filesToUpload == null || filesToUpload.length == 0) {
            System.out.println("文件夹 '" + albumTitle + "' 中没有找到图片文件，跳过。");
            return;
        }

        List<File> fileList = new ArrayList<>(Arrays.asList(filesToUpload));
        fileList.sort(Comparator.comparingLong(File::lastModified));
        System.out.println("发现 " + fileList.size() + " 张图片，已按日期排序。");

        try {
            // 步骤 0: 创建相册
            System.out.println("\n======== 步骤 0: 创建新相册 '" + albumTitle + "' ========");
            CreateAlbumResponse albumResponse = apiClient.createAlbum(albumTitle);
            String newAlbumId = albumResponse.getAlbumId();
            String newTid = albumResponse.getInfo().getTid();
            System.out.println("  -> 成功创建相册! 相册ID: " + newAlbumId + ", TID: " + newTid);

            if (newAlbumId == null || newAlbumId.isEmpty() || newTid == null || newTid.isEmpty()) {
                throw new IOException("未能从创建相册的响应中获取有效的 album_id 或 tid。");
            }

            // 循环上传文件，并收集fsid
            for (File file : fileList) {
                System.out.println("\n----------------------------------------------------");
                System.out.println(">>> 正在上传文件: " + file.getName());
                try {
                    long fsid = uploadSingleFileAndGetFsid(apiClient, file, newAlbumId);
                    uploadedFsids.add(fsid);
                    successCount++;
                } catch (Exception e) {
                    System.err.println("!!! 上传文件 " + file.getName() + " 失败: " + e.getMessage());
                }
                // Thread.sleep(500);
            }

            // 步骤 4: 所有文件上传完成后，批量添加到相册
            if (!uploadedFsids.isEmpty()) {
                System.out.println("\n----------------------------------------------------");
                System.out.println(">>> 所有文件上传完成，正在将 " + uploadedFsids.size() + " 个文件批量添加到相册...");
                apiClient.addFilesToAlbum(newAlbumId, uploadedFsids, newTid);
                System.out.println("  -> 批量添加成功!");
            }

        } catch (Exception e) {
            System.err.println("!!! 处理相册 '" + albumTitle + "' 失败: " + e.getMessage());
        }

        System.out.println("\n文件夹 '" + albumTitle + "' 处理完毕！成功上传: " + successCount + " / " + fileList.size());
    }

    /**
     * [已更新] 上传单个文件并返回fsid，去掉了添加到相册的步骤
     */
    private long uploadSingleFileAndGetFsid(BaiduPhotoApiClient apiClient, File file, String albumId) throws IOException {
        String remotePath = "/" + file.getName();
        long fsid;

        // 步骤 1: 预创建
        System.out.println("  [1/3] 正在预创建...");
        PrecreateResponse precreateResponse = apiClient.precreate(file, remotePath, albumId);

        if (precreateResponse.isSecondPass()) {
            fsid = precreateResponse.getFsId();
            System.out.println("  -> 文件已存在 (秒传成功)! FSID: " + fsid);
        } else if (precreateResponse.isUploadNeeded()) {
            String uploadId = precreateResponse.getUploadid();
            System.out.println("  -> 获取到 UploadID: " + uploadId);

            // 步骤 2: 上传数据
            System.out.println("  [2/3] 正在上传文件数据...");
            apiClient.uploadPart(file, remotePath, uploadId);
            System.out.println("  -> 文件数据上传完成。");

            // 步骤 3: 创建文件记录
            System.out.println("  [3/3] 正在创建文件记录...");
            CreateResponse createResponse = apiClient.createFile(file, remotePath, uploadId, albumId);
            if (createResponse.getErrno() != 0 || createResponse.getData() == null) {
                throw new IOException("创建文件记录失败，错误码: " + createResponse.getErrno());
            }
            fsid = createResponse.getData().getFsid();
            System.out.println("  -> 文件记录创建成功! FSID: " + fsid);
        } else if (precreateResponse.getErrno() == 0) {
            fsid = precreateResponse.getFsId();
            System.out.println("  -> 文件已存在 (秒传成功)! FSID: " + fsid);
        } else {
            throw new IOException("预创建失败，错误码: " + precreateResponse.getErrno());
        }

        return fsid;
    }
}