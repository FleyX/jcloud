package com.fleyx.jcloud.common.constant;

/**
 * 文件节点相关常量。
 */
public final class FileNodeConstants {

    private FileNodeConstants() {
    }

    /**
     * 虚拟根节点占位 ID。
     * <p>
     * 数值 0 的 base36 表示，左补零至 13 位。所有文件树根节点的 parent_id 都指向它。
     */
    public static final String ROOT_ID = "0000000000000";

    /**
     * 节点类型：文件。
     */
    public static final String TYPE_FILE = "file";

    /**
     * 节点类型：文件夹。
     */
    public static final String TYPE_FOLDER = "folder";

    /**
     * id 路径分隔符。
     */
    public static final String PATH_SEPARATOR = ".";

    /**
     * id 路径最大深度（业务软限制），约 5 层祖先。
     */
    public static final int MAX_PATH_DEPTH = 5;

    /**
     * 本地文件节点来源类型。
     */
    public static final String SOURCE_LOCAL = "local";

    /**
     * 远程文件节点来源类型。
     */
    public static final String SOURCE_REMOTE = "remote";

    /**
     * 文件操作结果状态：成功。
     */
    public static final String STATUS_SUCCESS = "success";

    /**
     * 文件操作结果状态：跳过。
     */
    public static final String STATUS_SKIPPED = "skipped";

    /**
     * 文件操作结果状态：失败。
     */
    public static final String STATUS_FAILED = "failed";
}
