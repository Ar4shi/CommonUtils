package com.urcl.utils.image.timechange;

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
public class BatchModifyImageTime {

    public static void modifyCreationTime(String folderPath, LocalDateTime startTime, NameType nameType, SortType sortType) {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("指定的文件夹不存在或不是一个有效的文件夹。");
            return;
        }

        // 获取文件夹中的所有图片文件
        List<File> imageFiles = getImageFiles(folder, nameType, sortType);

        // 循环处理每张图片
        for (int i = 0; i < imageFiles.size(); i++) {
            File imageFile = imageFiles.get(i);
            // 计算当前图片的创建时间，每次递增 1 分钟
            LocalDateTime currentTime = startTime.plusMinutes(i);
            // 将 LocalDateTime 转换为 Instant
            Instant instant = currentTime.toInstant(ZoneOffset.UTC);
            // 创建 FileTime 对象
            FileTime fileTime = FileTime.from(instant);

            // 1. 移除EXIF元数据
            try {
                removeAllMetadata(imageFile);
                System.out.println("已移除EXIF元数据: " + imageFile.getName());
            }catch (Exception e) {
                System.out.println("处理文件 " + imageFile.getName() + " 时出错: " + e.getMessage());
            }

            // 1. 修改MD5：向文件末尾追加随机字节
            try (FileOutputStream fos = new FileOutputStream(imageFile, true)) {
                byte[] randomByte = new byte[1];
                ThreadLocalRandom.current().nextBytes(randomByte); // 生成1个随机字节
                fos.write(randomByte); // 追加到文件末尾
                System.out.println("已向文件追加随机字节，新MD5将变化: " + imageFile.getName());
            } catch (IOException e) {
                System.out.println("修改文件内容时出错: " + e.getMessage());
                continue; // 跳过当前文件，继续处理下一个
            }

            try {
                // 修改文件的创建时间
                Files.setAttribute(imageFile.toPath(), "creationTime", fileTime);
                // 修改文件的修改时间
                Files.setLastModifiedTime(imageFile.toPath(), fileTime);
                System.out.println("成功修改文件 " + imageFile.getName() + " 的创建时间为 " + currentTime);
            } catch (IOException e) {
                System.out.println("修改文件 " + imageFile.getName() + " 的创建时间时出错：" + e.getMessage());
            }
        }
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
                    if (fileName.endsWith(".png") || fileName.endsWith(".jpg")) {
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
        }else if (NameType.DATE_STRING_SEQ == nameType) { // 新增排序类型
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
        }
    }
}
