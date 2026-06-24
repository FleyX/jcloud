package com.fleyx.jcloud.common;

import com.fleyx.jcloud.common.enums.ResultCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一响应体单元测试。
 */
class RTest {

    @Test
    void okWithoutDataShouldReturnSuccess() {
        R<Void> result = R.ok();
        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertTrue(result.isSuccess());
    }

    @Test
    void okWithDataShouldReturnData() {
        R<String> result = R.ok("hello");
        assertEquals("hello", result.getData());
        assertTrue(result.isSuccess());
    }

    @Test
    void failWithDefaultMessageShouldReturnBusinessError() {
        R<Void> result = R.fail();
        assertEquals(ResultCode.BUSINESS_ERROR.getCode(), result.getCode());
        assertNotNull(result.getMsg());
        assertFalse(result.isSuccess());
    }

    @Test
    void failWithCustomMessageShouldReturnGivenMessage() {
        R<Void> result = R.fail("自定义错误");
        assertEquals("自定义错误", result.getMsg());
        assertNull(result.getData());
    }

    @Test
    void failWithResultCodeShouldUseCodeAndMsg() {
        R<Void> result = R.fail(ResultCode.PARAM_ERROR);
        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertEquals(ResultCode.PARAM_ERROR.getMsg(), result.getMsg());
    }
}
