package com.fleyx.jcloud.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.User;
import com.fleyx.jcloud.service.support.UserUsedSpaceSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每日已用空间全量对账任务。
 * <p>
 * 每天凌晨 3 点执行（错开回收站清理的凌晨 2 点），对所有未删除用户按逻辑口径
 * 全量重算已用空间，使任何原因产生的漂移最迟一天内自动收敛。
 * 任务幂等：逐用户独立重算，单个用户失败不影响其余用户。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsedSpaceReconcileJob {

    private final UserMapper userMapper;
    private final UserUsedSpaceSupport userUsedSpaceSupport;

    /**
     * 每天凌晨 3 点执行对账。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcile() {
        log.info("开始每日已用空间对账任务");
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<>());
        if (users.isEmpty()) {
            log.info("无用户需要对账，对账任务结束");
            return;
        }

        int success = 0;
        int failed = 0;
        for (User user : users) {
            try {
                long recalculated = userUsedSpaceSupport.recalcUsedSpace(user.getId());
                success++;
                log.debug("用户已用空间对账完成: userId={}, usedSpace={}", user.getId(), recalculated);
            } catch (Exception e) {
                failed++;
                log.error("用户已用空间对账异常: userId={}", user.getId(), e);
            }
        }
        log.info("每日已用空间对账任务完成: 成功={}, 失败={}", success, failed);
    }
}
