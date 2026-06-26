package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.enums.FileZipTaskStatus;
import com.fleyx.jcloud.common.enums.ResultCode;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.mapper.StorageSpaceMapper;
import com.fleyx.jcloud.model.bo.BatchDownloadResult;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.bo.FileZipTask;
import com.fleyx.jcloud.model.dto.FileBatchDownloadDto;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.model.po.StorageSpace;
import com.fleyx.jcloud.service.FileDownloadService;
import com.fleyx.jcloud.service.SystemStorageSpaceProvider;
import com.fleyx.jcloud.util.FilePathUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 批量下载服务实现。
 */
@Service
public class FileDownloadServiceImpl implements FileDownloadService {

    private static final String TYPE_FOLDER = "folder";
    private static final String TYPE_FILE = "file";
    private static final String ZIP_FILE_NAME = "archive.zip";

    private final FileMapper fileMapper;
    private final StorageSpaceMapper storageSpaceMapper;
    private final TaskExecutor taskExecutor;
    private final SystemStorageSpaceProvider systemStorageSpaceProvider;

    public FileDownloadServiceImpl(FileMapper fileMapper, StorageSpaceMapper storageSpaceMapper,
                                   @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                                   SystemStorageSpaceProvider systemStorageSpaceProvider) {
        this.fileMapper = fileMapper;
        this.storageSpaceMapper = storageSpaceMapper;
        this.taskExecutor = taskExecutor;
        this.systemStorageSpaceProvider = systemStorageSpaceProvider;
    }

    private final ConcurrentHashMap<String, FileZipTask> taskStore = new ConcurrentHashMap<>();

    private static final String ZIP_TASKS_SUB_DIRECTORY = "zip-tasks";

    @Value("${jcloud.download.zip-stream-threshold-size:104857600}")
    private long streamThresholdSize;

    @Value("${jcloud.download.zip-stream-threshold-count:100}")
    private int streamThresholdCount;

    @Value("${jcloud.download.zip-task-ttl-minutes:60}")
    private long taskTtlMinutes;

    @Override
    public BatchDownloadResult downloadBatch(FileBatchDownloadDto dto, Long userId) {
        if (dto == null || CollectionUtils.isEmpty(dto.getIds())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "下载节点 ID 不能为空");
        }

