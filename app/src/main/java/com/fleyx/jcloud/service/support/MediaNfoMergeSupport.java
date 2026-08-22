package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.model.po.MediaMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.InputSource;

/**
 * 媒体 NFO 合并写支撑（从 {@link MediaNfoSupport} 拆分规避 400 行上限）。
 * <p>
 * 管理字段（tmdbid/title/originaltitle/plot/year/premiered/rating + 集的 season/episode）的
 * 「标签名 + 值」装配统一收敛于 {@link #managedScalarFields}，生成（{@link #generate}）与
 * 合并（{@link #mergeNfo}）均复用，消除两处字段清单漂移。
 * 合并语义（ADR 0033）：管理字段以 jcloud 为准覆盖；同名元素多个时更新第一个、删除其余收敛为单值；
 * 管理字段值为 null 时删除既有同名元素（对齐 genre 整体替换语义）；其余未知元素保留。
 */
@Slf4j
@Component
public class MediaNfoMergeSupport {

    /**
     * 从新模型元数据生成 Jellyfin/Kodi 兼容 NFO XML（issue #20/#21）。
     *
     * @return NFO XML 字符串
     */
    public String generate(MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        return generateXml(metadata, seasonNo, episodeNo);
    }

    /**
     * 合并写回 NFO（ADR 0033）：管理字段覆盖、genre 整体替换，其余元素保留；空/解析失败/根元素不符回退整体重写。
     */
    public String mergeNfo(String existingXml, MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        String mediaType = expectedMediaType(metadata);
        Element root = (existingXml == null || existingXml.isBlank()) ? null : parseRoot(existingXml);
        if (root == null) {
            return generate(metadata, seasonNo, episodeNo);
        }
        if (!expectedRootTag(mediaType).equals(root.getTagName())) {
            log.warn("NFO 根元素不符，回退整体重写: {}", root.getTagName());
            return generate(metadata, seasonNo, episodeNo);
        }
        Document doc = root.getOwnerDocument();
        for (NfoField field : managedScalarFields(metadata, seasonNo, episodeNo)) {
            applyScalar(doc, root, field);
        }
        replaceGenres(doc, root, metadata.getGenres());
        return serialize(doc);
    }

    private String expectedMediaType(MediaMetadata metadata) {
        MediaMetadataOwnerType ownerType = MediaMetadataOwnerType.of(metadata.getOwnerType());
        return ownerType == null ? "movie" : switch (ownerType) {
            case SERIES -> "tv";
            case EPISODE -> "episode";
            default -> "movie";
        };
    }

    private String expectedRootTag(String mediaType) {
        return switch (mediaType) {
            case "tv" -> "tvshow";
            case "episode" -> "episodedetails";
            default -> "movie";
        };
    }

    private String generateXml(MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        String rootTag = expectedRootTag(expectedMediaType(metadata));
        Document doc = newDocument();
        Element root = doc.createElement(rootTag);
        doc.appendChild(root);
        for (NfoField field : managedScalarFields(metadata, seasonNo, episodeNo)) {
            append(doc, root, field.tag(), field.value());
        }
        appendGenres(doc, root, metadata.getGenres());
        return serialize(doc);
    }

    /**
     * 管理字段的「标签名 + 值」装配（generateXml 与 mergeNfo 共用同一有序清单，消除两处字段漂移）。
     * releaseDate 派生 year/premiered；releaseDate 为 null 时以 null 值占位——
     * generateXml 跳过不写、mergeNfo 据此删除既有旧元素。
     */
    private record NfoField(String tag, Object value) {
    }

    private List<NfoField> managedScalarFields(MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        List<NfoField> fields = new ArrayList<>();
        fields.add(new NfoField("tmdbid", metadata.getTmdbId()));
        fields.add(new NfoField("title", metadata.getTitle()));
        fields.add(new NfoField("originaltitle", metadata.getOriginalTitle()));
        fields.add(new NfoField("plot", metadata.getOverview()));
        String releaseDate = metadata.getReleaseDate();
        fields.add(new NfoField("year", releaseDate != null
                ? (releaseDate.length() >= 4 ? releaseDate.substring(0, 4) : releaseDate) : null));
        fields.add(new NfoField("premiered", releaseDate));
        fields.add(new NfoField("rating", metadata.getVoteAverage()));
        if ("episode".equals(expectedMediaType(metadata))) {
            fields.add(new NfoField("season", seasonNo));
            fields.add(new NfoField("episode", episodeNo));
        }
        return fields;
    }

    /**
     * 合并写标量：值非空时更新第一个同名元素并删除其余（收敛为单值）；缺失则补充；
     * 值为 null 时删除既有同名元素（对齐「jcloud 为准覆盖」与 genre 整体替换语义）。
     */
    private void applyScalar(Document doc, Element root, NfoField field) {
        NodeList nodes = root.getElementsByTagName(field.tag());
        if (field.value() == null) {
            for (int i = nodes.getLength() - 1; i >= 0; i--) {
                root.removeChild(nodes.item(i));
            }
            return;
        }
        if (nodes.getLength() == 0) {
            append(doc, root, field.tag(), field.value());
            return;
        }
        Element first = (Element) nodes.item(0);
        first.setTextContent(String.valueOf(field.value()));
        for (int i = nodes.getLength() - 1; i > 0; i--) {
            root.removeChild(nodes.item(i));
        }
    }

    private void append(Document doc, Element parent, String tag, Object value) {
        if (value == null) {
            return;
        }
        Element element = doc.createElement(tag);
        element.setTextContent(String.valueOf(value));
        parent.appendChild(element);
    }

    private void replaceGenres(Document doc, Element root, String genres) {
        NodeList nodes = root.getElementsByTagName("genre");
        for (int i = nodes.getLength() - 1; i >= 0; i--) {
            root.removeChild(nodes.item(i));
        }
        if (genres != null && !genres.isBlank()) {
            for (String genre : genres.split(",")) {
                if (!genre.isBlank()) {
                    append(doc, root, "genre", genre.trim());
                }
            }
        }
    }

    private void appendGenres(Document doc, Element root, String genres) {
        if (genres != null && !genres.isBlank()) {
            for (String genre : genres.split(",")) {
                append(doc, root, "genre", genre.isBlank() ? null : genre.trim());
            }
        }
    }

    private Document newDocument() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException("NFO 生成失败", e);
        }
    }

    private Element parseRoot(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(StrUtil.removePrefix(xml, "\uFEFF")))).getDocumentElement();
        } catch (Exception e) {
            log.debug("NFO 合并解析失败，回退整体重写: {}", e.getMessage());
            return null;
        }
    }

    private String serialize(Document doc) {
        try {
            StringWriter writer = new StringWriter();
            TransformerFactory.newInstance().newTransformer()
                    .transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("NFO 序列化失败", e);
        }
    }
}
