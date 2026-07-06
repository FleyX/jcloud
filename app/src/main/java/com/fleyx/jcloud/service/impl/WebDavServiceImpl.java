package com.fleyx.jcloud.service.impl;

import com.fleyx.jcloud.common.constant.FileNodeConstants;
import com.fleyx.jcloud.common.context.UserContext;
import com.fleyx.jcloud.common.exception.BusinessException;
import com.fleyx.jcloud.common.exception.WebDavException;
import com.fleyx.jcloud.mapper.FileMapper;
import com.fleyx.jcloud.model.bo.FileDownloadResult;
import com.fleyx.jcloud.model.po.FileNode;
import com.fleyx.jcloud.service.FileService;
import com.fleyx.jcloud.service.WebDavLockService;
import com.fleyx.jcloud.service.WebDavService;
import com.fleyx.jcloud.util.FileConflictHelper;
import com.fleyx.jcloud.util.UserReadOnlyChecker;
import com.fleyx.jcloud.util.UserReadWriteLock;
import com.fleyx.jcloud.util.WebDavPathResolver;
import com.fleyx.jcloud.util.WebDavPropFindBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * WebDAV 请求处理服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebDavServiceImpl implements WebDavService {

    private static final String TYPE_FILE = "file";
    private static final String TYPE_FOLDER = "folder";
    private static final String HEADER_DESTINATION = "Destination";
    private static final String HEADER_DEPTH = "Depth";
    private static final String HEADER_OVERWRITE = "Overwrite";
    private static final String HEADER_LOCK_TOKEN = "Lock-Token";

    private final FileMapper fileMapper;
    private final FileService fileService;
    private final WebDavLockService webDavLockService;
    private final WebDavPathResolver pathResolver;
    private final WebDavFileOperationHelper operationHelper;
    private final UserReadWriteLock userReadWriteLock;
    private final UserReadOnlyChecker userReadOnlyChecker;

    @Override
    public void handle(String userCode, HttpServletRequest request, HttpServletResponse response) {
        String method = request.getMethod();
        String path = pathResolver.extractDavPath(request.getRequestURI(), userCode);
        String userId = UserContext.requireCurrentUser().id();
        log.debug("WebDAV {} {}", method, path);
        try {
            switch (method.toUpperCase()) {
                case "OPTIONS" -> doOptions(response);
                case "PROPFIND" -> doPropFind(userId, userCode, path, request, response);
                case "GET" -> doGet(userId, path, response);
                case "HEAD" -> doHead(userId, path, response);
                case "PUT" -> doPut(userId, path, request, response);
                case "MKCOL" -> doMkCol(userId, path, response);
                case "DELETE" -> doDelete(userId, path, response);
                case "MOVE" -> doMoveOrCopy(userId, userCode, path, request, response, true);
                case "COPY" -> doMoveOrCopy(userId, userCode, path, request, response, false);
                case "LOCK" -> doLock(userId, userCode, path, response);
                case "UNLOCK" -> doUnlock(userId, path, request, response);
                default -> response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        } catch (WebDavException e) {
            sendError(response, e.getStatusCode(), e.getMessage());
        } catch (BusinessException e) {
            log.debug("WebDAV 业务异常: {}", e.getMessage());
            sendError(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        } catch (Exception e) {
            log.error("WebDAV 处理异常", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal Server Error");
        }
    }

    private void doOptions(HttpServletResponse response) {
        response.setHeader("DAV", "1, 2");
        response.setHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, COPY, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK");
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private void doPropFind(String userId, String userCode, String path,
                            HttpServletRequest request, HttpServletResponse response) throws IOException {
        FileNode node = pathResolver.resolveNode(userId, path);
        if (node == null) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Not Found");
            return;
        }
        List<FileNode> nodes = new ArrayList<>();
        nodes.add(node);
        if (TYPE_FOLDER.equals(node.getType()) && !"0".equals(request.getHeader(HEADER_DEPTH))) {
            nodes.addAll(fileMapper.selectByParentId(userId, node.getId()));
        }
        String requestUrl = request.getRequestURL().toString();
        if (!requestUrl.endsWith("/") && TYPE_FOLDER.equals(node.getType())) {
            requestUrl += "/";
        }
        String xml = WebDavPropFindBuilder.build(requestUrl, nodes, path);
        response.setStatus(207);
        response.setContentType("text/xml; charset=UTF-8");
        response.getWriter().write(xml);
    }

    private void doGet(String userId, String path, HttpServletResponse response) throws IOException {
        FileNode node = pathResolver.resolveNode(userId, path);
        if (node == null || TYPE_FOLDER.equals(node.getType())) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Not Found");
            return;
        }
        FileDownloadResult result = fileService.download(node.getId(), userId);
        response.setContentType(StringUtils.hasText(result.getContentType()) ? result.getContentType() : "application/octet-stream");
        if (result.getSize() != null) {
            response.setContentLengthLong(result.getSize());
        }
        try (InputStream in = result.getInputStream(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    private void doHead(String userId, String path, HttpServletResponse response) {
        FileNode node = pathResolver.resolveNode(userId, path);
        if (node == null) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Not Found");
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        if (!TYPE_FOLDER.equals(node.getType())) {
            response.setContentLengthLong(node.getSize() == null ? 0 : node.getSize());
        }
    }

    private void doPut(String userId, String path, HttpServletRequest request, HttpServletResponse response) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        if (path.isEmpty() || path.endsWith("/")) {
            sendError(response, HttpServletResponse.SC_CONFLICT, "Cannot PUT to collection");
            return;
        }
        WebDavPathResolver.ParentName parentName = pathResolver.resolveParentAndName(userId, path);
        if (parentName.parent() == null) {
            sendError(response, HttpServletResponse.SC_CONFLICT, "Parent does not exist");
            return;
        }
        validateNotRemoteTarget(parentName.parent(), "upload");
        long size = request.getContentLengthLong();
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            operationHelper.upload(userId, parentName.parent(), parentName.name(), request, size);
            response.setStatus(HttpServletResponse.SC_CREATED);
        } finally {
            lock.unlock();
        }
    }

    private void doMkCol(String userId, String path, HttpServletResponse response) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        if (path.isEmpty() || path.endsWith("/")) {
            sendError(response, HttpServletResponse.SC_CONFLICT, "Invalid folder path");
            return;
        }
        WebDavPathResolver.ParentName parentName = pathResolver.resolveParentAndName(userId, path);
        if (parentName.parent() == null) {
            sendError(response, HttpServletResponse.SC_CONFLICT, "Parent does not exist");
            return;
        }
        validateNotRemoteTarget(parentName.parent(), "create folder");
        if (FileConflictHelper.existsSameName(fileMapper, userId, parentName.parent().getId(), parentName.name(), null)) {
            sendError(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Already exists");
            return;
        }
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            operationHelper.createFolder(userId, parentName.parent().getId(), parentName.name());
            response.setStatus(HttpServletResponse.SC_CREATED);
        } finally {
            lock.unlock();
        }
    }

    private void doDelete(String userId, String path, HttpServletResponse response) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        if (path.isEmpty()) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "Cannot delete root");
            return;
        }
        FileNode node = pathResolver.resolveNode(userId, path);
        if (node == null) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Not Found");
            return;
        }
        validateNotRemoteSourceForDelete(node);
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            operationHelper.delete(userId, node);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } finally {
            lock.unlock();
        }
    }

    private void doMoveOrCopy(String userId, String userCode, String path, HttpServletRequest request,
                              HttpServletResponse response, boolean isMove) {
        userReadOnlyChecker.checkWriteAllowed(userId);
        if (path.isEmpty()) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "Cannot move/copy root");
            return;
        }
        String destination = request.getHeader(HEADER_DESTINATION);
        if (!StringUtils.hasText(destination)) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing Destination");
            return;
        }
        FileNode source = pathResolver.resolveNode(userId, path);
        if (source == null) {
            sendError(response, HttpServletResponse.SC_NOT_FOUND, "Not Found");
            return;
        }
        String destPath = pathResolver.extractDavPath(pathResolver.extractDestinationPath(destination), userCode);
        WebDavPathResolver.ParentName dest = pathResolver.resolveParentAndName(userId, destPath);
        if (dest.parent() == null) {
            sendError(response, HttpServletResponse.SC_CONFLICT, "Destination parent does not exist");
            return;
        }
        validateSameSourceType(source, dest.parent());
        boolean overwrite = !"F".equalsIgnoreCase(request.getHeader(HEADER_OVERWRITE));
        RLock lock = userReadWriteLock.writeLock(userId);
        lock.lock();
        try {
            executeMoveOrCopy(userId, source, dest.parent(), dest.name(), isMove, overwrite, response);
        } finally {
            lock.unlock();
        }
    }

    private void executeMoveOrCopy(String userId, FileNode source, FileNode targetParent,
                                   String targetName, boolean isMove, boolean overwrite,
                                   HttpServletResponse response) {
        FileNode existing = FileConflictHelper.findSameName(fileMapper, userId, targetParent.getId(), targetName);
        if (existing != null) {
            if (!overwrite) {
                sendError(response, HttpServletResponse.SC_PRECONDITION_FAILED, "Already exists");
                return;
            }
            operationHelper.delete(userId, existing);
        }
        if (isMove) {
            operationHelper.move(userId, source, targetParent, targetName);
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else {
            operationHelper.copy(userId, source, targetParent, targetName);
            response.setStatus(HttpServletResponse.SC_CREATED);
        }
    }

    private void doLock(String userId, String userCode, String path, HttpServletResponse response) throws IOException {
        String token = webDavLockService.lock(userId, userCode + "/" + path);
        String xml = webDavLockService.buildLockDiscovery("/dav/" + userCode + "/" + path, token);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/xml; charset=UTF-8");
        response.setHeader("Lock-Token", "<opaquelocktoken:" + token + ">");
        response.getWriter().write(xml);
    }

    private void doUnlock(String userId, String path, HttpServletRequest request, HttpServletResponse response) {
        String token = request.getHeader(HEADER_LOCK_TOKEN);
        if (token != null && token.startsWith("<opaquelocktoken:") && token.endsWith(">")) {
            token = token.substring("<opaquelocktoken:".length(), token.length() - 1);
        }
        String userCode = UserContext.requireUserCode();
        if (webDavLockService.unlock(userId, userCode + "/" + path, token)) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else {
            sendError(response, 423, "Locked");
        }
    }

    private void validateNotRemoteTarget(FileNode parent, String action) {
        if (FileNodeConstants.SOURCE_REMOTE.equals(parent.getSourceType())) {
            throw new BusinessException("不能跨本地与远程目录" + action);
        }
    }

    private void validateNotRemoteSourceForDelete(FileNode node) {
        if (FileNodeConstants.SOURCE_REMOTE.equals(node.getSourceType())) {
            throw new BusinessException("远程文件需通过远程挂载管理删除");
        }
    }

    private void validateSameSourceType(FileNode source, FileNode targetParent) {
        String targetSource = targetParent.getSourceType() == null
                ? FileNodeConstants.SOURCE_LOCAL : targetParent.getSourceType();
        String sourceSource = source.getSourceType() == null
                ? FileNodeConstants.SOURCE_LOCAL : source.getSourceType();
        if (!targetSource.equals(sourceSource)) {
            throw new BusinessException("不能跨本地与远程目录操作");
        }
        if (FileNodeConstants.SOURCE_REMOTE.equals(sourceSource)
                && !java.util.Objects.equals(source.getRemoteMountId(), targetParent.getRemoteMountId())) {
            throw new BusinessException("不能跨本地与远程目录操作");
        }
    }

    private void sendError(HttpServletResponse response, int status, String message) {
        try {
            if (!response.isCommitted()) {
                response.sendError(status, message);
            }
        } catch (IOException e) {
            log.warn("发送 WebDAV 错误响应失败", e);
        }
    }
}
