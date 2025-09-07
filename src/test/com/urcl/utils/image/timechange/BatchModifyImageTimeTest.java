package com.urcl.utils.image.timechange;

import org.junit.Test;

import java.time.LocalDateTime;

public class BatchModifyImageTimeTest {

    private static final String ROOT_FOLDER_PATH = "D:\\Edge下载\\collection\\UnityNya";

    private static final LocalDateTime START_TIME = LocalDateTime.of(2025, 9, 9, 1, 0, 0);

    private static final SortType SORT_TYPE = SortType.REVERSE;

    private static final Boolean REMOVE_META_DATA = Boolean.FALSE;

    private static final Boolean MODIFY_MD5 = Boolean.FALSE;

    @Test
    public void run() {
        ModificationOptions options = ModificationOptions.builder()
                .folderPath(ROOT_FOLDER_PATH)
                .startTime(START_TIME)
                .sortType(SORT_TYPE)
                .removeMetadata(REMOVE_META_DATA)
                .modifyMD5(MODIFY_MD5)
                .build();
        BatchModifyImageTime.modifyCreationTime(options);
    }
}
