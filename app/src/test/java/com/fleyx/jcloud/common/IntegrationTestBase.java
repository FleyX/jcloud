package com.fleyx.jcloud.common;

import com.fleyx.jcloud.common.context.CurrentUser;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.model.dto.StorageSpaceSaveDto;
import com.fleyx.jcloud.model.dto.UserSaveDto;
import com.fleyx.jcloud.model.vo.StorageSpaceVo;
import com.fleyx.jcloud.model.vo.UserVo;
import com.fleyx.jcloud.service.StorageSpaceService;
import com.fleyx.jcloud.service.UserService;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;

/**
 * 集成测试基类：统一 SpringBootTest 上下文与"用户 + 存储空间"准备样板。
 * <p>
 * 需要事务回滚的子类自行追加 {@code @Transactional}（如存在手动清理数据的用例则不加）。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final long DEFAULT_QUOTA_BYTES = 10737418240L;

    @TempDir
    protected Path tempDir;

    @Autowired
    protected UserService userService;

    @Autowired
    protected StorageSpaceService storageSpaceService;

    /**
     * 准备用户与其独占存储空间，默认 10GB 配额，并写入 UserContext。
     */
    protected UserWithSpace prepareUserWithStorageSpace() {
        return prepareUserWithStorageSpace(DEFAULT_QUOTA_BYTES);
    }

    /**
     * 准备用户与其独占存储空间，并写入 UserContext。
     *
     * @param quotaBytes 配额字节数；0 表示不限
     */
    protected UserWithSpace prepareUserWithStorageSpace(long quotaBytes) {
        Path spacePath = tempDir.resolve("space-" + System.nanoTime());
        StorageSpaceSaveDto spaceDto = new StorageSpaceSaveDto();
        spaceDto.setName("用户空间");
        spaceDto.setPath(spacePath.toString());
        StorageSpaceVo space = storageSpaceService.save(spaceDto);
        afterSpaceCreated(space);

        UserSaveDto userDto = new UserSaveDto();
        userDto.setUsername("user_" + Long.toUnsignedString(System.nanoTime(), 36));
        userDto.setPassword("123456");
        userDto.setStorageSpaceId(space.getId());
        userDto.setQuota(toQuotaValue(quotaBytes));
        userDto.setQuotaUnit(toQuotaUnit(quotaBytes));
        UserVo user = userService.saveUser(userDto);
        UserContext.set(new CurrentUser(user.getId(), user.getUsername()));

        return new UserWithSpace(user, space, spacePath);
    }

    /**
     * 子类可覆盖：存储空间创建后、用户创建前的额外初始化。
     */
    protected void afterSpaceCreated(StorageSpaceVo space) {
    }

    protected Path resolvePhysicalPath(UserWithSpace userWithSpace, String physicalPath) {
        return userWithSpace.spacePath()
                .resolve("files")
                .resolve(userWithSpace.user().getUsername())
                .resolve(physicalPath);
    }

    private static long toQuotaValue(long quotaBytes) {
        return quotaBytes == DEFAULT_QUOTA_BYTES ? 10L : quotaBytes;
    }

    private static String toQuotaUnit(long quotaBytes) {
        return quotaBytes == DEFAULT_QUOTA_BYTES ? "GB" : "B";
    }

    public record UserWithSpace(UserVo user, StorageSpaceVo space, Path spacePath) {
    }
}
