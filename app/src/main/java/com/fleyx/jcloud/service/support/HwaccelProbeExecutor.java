package com.fleyx.jcloud.service.support;

/**
 * 硬解方式真实编码探测执行器（可替换组件）。
 * <p>
 * 对指定硬解方式做一次"初始化设备 + 实际编码"的真实探测，而非解析 ffmpeg 编译列表。
 * 探测结果用于首次启动时选择本机最强可用硬解档位并落库（见 {@link HwaccelProbeSupport}）。
 */
public interface HwaccelProbeExecutor {

    /**
     * 对指定硬解方式执行一次真实编码探测。
     *
     * @param hwaccel 硬解方式（nvenc / qsv / vaapi）
     * @param device  硬件设备路径（仅 vaapi 使用），可由实现自行解析
     * @return 可用返回 true；进程异常、超时、非零退出一律视为不可用，不抛异常
     */
    boolean probe(String hwaccel, String device);
}