        List<FileNode> files = collectDownloadFiles(dto.getIds(), userId);
        if (files.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "没有可下载的文件");
        }

        long totalSize = files.stream().mapToLong(n -> n.getSize() == null ? 0L : n.getSize()).sum();
        if (totalSize <= streamThresholdSize && files.size() <= streamThresholdCount) {
            return buildSyncZip(files, totalSize);
        }

        String taskId = UUID.randomUUID().toString();
        Path taskDir = resolveTaskDir(userId, taskId);
        Path zipPath = taskDir.resolve(ZIP_FILE_NAME);
        FileZipTask pendingTask = new FileZipTask(taskId, userId, FileZipTaskStatus.PENDING, zipPath, totalSize, null, Instant.now());
        taskStore.put(taskId, pendingTask);

        taskExecutor.execute(() -> buildZipAsync(taskId, files, zipPath));
        FileZipTask task = taskStore.get(taskId);
        return new BatchDownloadResult.TaskResult(taskId, task.status());
    }

    @Override
    public FileZipTask getTask(String taskId, Long userId) {
        FileZipTask task = taskStore.get(taskId);
        if (task == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "任务不存在");
        }
        if (!task.userId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该任务");
        }
        return task;
    }

    @Override
    public FileDownloadResult downloadTaskResult(String taskId, Long userId) {
        FileZipTask task = getTask(taskId, userId);
        if (task.status() != FileZipTaskStatus.COMPLETED) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "任务尚未完成");
        }
        try {
            InputStream inputStream = Files.newInputStream(task.zipPath());
            return new FileDownloadResult(ZIP_FILE_NAME, inputStream, MediaType.APPLICATION_OCTET_STREAM_VALUE, Files.size(task.zipPath()));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "ZIP 文件读取失败");
        }
    }

    private List<FileNode> collectDownloadFiles(List<Long> ids, Long userId) {
        List<FileNode> result = new ArrayList<>();
        for (Long id : ids) {
            FileNode node = fileMapper.selectById(id);
            if (node == null || !userId.equals(node.getUserId()) || node.getDeleteAt() != 0L) {
                continue;
            }
            if (TYPE_FILE.equals(node.getType())) {
                result.add(node);
            } else if (TYPE_FOLDER.equals(node.getType())) {
                List<FileNode> descendants = fileMapper.selectFilesByPathNamePrefix(userId, node.getPathName());
                for (FileNode descendant : descendants) {
                    if (!descendant.getId().equals(node.getId())) {
                        result.add(descendant);
                    }
                }
            }
        }
        return result;
    }

    private BatchDownloadResult buildSyncZip(List<FileNode> files, long totalSize) {
        try {
            Path tempFile = Files.createTempFile("jcloud-batch-", ".zip");
            writeZip(files, tempFile);
            InputStream inputStream = Files.newInputStream(tempFile);
            return new BatchDownloadResult.StreamResult(ZIP_FILE_NAME, inputStream, Files.size(tempFile));
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "ZIP 打包失败");
        }
    }

    private void buildZipAsync(String taskId, List<FileNode> files, Path zipPath) {
        updateTaskStatus(taskId, FileZipTaskStatus.RUNNING, null);
        try {
            Files.createDirectories(zipPath.getParent());
            writeZip(files, zipPath);
            updateTaskStatus(taskId, FileZipTaskStatus.COMPLETED, null);
        } catch (Exception e) {
            updateTaskStatus(taskId, FileZipTaskStatus.FAILED, e.getMessage());
        }
    }

    private void writeZip(List<FileNode> files, Path zipPath) throws IOException {
        try (OutputStream os = Files.newOutputStream(zipPath);
             ZipOutputStream zos = new ZipOutputStream(os)) {
            for (FileNode file : files) {
                StorageSpace space = storageSpaceMapper.selectById(file.getStorageSpaceId());
                if (space == null) {
                    throw new BusinessException(ResultCode.NOT_FOUND, "存储空间不存在");
                }
                Path physicalPath = FilePathUtil.resolvePhysicalPath(file, space);
                if (!Files.exists(physicalPath)) {
                    throw new BusinessException(ResultCode.NOT_FOUND, "文件已丢失: " + file.getName());
                }
                String entryName = resolveEntryName(file);
                ZipEntry entry = new ZipEntry(entryName);
                entry.setSize(file.getSize() == null ? 0L : file.getSize());
                zos.putNextEntry(entry);
                Files.copy(physicalPath, zos);
                zos.closeEntry();
            }
            zos.finish();
        }
    }

    private String resolveEntryName(FileNode file) {
        return FilePathUtil.stripLeadingSlash(file.getPathName());
    }

    private void updateTaskStatus(String taskId, FileZipTaskStatus status, String message) {
        FileZipTask current = taskStore.get(taskId);
        if (current == null) {
            return;
        }
        taskStore.put(taskId, new FileZipTask(
                current.taskId(), current.userId(), status, current.zipPath(), current.totalBytes(), message, current.createdAt()));
    }

    private Path resolveTaskDir(Long userId, String taskId) {
        StorageSpace systemSpace = systemStorageSpaceProvider.getSystemSpace();
        return Path.of(systemSpace.getPath(), ZIP_TASKS_SUB_DIRECTORY, userId.toString(), taskId);
    }

    @Scheduled(fixedRate = 300_000)
    public void cleanupExpiredTasks() {
        Instant deadline = Instant.now().minus(taskTtlMinutes, ChronoUnit.MINUTES);
        taskStore.entrySet().removeIf(entry -> {
            FileZipTask task = entry.getValue();
            if (task.createdAt().isBefore(deadline)) {
                deleteQuietly(task.zipPath());
                return true;
            }
            return false;
        });
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted((a, b) -> -a.compareTo(b))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                }
            } else {
                Files.deleteIfExists(path);
                if (path.getParent() != null) {
                    Files.deleteIfExists(path.getParent());
                }
            }
        } catch (IOException ignored) {
        }
    }
}
