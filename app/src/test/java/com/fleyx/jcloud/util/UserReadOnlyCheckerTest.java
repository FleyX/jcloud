package com.fleyx.jcloud.util;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用户只读状态校验测试。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserReadOnlyCheckerTest {

    @Autowired
    private UserReadOnlyChecker userReadOnlyChecker;

    @Autowired
    private UserMapper userMapper;

    @Test
    void shouldAllowWriteWhenUserNotReadOnly() {
        User user = new User();
        user.setUsername("writeAllowedUser");
        user.setPassword("123456");
        user.setReadOnly(0);
        userMapper.insert(user);

        assertDoesNotThrow(() -> userReadOnlyChecker.checkWriteAllowed(user.getId()));
    }

    @Test
    void shouldRejectWriteWhenUserReadOnly() {
        User user = new User();
        user.setUsername("readOnlyUser");
        user.setPassword("123456");
        user.setReadOnly(1);
        userMapper.insert(user);

        assertThrows(BusinessException.class,
                () -> userReadOnlyChecker.checkWriteAllowed(user.getId()));
    }

    @Test
    void shouldAllowWriteForUnknownUser() {
        assertDoesNotThrow(() -> userReadOnlyChecker.checkWriteAllowed(-1L));
    }
}
