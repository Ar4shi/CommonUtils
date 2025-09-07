package com.urcl.utils.uploader;

import com.urcl.utils.uploader.clients.BaiduPhotoApiClient;
import com.urcl.utils.uploader.model.AlbumInfo;
import com.urcl.utils.uploader.model.CreateAlbumResponse;
import com.urcl.utils.uploader.model.CreateResponse;
import com.urcl.utils.uploader.model.PrecreateResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class BaiduPhotoUploader {

    // [可配置] 一次并行处理多少个文件夹（相册）
    private static final int BATCH_SIZE = 5;

    // [可配置] 设置创建每个相册之间的间隔时间（毫秒）
    private static final int CREATE_ALBUM_INTERVAL_MS = 500;
    
    // [可配置] 设置每个文件上传的间隔时间（毫秒）
    public static final int UPLOAD_FILE_INTERVAL_MS = 10;

    // [可配置] 设置绑定每个相册之间的最小间隔时间（毫秒）
    public static final int BAND_ALBUM_INTERVAL_MS = 5000;

    // 所有的线程在执行“添加到相册”操作前都必须先获得这个锁。
    private static final Object ALBUM_ADD_LOCK = new Object();

    public void batchUpload(String root_folder_path, String bdstoken, String cookie) {
        File rootFolder = new File(root_folder_path);
        if (!rootFolder.isDirectory()) {
            System.err.println("错误: 提供的根路径不是一个文件夹! " + root_folder_path);
            return;
        }

        File[] subFolders = rootFolder.listFiles(f -> f.isDirectory() && !f.getName().startsWith("[Finished]"));
        if (subFolders == null || subFolders.length == 0) {
            System.out.println("在根目录中没有找到需要处理的子文件夹。");
            return;
        }

        List<File> subFolderList = Arrays.asList(subFolders);
        System.out.println("发现 " + subFolderList.size() + " 个待处理文件夹，将以每批 " + BATCH_SIZE + " 个进行处理...");

        BaiduPhotoApiClient mainApiClient = new BaiduPhotoApiClient(cookie, bdstoken);
        ExecutorService executor = Executors.newFixedThreadPool(BATCH_SIZE);

        for (int i = 0; i < subFolderList.size(); i += BATCH_SIZE) {
            List<File> batch = subFolderList.subList(i, Math.min(i + BATCH_SIZE, subFolderList.size()));

            System.out.printf("\n======================= 开始处理批次 %d / %d =======================\n",
                    (i / BATCH_SIZE + 1), (int) Math.ceil((double) subFolderList.size() / BATCH_SIZE));

            System.out.println("\n======== [批次 " + (i / BATCH_SIZE + 1) + "] 步骤 1: 串行创建相册 ========");
            List<AlbumInfo> createdAlbums = new ArrayList<>();
            for (File folder : batch) {
                String albumTitle = folder.getName();
                System.out.println("\n>>> 准备创建相册: " + albumTitle);
                try {
                    CreateAlbumResponse albumResponse = mainApiClient.createAlbum(albumTitle);
                    String newAlbumId = albumResponse.getAlbumId();
                    String newTid = albumResponse.getInfo().getTid();

                    if (newAlbumId == null || newAlbumId.isEmpty() || newTid == null || newTid.isEmpty()) {
                        System.err.println("!!! 创建相册 '" + albumTitle + "' 失败: 未能获取到有效的album_id或tid。");
                        continue;
                    }

                    System.out.println("  -> 成功创建相册! 相册ID: " + newAlbumId);
                    createdAlbums.add(new AlbumInfo(newAlbumId, newTid, folder));

                    System.out.println("  -> 等待 " + CREATE_ALBUM_INTERVAL_MS / 1000 + " 秒...");
                    Thread.sleep(CREATE_ALBUM_INTERVAL_MS);

                } catch (Exception e) {
                    System.err.println("!!! 创建相册 '" + albumTitle + "' 时发生严重错误: " + e.getMessage());
                }
            }

            if (createdAlbums.isEmpty()) {
                System.out.println("\n本批次没有成功创建任何相册，跳至下一批。");
                continue;
            }

            System.out.println("\n======== [批次 " + (i / BATCH_SIZE + 1) + "] 步骤 2: 并行上传文件到已创建的相册中 ========");
            List<CompletableFuture<Void>> uploadFutures = createdAlbums.stream()
                    .map(albumInfo -> CompletableFuture.runAsync(() -> {
                        processFilesForAlbum(new BaiduPhotoApiClient(cookie, bdstoken), albumInfo);
                    }, executor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(uploadFutures.toArray(new CompletableFuture[0])).join();
            System.out.println("\n======== [批次 " + (i / BATCH_SIZE + 1) + "] 文件上传任务已全部完成 ========");
        }

        executor.shutdown();
        System.out.println("\n\n所有批次处理完毕！");
    }

    private void processFilesForAlbum(BaiduPhotoApiClient apiClient, AlbumInfo albumInfo) {
        String albumTitle = albumInfo.getFolder().getName();
        long threadId = Thread.currentThread().getId();
        System.out.printf("====== [线程 %d] 开始上传照片到相册: %s ======\n", threadId, albumTitle);

        int successCount = 0;
        List<Long> uploadedFsids = new ArrayList<>();

        File[] filesToUpload = albumInfo.getFolder().listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg") || name.toLowerCase().endsWith(".png"));

        if (filesToUpload == null || filesToUpload.length == 0) {
            System.out.printf("[线程 %d] 文件夹 '%s' 中没有图片，跳过上传。\n", threadId, albumTitle);
            renameFolderToFinished(albumInfo.getFolder());
            return;
        }

        List<File> fileList = new ArrayList<>(Arrays.asList(filesToUpload));
        fileList.sort(Comparator.comparingLong(File::lastModified));
        System.out.printf("[线程 %d] 发现 %d 张图片，已按日期排序。\n", threadId, fileList.size());

        try {
            for (File file : fileList) {
                System.out.printf("\n>>> [线程 %d] 正在上传文件: %s\n", threadId, file.getName());
                try {
                    long fsid = uploadSingleFileAndGetFsid(apiClient, file, albumInfo.getAlbumId());
                    uploadedFsids.add(fsid);
                    successCount++;
                } catch (Exception e) {
                    System.err.printf("!!! [线程 %d] 上传文件 %s 失败: %s\n", threadId, file.getName(), e.getMessage());
                }
                Thread.sleep(UPLOAD_FILE_INTERVAL_MS); // 文件间短暂停顿
            }

            if (!uploadedFsids.isEmpty()) {
                System.out.printf("\n>>> [线程 %d] 所有文件上传完成，准备将 %d 个文件批量添加到相册...\n", threadId, uploadedFsids.size());
                
                synchronized (ALBUM_ADD_LOCK) {
                    System.out.printf(">>> [线程 %d] 获取到同步锁，正在添加到相册...\n", threadId);
                    apiClient.addFilesToAlbum(albumInfo.getAlbumId(), uploadedFsids, albumInfo.getTid());
                    System.out.printf("  -> [线程 %d] 批量添加成功! 释放同步锁。\n", threadId);

                    // 在释放锁之前，可以额外增加一个短暂的强制间隔，让服务器有喘息之机
                    Thread.sleep(BAND_ALBUM_INTERVAL_MS);
                }
                // ------------------------------------------------------------------
            }

            renameFolderToFinished(albumInfo.getFolder());

        } catch (Exception e) {
            System.err.printf("!!! [线程 %d] 处理相册 '%s' 的文件时失败: %s\n", threadId, albumTitle, e.getMessage());
        }

        System.out.printf("\n====== [线程 %d] 相册 '%s' 处理完毕！成功上传: %d / %d ======\n", threadId, albumTitle, successCount, fileList.size());
    }

    private long uploadSingleFileAndGetFsid(BaiduPhotoApiClient apiClient, File file, String albumId) throws IOException {
        String remotePath = "/" + file.getName();
        long fsid;
        long threadId = Thread.currentThread().getId();

        System.out.printf("  [%d] [1/3] 正在预创建...\n", threadId);
        PrecreateResponse precreateResponse = apiClient.precreate(file, remotePath, albumId);

        if (precreateResponse.isSecondPass()) {
            fsid = precreateResponse.getFsId();
            System.out.printf("  -> [%d] 文件已存在 (秒传成功)! FSID: %d\n", threadId, fsid);
        } else if (precreateResponse.isUploadNeeded()) {
            String uploadId = precreateResponse.getUploadid();
            System.out.printf("  -> [%d] 获取到 UploadID: %s\n", threadId, uploadId);

            System.out.printf("  -> [%d] [2/3] 正在上传文件数据...\n", threadId);
            apiClient.uploadPart(file, remotePath, uploadId);
            System.out.printf("  -> [%d] 文件数据上传完成。\n", threadId);

            System.out.printf("  -> [%d] [3/3] 正在创建文件记录...\n", threadId);
            CreateResponse createResponse = apiClient.createFile(file, remotePath, uploadId, albumId);
            if (createResponse.getErrno() != 0 || createResponse.getData() == null) {
                throw new IOException("创建文件记录失败，错误码: " + createResponse.getErrno());
            }
            fsid = createResponse.getData().getFsid();
            System.out.printf("  -> [%d] 文件记录创建成功! FSID: %d\n", threadId, fsid);
        } else if (precreateResponse.getErrno() == 0 && precreateResponse.getFsId() != null) {
            fsid = precreateResponse.getFsId();
            System.out.printf("  -> [%d] 文件已存在 (秒传成功)! FSID: %d\n", threadId, fsid);
        } else {
            throw new IOException("预创建失败，错误码: " + precreateResponse.getErrno());
        }
        return fsid;
    }

    private void renameFolderToFinished(File folder) {
        try {
            if (folder.getName().startsWith("[Finished]")) return;
            java.nio.file.Path sourcePath = folder.toPath();
            java.nio.file.Path destPath = new File(folder.getParent(), "[Finished] " + folder.getName()).toPath();
            Files.move(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.printf("  -> [线程 %d] 已成功将文件夹重命名为: %s\n", Thread.currentThread().getId(), destPath.getFileName());
        } catch (IOException e) {
            System.err.printf("!!! [线程 %d] 重命名文件夹 %s 失败: %s\n", Thread.currentThread().getId(), folder.getName(), e.getMessage());
        }
    }
}