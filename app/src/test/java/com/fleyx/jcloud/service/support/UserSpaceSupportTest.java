package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.mapper.UserMapper;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.model.po.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 用户与存储空间共享支撑组件测试。
 */
@ExtendWith(MockitoExtension.class)
class UserSpaceSupportTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private StorageSpaceMapper storageSpaceMapper;

    @InjectMocks
    private UserSpaceSupport userSpaceSupport;

    @Test
    void shouldReturnUser() {
        User user = new User();
        user.setId("u1");
        when(userMapper.selectById("u1")).thenReturn(user);

        assertSame(user, userSpaceSupport.requireUser("u1"));
    }

    @Test
    void shouldRejectMissingUser() {
        when(userMapper.selectById("u1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> userSpaceSupport.requireUser("u1"));
    }

    @Test
    void shouldRejectNullSpaceId() {
        assertThrows(BusinessException.class, () -> userSpaceSupport.requireSpace((String) null));
    }

    @Test
    void shouldRejectMissingSpaceById() {
        when(storageSpaceMapper.selectById("s1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> userSpaceSupport.requireSpace("s1"));
    }

    @Test
    void shouldReturnSpaceById() {
        StorageSpace space = new StorageSpace();
        space.setId("s1");
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);

        assertSame(space, userSpaceSupport.requireSpace("s1"));
    }

    @Test
    void shouldRejectMissingSpaceByUser() {
        User user = new User();
        user.setId("u1");
        user.setStorageSpaceId("s1");
        when(storageSpaceMapper.selectById("s1")).thenReturn(null);

        assertThrows(BusinessException.class, () -> userSpaceSupport.requireSpace(user));
    }

    @Test
    void shouldReturnSpaceByUser() {
        User user = new User();
        user.setId("u1");
        user.setStorageSpaceId("s1");
        StorageSpace space = new StorageSpace();
        space.setId("s1");
        when(storageSpaceMapper.selectById("s1")).thenReturn(space);

        assertSame(space, userSpaceSupport.requireSpace(user));
    }
}
