package com.urcl.utils.image.timechange;

public enum NameType {

    /**
     * 纯数字 形如 123456
     */
    NUMBER,

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
    DATE_STRING_SEQ,
    /**
     * 例如：0 (1).jpg, 1-1 (10).jpg
     */
    PREFIX_IN_PARENTHESES,

    /**
     * 形如：XXXXXX202508_1 只根据_后面的数字排序
     */
    PREFIX_YYYYMM_SEQ
}
