package com.urcl.utils.uploader.clients;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.urcl.utils.uploader.model.AddFileResponse;
import com.urcl.utils.uploader.model.CreateAlbumResponse;
import com.urcl.utils.uploader.model.CreateResponse;
import com.urcl.utils.uploader.model.PrecreateResponse;
import com.urcl.utils.uploader.utils.Utils;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory; // 导入 SLF4J 的类

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class BaiduPhotoApiClient {

    // ** [新增] 创建一个私有的、静态的 Logger 实例 **
    private static final Logger log = LoggerFactory.getLogger(BaiduPhotoApiClient.class);

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final String cookie;
    private final String bdstoken;

    // API 接口地址
    private static final String CREATE_ALBUM_URL = "https://photo.baidu.com/youai/album/v1/create";
    private static final String PRECREATE_URL = "https://photo.baidu.com/youai/file/v1/precreate";
    private static final String CREATE_URL = "https://photo.baidu.com/youai/file/v1/create";
    private static final String ADDFILE_URL = "https://photo.baidu.com/youai/album/v1/addfile";
    private static final String UPLOAD_URL_FORMAT = "https://xafj-ct11.pcs.baidu.com/rest/2.0/pcs/superfile2?method=upload&app_id=16051585&channel=chunlei&clienttype=70&web=1&path=%s&uploadid=%s&partseq=0";


    public BaiduPhotoApiClient(String cookie, String bdstoken) {
        this.cookie = cookie;
        this.bdstoken = bdstoken;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    /**
     * 新增: 步骤 0 - 创建相册
     */
    public CreateAlbumResponse createAlbum(String albumTitle) throws IOException {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(CREATE_ALBUM_URL)).newBuilder()
                .addQueryParameter("clienttype", "70")
                .addQueryParameter("bdstoken", this.bdstoken)
                .addQueryParameter("title", albumTitle)
                .addQueryParameter("source", "0")
                .addQueryParameter("tid", String.valueOf(System.currentTimeMillis()))
                .build();

        Request request = buildRequest(url, null, "https://photo.baidu.com/photo/web/album");

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("创建相册请求失败: " + response);
            String responseBody = Objects.requireNonNull(response.body()).string();
            // ** [优化] 将API响应日志降级为 DEBUG **
            log.debug("Create Album API response: {}", responseBody);
            CreateAlbumResponse createAlbumResponse = gson.fromJson(responseBody, CreateAlbumResponse.class);
            if (createAlbumResponse.getErrno() != 0) {
                throw new IOException("创建相册API错误, errno: " + createAlbumResponse.getErrno() + ", response: " + responseBody);
            }
            return createAlbumResponse;
        }
    }

    /**
     * 步骤 1: 预创建文件
     */
    public PrecreateResponse precreate(File file, String remotePath, String albumId) throws IOException {
        String md5;
        try {
            md5 = Utils.calculateMD5(file);
        } catch (Exception e) {
            throw new IOException("无法计算文件MD5", e);
        }

        RequestBody formBody = new FormBody.Builder()
                .add("autoinit", "1")
                .add("block_list", "[\"" + md5 + "\"]")
                .add("isdir", "0")
                .add("rtype", "1")
                .add("ctype", "11")
                .add("path", remotePath)
                .add("size", String.valueOf(file.length()))
                .add("slice-md5", md5)
                .add("content-md5", md5)
                .add("local_ctime", String.valueOf(Instant.now().getEpochSecond()))
                .add("local_mtime", String.valueOf(file.lastModified() / 1000))
                .build();

        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(PRECREATE_URL)).newBuilder()
                .addQueryParameter("clienttype", "70")
                .addQueryParameter("bdstoken", this.bdstoken)
                .build();

        Request request = buildRequest(url, formBody, "https://photo.baidu.com/photo/web/album/" + albumId);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("预创建请求失败: " + response);
            String responseBody = Objects.requireNonNull(response.body()).string();
            // ** [优化] 将API响应日志降级为 DEBUG **
            log.debug("Precreate API response: {}", responseBody);
            return gson.fromJson(responseBody, PrecreateResponse.class);
        }
    }

    /**
     * 步骤 2: 上传文件数据
     */
    public void uploadPart(File file, String remotePath, String uploadId) throws IOException {
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));

        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "blob", fileBody)
                .build();

        String encodedPath = URLEncoder.encode(remotePath, StandardCharsets.UTF_8.toString());
        String uploadUrl = String.format(UPLOAD_URL_FORMAT, encodedPath, uploadId);

        Request request = new Request.Builder()
                .url(uploadUrl)
                .post(multipartBody)
                .addHeader("Cookie", this.cookie)
                .addHeader("User-Agent", "\"Not;A=Brand\";v=\"99\", \"Microsoft Edge\";v=\"139\", \"Chromium\";v=\"139\"")
                .addHeader("Referer", "https://photo.baidu.com/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("文件上传失败: " + response);
            String responseBody = Objects.requireNonNull(response.body()).string();
            // ** [优化] 将API响应日志降级为 DEBUG **
            log.debug("Upload Part API response: {}", responseBody);
        }
    }

    /**
     * 步骤 3: 创建文件记录
     */
    public CreateResponse createFile(File file, String remotePath, String uploadId, String albumId) throws IOException {
        String md5;
        try {
            md5 = Utils.calculateMD5(file);
        } catch (Exception e) {
            throw new IOException("无法计算文件MD5", e);
        }

        RequestBody formBody = new FormBody.Builder()
                .add("path", remotePath)
                .add("size", String.valueOf(file.length()))
                .add("uploadid", uploadId)
                .add("block_list", "[\"" + md5 + "\"]")
                .add("isdir", "0")
                .add("rtype", "1")
                .add("content-md5", md5)
                .add("ctype", "11")
                .build();

        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(CREATE_URL)).newBuilder()
                .addQueryParameter("clienttype", "70")
                .addQueryParameter("bdstoken", this.bdstoken)
                .build();

        Request request = buildRequest(url, formBody, "https://photo.baidu.com/photo/web/album/" + albumId);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("创建文件请求失败: " + response);
            String responseBody = Objects.requireNonNull(response.body()).string();
            // ** [优化] 将API响应日志降级为 DEBUG **
            log.debug("Create File API response: {}", responseBody);
            return gson.fromJson(responseBody, CreateResponse.class);
        }
    }

    /**
     * 步骤 4: 将一批文件批量添加到相册
     */
    public void addFilesToAlbum(String albumId, List<Long> fsids, String tid) throws IOException {
        if (fsids == null || fsids.isEmpty()) {
            return;
        }

        JsonArray listArray = new JsonArray();
        for (Long fsid : fsids) {
            JsonObject fsidObject = new JsonObject();
            fsidObject.addProperty("fsid", fsid);
            listArray.add(fsidObject);
        }

        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(ADDFILE_URL)).newBuilder()
                .addQueryParameter("clienttype", "70")
                .addQueryParameter("bdstoken", this.bdstoken)
                .addQueryParameter("album_id", albumId)
                .addQueryParameter("tid", tid)
                .addQueryParameter("list", listArray.toString())
                .build();

        Request request = buildRequest(url, null, "https://photo.baidu.com/photo/web/album/" + albumId);

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("添加文件到相册失败: " + response);
            String responseBody = Objects.requireNonNull(response.body()).string();
            // ** [优化] 将API响应日志降级为 DEBUG **
            log.debug("Add to Album API response: {}", responseBody);
            AddFileResponse addFileResponse = gson.fromJson(responseBody, AddFileResponse.class);
            if (addFileResponse.getErrno() != 0) {
                throw new IOException("添加文件到相册API错误, errno: " + addFileResponse.getErrno() + ", response: " + responseBody);
            }
        }
    }

    /**
     * 辅助方法，用于构建通用的请求
     */
    private Request buildRequest(HttpUrl url, RequestBody body, String referer) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .addHeader("Cookie", this.cookie)
                .addHeader("Referer", referer)
                .addHeader("User-Agent", "\"Not;A=Brand\";v=\"99\", \"Microsoft Edge\";v=\"139\", \"Chromium\";v=\"139\"")
                .addHeader("X-Requested-With", "XMLHttpRequest");

        if (body != null) {
            builder.post(body);
        }

        return builder.build();
    }
}