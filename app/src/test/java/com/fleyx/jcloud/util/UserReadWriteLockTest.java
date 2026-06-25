package com.fleyx.jcloud.util;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户级 Redisson 读写锁测试。
 */
@SpringBootTest
@ActiveProfiles("test")
class UserReadWriteLockTest {

    @Autowired
    private UserReadWriteLock userReadWriteLock;

    @Test
    void writeLockShouldBeReentrant() throws InterruptedException {
        RLock lock = userReadWriteLock.writeLock(1L);
        assertTrue(lock.tryLock(1, TimeUnit.SECONDS));
        try {
            assertTrue(lock.tryLock(1, TimeUnit.SECONDS));
            lock.unlock();
        } finally {
            lock.unlock();
        }
    }

    @Test
    void writeLocksForSameUserShouldBeMutuallyExclusive() throws InterruptedException {
        long userId = 2L;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger(0);

        RLock firstLock = userReadWriteLock.writeLock(userId);
        firstLock.lock();
        try {
            Thread t = new Thread(() -> {
                RLock secondLock = userReadWriteLock.writeLock(userId);
                boolean acquired = secondLock.tryLock();
                if (acquired) {
                    try {
                        counter.incrementAndGet();
                    } finally {
                        secondLock.unlock();
                    }
                }
                latch.countDown();
            });
            t.start();
            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertEquals(0, counter.get(), "同一用户的写锁应互斥");
        } finally {
            firstLock.unlock();
        }
    }

    @Test
    void readLocksForSameUserShouldBeConcurrent() throws InterruptedException {
        long userId = 3L;
        CountDownLatch acquired = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger(0);

        Runnable holder = () -> {
            RLock lock = userReadWriteLock.readLock(userId);
            lock.lock();
            try {
                counter.incrementAndGet();
                acquired.countDown();
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            } finally {
                lock.unlock();
            }
        };

        new Thread(holder).start();
        new Thread(holder).start();

        assertTrue(acquired.await(2, TimeUnit.SECONDS));
        assertEquals(2, counter.get(), "同一用户的读锁应可并发");
        release.countDown();
    }

    @Test
    void locksForDifferentUsersShouldBeIndependent() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger(0);

        RLock lockA = userReadWriteLock.writeLock(4L);
        lockA.lock();
        try {
            Thread t = new Thread(() -> {
                RLock lockB = userReadWriteLock.writeLock(5L);
                if (lockB.tryLock()) {
                    try {
                        counter.incrementAndGet();
                    } finally {
                        lockB.unlock();
                    }
                }
                latch.countDown();
            });
            t.start();
            assertTrue(latch.await(1, TimeUnit.SECONDS));
            assertEquals(1, counter.get(), "不同用户的写锁应相互独立");
        } finally {
            lockA.unlock();
        }
    }
}
