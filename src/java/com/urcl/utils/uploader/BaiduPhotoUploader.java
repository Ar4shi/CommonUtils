package com.urcl.utils.uploader;

import com.urcl.utils.uploader.clients.BaiduPhotoApiClient;
import com.urcl.utils.uploader.model.AlbumInfo;
import com.urcl.utils.uploader.model.CreateAlbumResponse;
import com.urcl.utils.uploader.model.CreateResponse;
import com.urcl.utils.uploader.model.PrecreateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class BaiduPhotoUploader {

    // 使用SLF4J获取一个Logger实例
    private static final Logger log = LoggerFactory.getLogger(BaiduPhotoUploader.class);

    // [可配置] 一次并行处理多少个文件夹（相册）
    private static final int BATCH_SIZE = 10;

    // [可配置] 设置创建每个相册之间的间隔时间（毫秒）
    private static final int CREATE_ALBUM_INTERVAL_MS = 500;

    // [可配置] 设置每个文件上传的间隔时间（毫秒）
    public static final int UPLOAD_FILE_INTERVAL_MS = 10;

    // [可配置] 设置每次调用“添加到相册”接口之间的最小间隔时间（毫秒）
    public static final int BAND_ALBUM_INTERVAL_MS = 10000;

    // [可配置] 批量添加文件到相册时，每批次包含的文件数量。这是为了防止URL过长（HTTP 414错误）
    private static final int ADD_TO_ALBUM_CHUNK_SIZE = 200;

    // 所有的线程在执行“添加到相册”操作前都必须先获得这个锁。
    private static final Object ALBUM_ADD_LOCK = new Object();

    // 用于存储每个上传任务结果的线程安全列表
    private final List<UploadTaskResult> taskResults = Collections.synchronizedList(new ArrayList<>());

    /**
     * 内部类，用于封装单个文件夹（相册）上传任务的结果。
     */
    private static class UploadTaskResult {
        private final String albumName;
        private final int totalFiles;
        private final int successfulUploads;
        private final String status;

        public UploadTaskResult(String albumName, int totalFiles, int successfulUploads) {
            this.albumName = albumName;
            this.totalFiles = totalFiles;
            this.successfulUploads = successfulUploads;

            if (totalFiles == 0) {
                this.status = "SKIPPED (EMPTY)";
            } else if (successfulUploads == totalFiles) {
                this.status = "SUCCESS";
            } else if (successfulUploads > 0) {
                this.status = "PARTIAL SUCCESS";
            } else {
                this.status = "FAILED";
            }
        }

        @Override
        public String toString() {
            return String.format("-> Album: %-40s | Status: %-15s | Files Uploaded: %d / %d",
                    albumName, status, successfulUploads, totalFiles);
        }
    }

    public void batchUpload(String root_folder_path, String bdstoken, String cookie) {
        File rootFolder = new File(root_folder_path);
        if (!rootFolder.isDirectory()) {
            log.error("错误: 提供的根路径不是一个文件夹! {}", root_folder_path);
            return;
        }

        File[] subFolders = rootFolder.listFiles(f -> f.isDirectory() && !f.getName().startsWith("[Finished]"));
        if (subFolders == null || subFolders.length == 0) {
            log.info("在根目录中没有找到需要处理的子文件夹。");
            return;
        }

        List<File> subFolderList = Arrays.asList(subFolders);
        log.info("发现 {} 个待处理文件夹，将以每批 {} 个进行处理...", subFolderList.size(), BATCH_SIZE);

        BaiduPhotoApiClient mainApiClient = new BaiduPhotoApiClient(cookie, bdstoken);
        ExecutorService executor = Executors.newFixedThreadPool(BATCH_SIZE);

        for (int i = 0; i < subFolderList.size(); i += BATCH_SIZE) {
            List<File> batch = subFolderList.subList(i, Math.min(i + BATCH_SIZE, subFolderList.size()));
            int batchNum = (i / BATCH_SIZE + 1);
            int totalBatches = (int) Math.ceil((double) subFolderList.size() / BATCH_SIZE);

            log.info("======================= 开始处理批次 {} / {} =======================", batchNum, totalBatches);

            log.info("======== [批次 {}] 步骤 1: 串行创建相册 ========", batchNum);
            List<AlbumInfo> createdAlbums = new ArrayList<>();
            for (File folder : batch) {
                String albumTitle = rootFolder.getName() + "_" + folder.getName();
                log.info(">>> 准备创建相册: {}", albumTitle);
                try {
                    CreateAlbumResponse albumResponse = mainApiClient.createAlbum(albumTitle);
                    String newAlbumId = albumResponse.getAlbumId();
                    String newTid = albumResponse.getInfo().getTid();

                    if (newAlbumId == null || newAlbumId.isEmpty() || newTid == null || newTid.isEmpty()) {
                        log.error("!!! 创建相册 '{}' 失败: 未能获取到有效的album_id或tid。", albumTitle);
                        taskResults.add(new UploadTaskResult(albumTitle, 0, 0));
                        continue;
                    }

                    log.info("  -> 成功创建相册! 相册ID: {}", newAlbumId);
                    createdAlbums.add(new AlbumInfo(newAlbumId, newTid, folder));
                    log.debug("  -> 等待 {} 秒...", (double) CREATE_ALBUM_INTERVAL_MS / 1000);
                    Thread.sleep(CREATE_ALBUM_INTERVAL_MS);

                } catch (Exception e) {
                    log.error("!!! 创建相册 '{}' 时发生严重错误:", albumTitle, e);
                    taskResults.add(new UploadTaskResult(albumTitle, 0, 0));
                }
            }

            if (createdAlbums.isEmpty()) {
                log.warn("本批次没有成功创建任何相册，跳至下一批。");
                continue;
            }

            log.info("======== [批次 {}] 步骤 2: 并行上传文件到已创建的相册中 ========", batchNum);
            List<CompletableFuture<Void>> uploadFutures = createdAlbums.stream()
                    .map(albumInfo -> CompletableFuture.runAsync(() -> {
                        processFilesForAlbum(new BaiduPhotoApiClient(cookie, bdstoken), albumInfo);
                    }, executor))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(uploadFutures.toArray(new CompletableFuture[0])).join();
            log.info("======== [批次 {}] 文件上传任务已全部完成 ========", batchNum);
        }

        executor.shutdown();
        log.info("所有批次处理完毕！");
        printSummaryReport();
    }

    private void processFilesForAlbum(BaiduPhotoApiClient apiClient, AlbumInfo albumInfo) {
        String albumTitle = albumInfo.getFolder().getName();
        Thread.currentThread().setName(albumTitle);
        String threadInfo = Thread.currentThread().getId() + "_" + Thread.currentThread().getName();
        log.info("====== [线程 {}] 开始上传照片到相册: {} ======", threadInfo, albumTitle);

        int successCount = 0;
        List<Long> uploadedFsids = new ArrayList<>();

        File[] filesToUpload = albumInfo.getFolder().listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg") || name.toLowerCase().endsWith(".png"));

        if (filesToUpload == null || filesToUpload.length == 0) {
            log.warn("[线程 {}] 文件夹 '{}' 中没有图片，跳过上传。", threadInfo, albumTitle);
            renameFolderToFinished(albumInfo.getFolder());
            taskResults.add(new UploadTaskResult(albumTitle, 0, 0));
            return;
        }

        List<File> fileList = new ArrayList<>(Arrays.asList(filesToUpload));
        fileList.sort(Comparator.comparingLong(File::lastModified));

        int totalFiles = fileList.size();
        log.info("[线程 {}] 发现 {} 张图片，已按日期排序。", threadInfo, totalFiles);

        try {
            for (int i = 0; i < totalFiles; i++) {
                File file = fileList.get(i);
                int currentFileNum = i + 1;

                log.info(">>> [线程 {}] [{}/{}] 正在上传文件: {}", threadInfo, currentFileNum, totalFiles, file.getName());
                try {
                    long fsid = uploadSingleFileAndGetFsid(apiClient, file, albumInfo.getAlbumId());
                    uploadedFsids.add(fsid);
                    successCount++;
                } catch (Exception e) {
                    log.error("!!! [线程 {}] [{}/{}] 上传文件 {} 失败: {}", threadInfo, currentFileNum, totalFiles, file.getName(), e.getMessage());
                }
                Thread.sleep(UPLOAD_FILE_INTERVAL_MS);
            }

            if (!uploadedFsids.isEmpty()) {
                log.info(">>> [线程 {}] 所有文件上传完成，准备将 {} 个文件分批添加到相册...", threadInfo, uploadedFsids.size());

                synchronized (ALBUM_ADD_LOCK) {
                    log.debug(">>> [线程 {}] 获取到同步锁，准备分批添加到相册...", threadInfo);

                    int totalFsids = uploadedFsids.size();
                    int totalChunks = (int) Math.ceil((double) totalFsids / ADD_TO_ALBUM_CHUNK_SIZE);

                    for (int j = 0; j < totalFsids; j += ADD_TO_ALBUM_CHUNK_SIZE) {
                        int end = Math.min(j + ADD_TO_ALBUM_CHUNK_SIZE, totalFsids);
                        List<Long> chunk = uploadedFsids.subList(j, end);
                        int currentChunkNum = (j / ADD_TO_ALBUM_CHUNK_SIZE) + 1;

                        try {
                            log.info("  -> [线程 {}] 正在添加批次 {}/{} ({}个文件) 到相册...", threadInfo, currentChunkNum, totalChunks, chunk.size());
                            apiClient.addFilesToAlbum(albumInfo.getAlbumId(), chunk, albumInfo.getTid());
                            log.info("     [线程 {}] 批次 {} 添加成功!", threadInfo, currentChunkNum);
                        } catch (Exception e) {
                            log.error("!!! [线程 {}] 添加批次 {} 到相册 '{}' 失败:", threadInfo, currentChunkNum, albumTitle, e);
                        }

                        log.debug("  -> [线程 {}] 等待 {} 秒，再提交下一批或释放锁...", threadInfo, (double) BAND_ALBUM_INTERVAL_MS / 1000);
                        Thread.sleep(BAND_ALBUM_INTERVAL_MS);
                        // ==========================================================
                    }

                    log.info("  -> [线程 {}] 所有批次添加完毕! 释放同步锁。", threadInfo);
                }
            }

            renameFolderToFinished(albumInfo.getFolder());

        } catch (Exception e) {
            log.error("!!! [线程 {}] 处理相册 '{}' 的文件时失败:", threadInfo, albumTitle, e);
        } finally {
            taskResults.add(new UploadTaskResult(albumTitle, totalFiles, successCount));
            log.info("====== [线程 {}] 相册 '{}' 处理完毕！成功上传: {} / {} ======", threadInfo, albumTitle, successCount, totalFiles);
        }
    }

    private long uploadSingleFileAndGetFsid(BaiduPhotoApiClient apiClient, File file, String albumId) throws IOException {
        String remotePath = "/" + file.getName();
        long fsid;
        String threadInfo = Thread.currentThread().getId() + "_" + Thread.currentThread().getName();

        log.debug("  [{}] [1/3] 正在预创建...", threadInfo);
        PrecreateResponse precreateResponse = apiClient.precreate(file, remotePath, albumId);

        if (precreateResponse.isSecondPass() || (precreateResponse.getErrno() == 0 && precreateResponse.getFsId() != null)) {
            fsid = precreateResponse.getFsId();
            log.info("  -> [{}] 文件已存在 (秒传成功)! FSID: {}", threadInfo, fsid);
        } else if (precreateResponse.isUploadNeeded()) {
            String uploadId = precreateResponse.getUploadid();
            log.debug("  -> [{}] 获取到 UploadID: {}", threadInfo, uploadId);

            log.debug("  -> [{}] [2/3] 正在上传文件数据...", threadInfo);
            apiClient.uploadPart(file, remotePath, uploadId);
            log.debug("  -> [{}] 文件数据上传完成。", threadInfo);

            log.debug("  -> [{}] [3/3] 正在创建文件记录...", threadInfo);
            CreateResponse createResponse = apiClient.createFile(file, remotePath, uploadId, albumId);
            if (createResponse.getErrno() != 0 || createResponse.getData() == null) {
                throw new IOException("创建文件记录失败，错误码: " + createResponse.getErrno());
            }
            fsid = createResponse.getData().getFsid();
            log.info("  -> [{}] 文件记录创建成功! FSID: {}", threadInfo, fsid);
        } else {
            throw new IOException("预创建失败，错误码: " + precreateResponse.getErrno());
        }
        return fsid;
    }

    private void renameFolderToFinished(File folder) {
        String threadInfo = Thread.currentThread().getId() + "_" + Thread.currentThread().getName();
        try {
            if (folder.getName().startsWith("[Finished]")) return;
            java.nio.file.Path sourcePath = folder.toPath();
            java.nio.file.Path destPath = new File(folder.getParent(), "[Finished] " + folder.getName()).toPath();
            Files.move(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("  -> [线程 {}] 已成功将文件夹重命名为: {}", threadInfo, destPath.getFileName());
        } catch (IOException e) {
            log.error("!!! [线程 {}] 重命名文件夹 {} 失败: {}", threadInfo, folder.getName(), e.getMessage());
        }
    }

    private void printSummaryReport() {
        log.info("==========================================================================");
        log.info("=======================   U P L O A D   S U M M A R Y   =======================");
        log.info("==========================================================================");

        if (taskResults.isEmpty()) {
            log.info("没有处理任何任务。");
            return;
        }

        long totalSuccess = taskResults.stream().filter(r -> "SUCCESS".equals(r.status)).count();
        long totalPartial = taskResults.stream().filter(r -> "PARTIAL SUCCESS".equals(r.status)).count();
        long totalFailed = taskResults.stream().filter(r -> "FAILED".equals(r.status)).count();
        long totalSkipped = taskResults.stream().filter(r -> r.status.startsWith("SKIPPED")).count();

        log.info("[总体统计]");
        log.info("  - 处理文件夹总数: {}", taskResults.size());
        log.info("  - 完全成功: {}", totalSuccess);
        log.info("  - 部分成功: {}", totalPartial);
        log.info("  - 失败: {}", totalFailed);
        log.info("  - 跳过 (空文件夹): {}", totalSkipped);

        log.info("[详细情况]");
        for (UploadTaskResult result : taskResults) {
            log.info(result.toString());
        }
        log.info("==========================================================================");
    }
}