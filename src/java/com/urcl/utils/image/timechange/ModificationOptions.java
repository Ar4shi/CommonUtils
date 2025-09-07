package com.urcl.utils.image.timechange;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModificationOptions {

    private String folderPath;

    private LocalDateTime startTime;

    private SortType sortType;

    private Boolean removeMetadata;

    private Boolean modifyMD5;

}
