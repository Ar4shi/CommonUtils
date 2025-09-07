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

    /**
     * 根据文件名自动检测其命名类型。
     *
     * @param folder 要检测的文件夹。
     * @return 检测到的 NameType。
     * @throws IllegalStateException 如果文件夹为空或无法识别文件名类型。
     */
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

        // 注意：匹配顺序很重要，应从最具体到最不具体
        if (nameWithoutExt.matches("^20\\d{12}$")) return NameType.TIMESTAMP_14;
        if (nameWithoutExt.matches("^\\d{8}_.+")) return NameType.DATE_STRING_SEQ;
        if (nameWithoutExt.matches(".+\\s\\(\\d+\\)$")) return NameType.PREFIX_IN_PARENTHESES;
        if (nameWithoutExt.matches(".+\\(\\d+\\)$")) return NameType.POST_PARENTHESES;
        if (nameWithoutExt.matches("^\\d+_.+")) return NameType.RRE_UNDERLINE;
        if (nameWithoutExt.matches("^\\d+$")) return NameType.NUMBER;

        throw new IllegalStateException("无法识别文件名格式。已检查的第一个文件: '" + firstFile.getName() + "'");
    }

    private static void removeAllMetadata(File imageFile) throws Exception {
        // 方法1: 使用Apache Commons Imaging移除EXIF数据
        removeExifMetadata(imageFile);

        // 方法2: 重写文件内容(破坏可能存在的其他元数据结构)
        rewriteFileContent(imageFile);
    }

    private static void removeExifMetadata(File imageFile) throws Exception {
        try {
            ByteSource byteSource = new ByteSourceFile(imageFile);
            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                new ExifRewriter().removeExifMetadata(byteSource, outputStream);

                // 将处理后的数据写回原文件
                try (FileOutputStream fos = new FileOutputStream(imageFile)) {
                    fos.write(outputStream.toByteArray());
                }
            }
        } catch (Exception e) {
            System.out.println("使用Commons Imaging移除EXIF失败: " + e.getMessage());
            // 继续尝试其他方法
        }
    }

    private static void rewriteFileContent(File imageFile) throws IOException {
        // 读取文件内容
        byte[] content = Files.readAllBytes(imageFile.toPath());

        // 查找可能的元数据标记并清除
        // 这是一个简化的示例，实际实现可能需要更复杂的解析
        String contentStr = new String(content);

        // 尝试查找并移除XMP元数据
        int xmpStart = contentStr.indexOf("<x:xmpmeta");
        if (xmpStart != -1) {
            int xmpEnd = contentStr.indexOf("</x:xmpmeta>", xmpStart);
            if (xmpEnd != -1) {
                xmpEnd += 12; // "</x:xmpmeta>"的长度
                // 用空格替换XMP数据
                for (int i = xmpStart; i < xmpEnd && i < content.length; i++) {
                    content[i] = ' ';
                }
            }
        }

        // 写回修改后的内容
        try (FileOutputStream fos = new FileOutputStream(imageFile)) {
            fos.write(content);
        }
    }

    public static List<File> getImageFiles(File folder, NameType nameType, SortType sortType) {
        List<File> imageFiles = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files != null) {
            fileSort(files, nameType, sortType);
            for (File file : files) {
                if (file.isFile()) {
                    String fileName = file.getName().toLowerCase();
                    if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".gif")) {
                        imageFiles.add(file);
                    }
                }
            }
        }
        return imageFiles;
    }

    private static void fileSort(File[] files, NameType nameType, SortType sortType) {
        if (NameType.POST_PARENTHESES == nameType) {
            Arrays.sort(files, Comparator.comparing(file -> {
                String fileName = file.getName();
                int startIndex = fileName.lastIndexOf('(') + 1;
                int endIndex = fileName.lastIndexOf(')');
                return Integer.parseInt(fileName.substring(startIndex, endIndex)) * (SortType.SEQUENTIAL.equals(sortType) ? 1 : -1);
            }));
        } else if (NameType.NUMBER == nameType) {
            Arrays.sort(files, Comparator.comparing(file -> {
                String fileName = file.getName();
                int endIndex = fileName.lastIndexOf('.');
                return Integer.parseInt(fileName.substring(0, endIndex)) * (SortType.SEQUENTIAL.equals(sortType) ? 1 : -1);
            }));
        } else if (NameType.RRE_UNDERLINE == nameType) {
            Arrays.sort(files, Comparator.comparing(file -> {
                String fileName = file.getName();
                int startIndex = 0;
                int endIndex = fileName.indexOf('_');
                return Integer.parseInt(fileName.substring(startIndex, endIndex)) * (SortType.SEQUENTIAL.equals(sortType) ? 1 : -1);
            }));
        } else if (NameType.TIMESTAMP_14 == nameType) {
            // 新增：14位时间戳排序逻辑
            Arrays.sort(files, Comparator.comparing(file -> {
                String fileName = file.getName();
                // 使用正则匹配连续14位数字
                Matcher matcher = Pattern.compile("\\d{14}").matcher(fileName);
                if (matcher.find()) {
                    return Long.parseLong(matcher.group()) * (SortType.SEQUENTIAL.equals(sortType) ? 1 : -1);
                } else {
                    // 未找到时按文件名自然排序（或抛异常）
                    return 0L; // 或 throw new IllegalArgumentException("无效文件名: " + fileName);
                }
            }));
        } else if (NameType.DATE_STRING_SEQ == nameType) { // 新增排序类型
            Arrays.sort(files, Comparator.comparing(file -> {
                String fileName = file.getName();
                // 提取最后一个下划线后的数字部分（如0001）
                int lastUnderscore = fileName.lastIndexOf('_');
                int dotIndex = fileName.lastIndexOf('.');

                // 确保文件名格式有效
                if (lastUnderscore == -1 || dotIndex <= lastUnderscore) {
                    throw new IllegalArgumentException("无效文件名格式: " + fileName);
                }

                String seqPart = fileName.substring(lastUnderscore + 1, dotIndex);
                return Integer.parseInt(seqPart) * (SortType.SEQUENTIAL.equals(sortType) ? 1 : -1);
            }));
        } else if (NameType.PREFIX_IN_PARENTHESES == nameType) { // 【新增的 else if 分支】
            // 使用一个自定义的、支持多级比较的 Comparator
            Arrays.sort(files, (file1, file2) -> {
                String name1 = file1.getName();
                String name2 = file2.getName();

                // --- 解析 file1 ---
                int p1Start = name1.lastIndexOf('(');
                int p1End = name1.lastIndexOf(')');
                // 找到括号前的前缀，注意要包含前面的空格以便后续处理
                String prefix1 = name1.substring(0, p1Start).trim();
                int num1 = Integer.parseInt(name1.substring(p1Start + 1, p1End));

                // --- 解析 file2 ---
                int p2Start = name2.lastIndexOf('(');
                int p2End = name2.lastIndexOf(')');
                String prefix2 = name2.substring(0, p2Start).trim();
                int num2 = Integer.parseInt(name2.substring(p2Start + 1, p2End));

                // --- 多级比较逻辑 ---
                // 1. 先比较括号前的部分 (主排序键)
                int prefixCompare = prefix1.compareTo(prefix2);
                if (prefixCompare != 0) {
                    return prefixCompare; // 如果前缀不同，直接返回比较结果
                }

                // 2. 如果前缀相同，再比较括号里的数字 (次排序键)
                return Integer.compare(num1, num2);
            });
            // 【重要】在排序完成后，单独处理逆序逻辑
            // 因为上面的自定义比较器没有简单的方法直接乘以 -1
            if (SortType.REVERSE.equals(sortType)) {
                // 将数组反转
                for (int i = 0; i < files.length / 2; i++) {
                    File temp = files[i];
                    files[i] = files[files.length - 1 - i];
                    files[files.length - 1 - i] = temp;
                }
            }
        }
    }
}
