package com.urcl.utils.image.timechange;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.imaging.common.bytesource.ByteSource;
import org.apache.commons.imaging.common.bytesource.ByteSourceFile;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量修改图片的时间
 */
@Slf4j
public class BatchModifyImageTime {

    // ... modifyCreationTime 方法 和 detectNameType 方法保持不变 ...
    public static void modifyCreationTime(ModificationOptions options) {
        String root_folder_path = options.getFolderPath();
        File rootFolder = new File(root_folder_path);
        if (!rootFolder.isDirectory()) {
            log.error("错误: 提供的根路径不是一个文件夹! {}", root_folder_path);
            return;
        }

        File[] subFolders = rootFolder.listFiles(File::isDirectory);
        if (subFolders == null || subFolders.length == 0) {
            log.info("在根目录中没有找到任何子文件夹。");
            return;
        }

        log.info("发现 {} 个子文件夹，将为每一个文件夹进行分别进行排序。", subFolders.length);

        for (File folder : subFolders) {
            log.info("\n=======================================================");
            log.info("====== 开始处理文件夹: {} ======", folder.getName());
            log.info("=======================================================");

            try {
                // 步骤 1: 自动检测 NameType
                NameType detectedType = detectNameType(folder);
                log.info("已自动检测到命名类型: {}", detectedType);

                // 步骤 2: 基于检测到的类型获取并排序文件
                List<File> imageFiles = getImageFiles(folder, detectedType, options.getSortType());
                if (imageFiles.isEmpty()) {
                    log.warn("文件夹 {} 中没有找到符合条件的图片文件。", folder.getName());
                    continue; // 跳到下一个文件夹
                }

                log.info("找到 {} 个图片文件，准备处理...", imageFiles.size());

                // 步骤 3: 循环处理每张图片
                for (int i = 0; i < imageFiles.size(); i++) {
                    File imageFile = imageFiles.get(i);
                    LocalDateTime currentTime = options.getStartTime().plusMinutes(i);
                    Instant instant = currentTime.toInstant(ZoneOffset.UTC);
                    FileTime fileTime = FileTime.from(instant);

                    // 移除EXIF元数据
                    if (options.getRemoveMetadata()) {
                        try {
                            removeAllMetadata(imageFile);
                            log.debug("已移除EXIF元数据: {}", imageFile.getName());
                        } catch (Exception e) {
                            log.error("处理文件 {} 时移除元数据出错: {}", imageFile.getName(), e.getMessage());
                        }
                    }

                    // 修改MD5
                    if (options.getModifyMD5()) {
                        try (FileOutputStream fos = new FileOutputStream(imageFile, true)) {
                            byte[] randomByte = new byte[1];
                            ThreadLocalRandom.current().nextBytes(randomByte);
                            fos.write(randomByte);
                            log.debug("已向文件追加随机字节以修改MD5: {}", imageFile.getName());
                        } catch (IOException e) {
                            log.error("修改文件 {} 内容时出错: {}", imageFile.getName(), e.getMessage());
                            continue;
                        }
                    }

                    // 修改文件时间戳
                    try {
                        Files.setAttribute(imageFile.toPath(), "creationTime", fileTime);
                        Files.setLastModifiedTime(imageFile.toPath(), fileTime);
                        log.info("成功修改 '{}' 的时间为 {}", imageFile.getName(), currentTime);
                    } catch (IOException e) {
                        log.error("修改文件 {} 的创建时间时出错：{}", imageFile.getName(), e.getMessage());
                    }
                }
            } catch (IllegalStateException e) {
                // 如果 detectNameType 抛出异常，则捕获它
                log.error("!!! 跳过文件夹 '{}': {}", folder.getName(), e.getMessage());
            }
        }
    }

