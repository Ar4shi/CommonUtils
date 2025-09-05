package com.urcl.utils.image.timechange;

import org.junit.Test;

import java.time.LocalDateTime;

public class BatchModifyImageTimeTest {

    @Test
    public void run() {
        // 指定图片所在的文件夹路径
        String folderPath = "D:\\Edge下载\\collection\\BrightSky\\";
        for (int i = 1; i <= 2; i++) {
            String finalPath = folderPath + i;
            // 指定起始时间，这里设置为 2025 年 3 月 7 日 12:00:00
            LocalDateTime startTime = LocalDateTime.of(2021, 8, 9, 1, 0, 0);
            ModificationOptions options = ModificationOptions.builder()
                    .folderPath(finalPath)
                    .startTime(startTime)
                    .nameType(NameType.NUMBER)
                    .sortType(SortType.REVERSE)
                    .removeMetadata(false)
                    .modifyMD5(false)
                    .build();
            BatchModifyImageTime.modifyCreationTime(options);
        }

    }
}
