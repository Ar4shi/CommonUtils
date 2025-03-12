package com.urcl.utils.image.timechange;

import java.io.File;
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

/**
 * 批量修改图片的时间
 */
public class BatchModifyImageTime {

    public static void modifyCreationTime(String folderPath, LocalDateTime startTime, SortType sortType) {
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("指定的文件夹不存在或不是一个有效的文件夹。");
            return;
        }

        // 获取文件夹中的所有图片文件
        List<File> imageFiles = getImageFiles(folder, sortType);

        // 循环处理每张图片
        for (int i = 0; i < imageFiles.size(); i++) {
            File imageFile = imageFiles.get(i);
            // 计算当前图片的创建时间，每次递增 1 分钟
            LocalDateTime currentTime = startTime.plusMinutes(i);
            // 将 LocalDateTime 转换为 Instant
            Instant instant = currentTime.toInstant(ZoneOffset.UTC);
            // 创建 FileTime 对象
            FileTime fileTime = FileTime.from(instant);

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

    public static List<File> getImageFiles(File folder, SortType sortType) {
        List<File> imageFiles = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files != null) {
            fileSort(files, sortType);
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

    private static void fileSort(File[] files, SortType sortType) {
        if (SortType.POST_PARENTHESES == sortType) {
            Arrays.sort(files, Comparator.comparing(file -> {
                String fileName = file.getName();
                int startIndex = fileName.lastIndexOf('(') + 1;
                int endIndex = fileName.lastIndexOf(')');
                return Integer.parseInt(fileName.substring(startIndex, endIndex));
            }));
        } else if (SortType.RRE_UNDERLINE == sortType) {
            Arrays.sort(files, Comparator.comparing(file -> {
                String fileName = file.getName();
                int startIndex = 0;
                int endIndex = fileName.indexOf('_');
                return Integer.parseInt(fileName.substring(startIndex, endIndex));
            }));
        }
    }
}
