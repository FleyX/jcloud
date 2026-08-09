package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMatchStatus;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.FileProbe;
import com.fleyx.jcloud.service.support.MediaScanDriverSupport.MediaScanContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 三型 reconcile 共享骨架驱动器（电视/电影/其他，ADR 0021 / issue #17/18/19）。
 * <p>
 * 电视/电影锚定行 upsert 助手（按 folder_node_id 定位、insert 初始化、renamed→resetMatch 含
 * MANUAL 保留与删元数据）与三型明细行 reconcile 循环（probe 命中/哈希判断/按锚全局改挂/
 * seen 累加）在此单一实现；类型差异（电视集并入语义 {@link MediaTvReconcileSupport#placeEpisodeFile}、
 * 其他哈希命中刷新 scan_time）通过 {@link RowReconciler} 钩子注入。本类不开启事务：
 * upsert 在调用方 {@code @Transactional} 方法内执行，Prepare 的可变集合语义（upsert 内向
 * existingFiles/episodeById 追加新建与改挂认领行，deleteUnseen 依赖之）由调用方传入的
 * 集合引用保留。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaReconcileDriverSupport {

    private final MediaCascadeDriverSupport mediaCascadeDriverSupport;

    /**
     * 锚定行 upsert 共享助手（电视剧行/电影行）：按 folder_node_id 定位存量行，未命中时
     * createAndInsert 完成初始化与插入；命中时计算 renamed→resetMatch（MANUAL 保留；
     * 非 manual 重置匹配并清元数据行），差异更新由 applyUpdate 完成。
     *
     * @param name               清理后的标题/剧名（改名判定基准）
     * @param releaseYear        解析出的发行年份（改名判定基准）
     * @param metadataOwnerType  实体元数据归属类型（{@link com.fleyx.jcloud.common.enums.MediaMetadataOwnerType}）
     * @param findExisting       按 folder_node_id 定位存量行
     * @param createAndInsert    新建行初始化 + 插入
     * @param applyUpdate        命中行差异更新（含 resetMatch 条件 set 与内存同步）
     */
    public <T> T upsertAnchoredRow(String name, Integer releaseYear, String metadataOwnerType,
                                   Supplier<T> findExisting,
                                   Supplier<T> createAndInsert,
                                   BiConsumer<T, Boolean> applyUpdate,
                                   Function<T, String> idGetter,
                                   Function<T, String> nameGetter,
                                   Function<T, Integer> yearGetter,
                                   Function<T, String> matchStatusGetter,
                                   Function<T, String> metadataIdGetter) {
        T row = findExisting.get();
        if (row == null) {
            return createAndInsert.get();
        }
        boolean renamed = !Objects.equals(nameGetter.apply(row), name)
                || !Objects.equals(yearGetter.apply(row), releaseYear);
        boolean resetMatch = renamed && !MediaMatchStatus.MANUAL.getCode().equals(matchStatusGetter.apply(row))
                && (!MediaMatchStatus.UNMATCHED.getCode().equals(matchStatusGetter.apply(row))
                || metadataIdGetter.apply(row) != null);
        if (resetMatch) {
            mediaCascadeDriverSupport.deleteMetadata(metadataOwnerType, idGetter.apply(row));
        }
        applyUpdate.accept(row, resetMatch);
        return row;
    }

    /**
     * 明细行 reconcile 钩子（三型差异注入点）。
     *
     * @param <T> 明细行类型（集文件/电影文件/other 行）
     */
    public interface RowReconciler<T> {

        /**
         * 行内文件变更哈希（与探测结果比对判定文件是否变化）。
         */
        String fileHash(T row);

        /**
         * 哈希命中且非强扫时，锚归属与解析结果是否一致（一致直接 seen 跳过；不一致继续走改挂，
         * 对应电视「哈希相同但锚归属与解析结果不一致（脏数据/同名路径迁移）」分支）。
         */
        boolean hashHitExtraOk(T row, MediaScanContext ctx, FileProbe fp);

        /**
         * 哈希命中跳过前执行的额外动作（其他库刷新批次扫描时间防批次清理误删；电视/电影空实现）。
         */
        default void hashHitExtraAction(MediaScanContext ctx, T row) {
        }

        /**
         * seen 累加（电视同时累加集行 ID）。
         */
        void markSeen(T row);

        /**
         * 本实体存量未命中时按锚全局查找（跨实体/跨来源移动的存量行，改挂认领）。
         */
        T findByFileNodeId(String fileNodeId);

        /**
         * 新建明细行（仅设置锚 file_node_id；其余字段与归属在 {@link #assignAndPersist} 完成）。
         */
        T newRow(FileNode file);

        /**
         * 归属与字段写入并持久化：解析目标（电视集并入语义）、写哈希/大小/修改时间/探测结果，
         * 新建行 insert、存量行 updateById。
         */
        void assignAndPersist(T row, MediaScanContext ctx, FileNode file, FileProbe fp);
    }

    /**
     * 明细行 reconcile 循环共享驱动：probe 命中 → 哈希判断（命中且锚归属一致直接 seen 跳过）→
     * 按锚全局改挂认领 → 新建 → 归属写入与持久化 → seen 累加。探测已在事务外预计算完成，
     * 此处只做 DB 读写；向调用方传入的存量集合（Prepare 可变语义）追加新建与改挂认领行。
     */
    public <T> void reconcileRow(MediaScanContext ctx, FileNode file,
                                 Map<String, FileProbe> probeByFileId,
                                 Map<String, T> rowByNodeId, List<T> existingRows,
                                 RowReconciler<T> hooks) {
        FileProbe fp = probeByFileId.get(file.getId());
        if (fp == null) {
            log.debug("文件未在预计算中命中，跳过: {}", file.getName());
            return;
        }
        T row = rowByNodeId.get(file.getId());
        if (row != null && !ctx.force() && Objects.equals(hooks.fileHash(row), fp.fileHash())
                && hooks.hashHitExtraOk(row, ctx, fp)) {
            hooks.hashHitExtraAction(ctx, row);
            hooks.markSeen(row);
            return;
        }
        if (row == null) {
            row = hooks.findByFileNodeId(file.getId());
            if (row != null) {
                existingRows.add(row);
                rowByNodeId.put(file.getId(), row);
            }
        }
        if (row == null) {
            row = hooks.newRow(file);
            existingRows.add(row);
            rowByNodeId.put(file.getId(), row);
        }
        hooks.assignAndPersist(row, ctx, file, fp);
        hooks.markSeen(row);
    }
}
