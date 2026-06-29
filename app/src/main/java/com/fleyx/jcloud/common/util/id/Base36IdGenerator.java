package com.fleyx.jcloud.common.util.id;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;

/**
 * 定长 base36 ID 生成器。
 * <p>
 * 内部复用 MyBatis-Plus 默认雪花算法生成 long，再转换为小写、左补零的 13 位 base36 字符串。
 */
public class Base36IdGenerator implements IdentifierGenerator {

    private final DefaultIdentifierGenerator delegate;

    public Base36IdGenerator() {
        this.delegate = new DefaultIdentifierGenerator();
    }

    @Override
    public Base36Number nextId(Object entity) {
        return Base36Number.of(delegate.nextId(entity));
    }
}
