package com.urcl.utils.image.timechange;

import org.junit.Test;

import java.time.LocalDateTime;

public class BatchModifyImageTimeTest {

    @Test
    public void run() {
        // 指定图片所在的文件夹路径
        String folderPath = "D:\\Edge下载\\collection\\NFFA1\\";
        for(int i =1;i<=20;i++){
            String finalPath = folderPath+i;
            // 指定起始时间，这里设置为 2025 年 3 月 7 日 12:00:00
            LocalDateTime startTime = LocalDateTime.of(2020, 8, 9, 1, 0, 0);

            BatchModifyImageTime.modifyCreationTime(finalPath, startTime, SortType.DATE_STRING_SEQ);
        }

    }
}