    private static NameType detectNameType(File folder) throws IllegalStateException {
        File[] imageFiles = folder.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".jpg") ||
                        name.toLowerCase().endsWith(".jpeg") ||
                        name.toLowerCase().endsWith(".png"));

        if (imageFiles == null || imageFiles.length == 0) {
            throw new IllegalStateException("文件夹为空，无法确定命名约定。");
        }

        File firstFile = imageFiles[0];
        String nameWithoutExt = firstFile.getName().substring(0, firstFile.getName().lastIndexOf('.'));

        // 匹配顺序很重要，从最具体的开始
        if (nameWithoutExt.matches("^[a-zA-Z]+20\\d{4}_\\d+$")) return NameType.PREFIX_YYYYMM_SEQ;
        if (nameWithoutExt.matches("^20\\d{12}$")) return NameType.TIMESTAMP_14;
        if (nameWithoutExt.matches("^\\d{8}_.+")) return NameType.DATE_STRING_SEQ;
        if (nameWithoutExt.matches(".+\\s\\(\\d+\\)$")) return NameType.PREFIX_IN_PARENTHESES;
        if (nameWithoutExt.matches(".+\\(\\d+\\)$")) return NameType.POST_PARENTHESES;
        if (nameWithoutExt.matches("^\\d+_.+")) return NameType.RRE_UNDERLINE;
        if (nameWithoutExt.matches("^\\d+$")) return NameType.NUMBER;

        throw new IllegalStateException("无法识别文件名格式。已检查的第一个文件: '" + firstFile.getName() + "'");
    }

    // ... removeAllMetadata, removeExifMetadata, rewriteFileContent 方法保持不变 ...
    private static void removeAllMetadata(File imageFile) throws Exception {
        removeExifMetadata(imageFile);
        rewriteFileContent(imageFile);
    }

    private static void removeExifMetadata(File imageFile) throws Exception {
        try {
            ByteSource byteSource = new ByteSourceFile(imageFile);
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                new ExifRewriter().removeExifMetadata(byteSource, outputStream);
                try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                    fos.write(outputStream.toByteArray());
                }
            }
        } catch (Exception e) {
            System.out.println("使用Commons Imaging移除EXIF失败: " + e.getMessage());
        }
    }

    private static void rewriteFileContent(File imageFile) throws IOException {
        byte[] content = Files.readAllBytes(imageFile.toPath());
        String contentStr = new String(content);
        int xmpStart = contentStr.indexOf("<x:xmpmeta");
        if (xmpStart != -1) {
            int xmpEnd = contentStr.indexOf("</x:xmpmeta>", xmpStart);
            if (xmpEnd != -1) {
                xmpEnd += 12;
                for (int i = xmpStart; i < xmpEnd && i < content.length; i++) {
                    content[i] = ' ';
                }
            }
        }
        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(content);
        }
    }


    public static List<File> getImageFiles(File folder, NameType nameType, SortType sortType) {
        File[] files = folder.listFiles((dir, name) -> {
            String lowerName = name.toLowerCase();
            return lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".gif");
        });

        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        fileSort(files, nameType, sortType);
        return new ArrayList<>(Arrays.asList(files));
    }

    /**
     * 根据指定的命名类型和排序类型对文件数组进行排序。
     */
    private static void fileSort(File[] files, NameType nameType, SortType sortType) {
        // 使用 switch 结构，更清晰且易于扩展
        switch (nameType) {
            case POST_PARENTHESES:
                Arrays.sort(files, Comparator.comparing(file -> {
                    String fileName = file.getName();
                    int startIndex = fileName.lastIndexOf('(') + 1;
                    int endIndex = fileName.lastIndexOf(')');
                    return Integer.parseInt(fileName.substring(startIndex, endIndex));
                }));
                break;

            case NUMBER:
                Arrays.sort(files, Comparator.comparing(file -> {
                    String fileName = file.getName();
                    int endIndex = fileName.lastIndexOf('.');
                    return Integer.parseInt(fileName.substring(0, endIndex));
                }));
                break;

            case RRE_UNDERLINE:
                Arrays.sort(files, Comparator.comparing(file -> {
                    String fileName = file.getName();
                    int endIndex = fileName.indexOf('_');
                    return Integer.parseInt(fileName.substring(0, endIndex));
                }));
                break;

            case TIMESTAMP_14:
                Arrays.sort(files, Comparator.comparing(file -> {
                    Matcher matcher = Pattern.compile("\\d{14}").matcher(file.getName());
                    return matcher.find() ? Long.parseLong(matcher.group()) : 0L;
                }));
                break;

            // ======================== [核心修改] ========================
            // 让 PREFIX_YYYYMM_SEQ 和 DATE_STRING_SEQ 使用完全相同的排序逻辑
            // 即：只按最后一个下划线后面的数字排序
            case PREFIX_YYYYMM_SEQ:
            case DATE_STRING_SEQ:
                Arrays.sort(files, Comparator.comparingInt(file -> {
                    String fileName = file.getName();
                    int lastUnderscore = fileName.lastIndexOf('_');
                    int dotIndex = fileName.lastIndexOf('.');

                    if (lastUnderscore == -1 || dotIndex <= lastUnderscore) {
                        log.warn("无法从 '{}' 中解析出序号，将按0处理。", fileName);
                        return 0; // 返回一个默认值
                    }

                    try {
                        String seqPart = fileName.substring(lastUnderscore + 1, dotIndex);
                        return Integer.parseInt(seqPart);
                    } catch (NumberFormatException e) {
                        log.warn("文件名 '{}' 的序号部分不是有效数字，将按0处理。", fileName);
                        return 0; // 解析失败时返回默认值
                    }
                }));
                break;
            // ==========================================================

            case PREFIX_IN_PARENTHESES:
                Arrays.sort(files, (file1, file2) -> {
                    String name1 = file1.getName();
                    String name2 = file2.getName();
                    int p1Start = name1.lastIndexOf('(');
                    String prefix1 = name1.substring(0, p1Start).trim();
                    int num1 = Integer.parseInt(name1.substring(p1Start + 1, name1.lastIndexOf(')')));
                    int p2Start = name2.lastIndexOf('(');
                    String prefix2 = name2.substring(0, p2Start).trim();
                    int num2 = Integer.parseInt(name2.substring(p2Start + 1, name2.lastIndexOf(')')));
                    int prefixCompare = prefix1.compareTo(prefix2);
                    return (prefixCompare != 0) ? prefixCompare : Integer.compare(num1, num2);
                });
                break;

            default:
                // 对于未知或不符合任何规则的类型，按文件名自然排序
                Arrays.sort(files, Comparator.comparing(File::getName));
                break;
        }

        // 所有排序完成后，统一处理逆序逻辑
        if (SortType.REVERSE.equals(sortType)) {
            for (int i = 0; i < files.length / 2; i++) {
                File temp = files[i];
                files[i] = files[files.length - 1 - i];
                files[files.length - 1 - i] = temp;
            }
        }
    }
}