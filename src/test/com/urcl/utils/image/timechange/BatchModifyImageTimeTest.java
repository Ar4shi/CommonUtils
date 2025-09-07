package com.urcl.utils.image.timechange;

import org.junit.Test;

import java.time.LocalDateTime;

public class BatchModifyImageTimeTest {

    private static final String ROOT_FOLDER_PATH = "D:\\Edge下载\\collection\\Hajily";

    @Test
    public void run() {
        // 指定起始时间
        LocalDateTime startTime = LocalDateTime.of(2025, 9, 9, 1, 0, 0);
        ModificationOptions options = ModificationOptions.builder()
                .folderPath(ROOT_FOLDER_PATH)
                .startTime(startTime)
                .nameType(NameType.POST_PARENTHESES)
                .sortType(SortType.REVERSE)
                .removeMetadata(false)
                .modifyMD5(false)
                .build();
        BatchModifyImageTime.modifyCreationTime(options);
    }
}
