package com.urcl.utils.image.timechange;

public enum SortType {

    /**
     * 前下划线,形如 123_xxx_xx_xxx.png
     */
    RRE_UNDERLINE,

    /**
     * 后括号,形如 xxx_xxx_xx(123).png
     */
    POST_PARENTHESES,

    /**
     * 形如 20250725154901
     */
    TIMESTAMP_14,
    /**
     * 形如 20250731_xxx_0001.jpg
     */
    DATE_STRING_SEQ
}
