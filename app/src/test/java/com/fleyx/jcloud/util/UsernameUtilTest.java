package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UsernameUtil 单元测试。
 */
class UsernameUtilTest {

    @Test
    void shouldAcceptValidUsername() {
        assertTrue(UsernameUtil.isValid("alice_01"));
        assertTrue(UsernameUtil.isValid("user123"));
        assertTrue(UsernameUtil.isValid("abc_12"));
    }

    @Test
    void shouldAcceptAdminException() {
        assertTrue(UsernameUtil.isValid("admin"));
    }

    @Test
    void shouldRejectTooShortOrTooLong() {
        assertFalse(UsernameUtil.isValid("short"));
        assertFalse(UsernameUtil.isValid("a".repeat(33)));
    }

    @Test
    void shouldRejectUppercase() {
        assertFalse(UsernameUtil.isValid("Alice_01"));
    }

    @Test
    void shouldRejectInvalidCharacters() {
        assertFalse(UsernameUtil.isValid("alice-01"));
        assertFalse(UsernameUtil.isValid("alice.01"));
        assertFalse(UsernameUtil.isValid("alice/01"));
    }

    @Test
    void shouldRejectUnderscoreAtEdgeOrConsecutive() {
        assertFalse(UsernameUtil.isValid("_alice01"));
        assertFalse(UsernameUtil.isValid("alice01_"));
        assertFalse(UsernameUtil.isValid("alice__01"));
    }

    @Test
    void shouldRejectReservedNames() {
        assertFalse(UsernameUtil.isValid("files"));
        assertFalse(UsernameUtil.isValid("trash"));
        assertFalse(UsernameUtil.isValid("tmp"));
    }

    @Test
    void shouldNormalizeToLowercase() {
        assertEquals("alice", UsernameUtil.normalize("Alice"));
        assertEquals("alice", UsernameUtil.normalize("  Alice  "));
    }

    @Test
    void shouldRequireValidUsername() {
        assertEquals("alice_01", UsernameUtil.requireValid("Alice_01"));
        assertThrows(IllegalArgumentException.class, () -> UsernameUtil.requireValid("Alice-01"));
    }
}
