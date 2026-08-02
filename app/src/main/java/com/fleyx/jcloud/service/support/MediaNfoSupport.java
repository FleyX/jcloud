package com.fleyx.jcloud.service.support;

import com.fleyx.jcloud.common.enums.MediaMetadataOwnerType;
import com.fleyx.jcloud.model.po.MediaMetadata;
import lombok.RequiredArgsConstructor;
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
 * 媒体 NFO 支撑组件：Jellyfin/Kodi 兼容 NFO 的解析、生成与本地来源元数据落库。
 * <p>
 * 命名约定（ADR 0020）：电影/集为与视频同名的 {@code .nfo}，剧文件夹为 {@code tvshow.nfo}；
 * 图片为 {@code poster.jpg}、{@code fanart.jpg}、{@code seasonXX-poster.jpg}、集剧照 {@code <视频名>-thumb.jpg}。
 * 解析容错：非法 XML 返回 null，缺字段返回部分解析结果，均不抛业务异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaNfoSupport {

    /**
     * 剧级 NFO 文件名。
     */
    public static final String TVSHOW_NFO = "tvshow.nfo";

    /**
     * 海报图片文件名。
     */
    public static final String POSTER_JPG = "poster.jpg";

    /**
     * 背景图片文件名。
     */
    public static final String FANART_JPG = "fanart.jpg";

    private static final String NFO_MIME = "application/xml";

    /**
     * NFO 解析结果。
     *
     * @param mediaType     类型：movie / tv / episode（对应根元素 movie/tvshow/episodedetails）
     * @param title         标题
     * @param originalTitle 原始标题
     * @param overview      简介（plot）
     * @param releaseDate   上映/首播日期（premiered/releasedate 优先，其次 year）
     * @param voteAverage   评分（rating）
     * @param genres        类型列表，逗号分隔
     * @param tmdbId        TMDB ID（tmdbid），可为空
     * @param seasonNo      季号（仅集）
     * @param episodeNo     集号（仅集）
     */
    public record NfoData(String mediaType, String title, String originalTitle, String overview,
                          String releaseDate, Double voteAverage, String genres, Long tmdbId,
                          Integer seasonNo, Integer episodeNo) {
    }

    /**
     * 构造全字段为空的 NFO 解析结果（目录仅有本地图片、无 NFO 时使用），
     * 用于构建缺失文本字段的 local_nfo 元数据，完整性标记为不完整。
     *
     * @param mediaType 类型：movie / tv / episode
     * @return 全字段为空的解析结果
     */
    public static NfoData emptyData(String mediaType) {
        return new NfoData(mediaType, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 视频文件对应的 NFO 文件名（主文件名 + .nfo）。
     */
    public String nfoNameOf(String videoName) {
        return mainNameOf(videoName) + ".nfo";
    }

    /**
     * 集剧照文件名（主文件名 + -thumb.jpg）。
     */
    public String episodeThumbNameOf(String videoName) {
        return mainNameOf(videoName) + "-thumb.jpg";
    }

    /**
     * 季海报文件名（seasonXX-poster.jpg，季号两位补齐）。
     */
    public String seasonPosterName(Integer seasonNo) {
        return String.format("season%02d-poster.jpg", seasonNo);
    }

    /**
     * 视频主文件名（去扩展名）。
     */
    public String mainNameOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        return idx <= 0 ? fileName : fileName.substring(0, idx);
    }

    /**
     * NFO 文件的 MIME 类型。
     */
    public String nfoMimeType() {
        return NFO_MIME;
    }

    /**
     * 解析 NFO XML，非法 XML 返回 null，缺字段返回部分结果。
     *
     * @param xml NFO 内容
     * @return 解析结果或 null
     */
    public NfoData parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            Element root = doc.getDocumentElement();
            String mediaType = switch (root.getTagName()) {
                case "movie" -> "movie";
                case "tvshow" -> "tv";
                case "episodedetails" -> "episode";
                default -> null;
            };
            if (mediaType == null) {
                log.warn("NFO 根元素不支持: {}", root.getTagName());
                return null;
            }
            String releaseDate = firstText(root, "premiered", "releasedate");
            if (releaseDate == null) {
                releaseDate = text(root, "year");
            }
            List<String> genres = texts(root, "genre");
            return new NfoData(mediaType, text(root, "title"), text(root, "originaltitle"),
                    text(root, "plot"), releaseDate, doubleValue(text(root, "rating")),
                    genres.isEmpty() ? null : String.join(",", genres), longValue(text(root, "tmdbid")),
                    intValue(text(root, "season")), intValue(text(root, "episode")));
        } catch (Exception e) {
            log.warn("NFO 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从新模型元数据生成 Jellyfin/Kodi 兼容 NFO XML（issue #20/#21）。
     * 根元素按 owner_type 派生：series→tvshow / episode→episodedetails / 其余→movie。
     *
     * @param metadata 元数据行（已绑定 owner）
     * @param seasonNo 季号，仅集有效，可为空
     * @param episodeNo 集号，仅集有效，可为空
     * @return NFO XML 字符串
     */
    public String generate(MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        // owner_type 未知编码时回退 movie 根元素（of 空安全，issue #21 收尾）
        MediaMetadataOwnerType ownerType = MediaMetadataOwnerType.of(metadata.getOwnerType());
        String mediaType = ownerType == null ? "movie" : switch (ownerType) {
            case SERIES -> "tv";
            case EPISODE -> "episode";
            default -> "movie";
        };
        return generateXml(mediaType, metadata.getTmdbId(), metadata.getTitle(),
                metadata.getOriginalTitle(), metadata.getOverview(), metadata.getReleaseDate(),
                metadata.getVoteAverage(), metadata.getGenres(), seasonNo, episodeNo);
    }

    /**
     * 按字段生成 Jellyfin/Kodi 兼容 NFO XML（movie/tvshow/episodedetails 根元素按 mediaType 派生）。
     */
    private String generateXml(String mediaType, Long tmdbId, String title, String originalTitle,
                               String overview, String releaseDate, Double voteAverage, String genres,
                               Integer seasonNo, Integer episodeNo) {
        String rootTag = switch (mediaType) {
            case "tv" -> "tvshow";
            case "episode" -> "episodedetails";
            default -> "movie";
        };
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement(rootTag);
            doc.appendChild(root);
            append(doc, root, "tmdbid", tmdbId);
            append(doc, root, "title", title);
            append(doc, root, "originaltitle", originalTitle);
            append(doc, root, "plot", overview);
            if (releaseDate != null) {
                append(doc, root, "year", releaseDate.length() >= 4
                        ? releaseDate.substring(0, 4) : releaseDate);
                append(doc, root, "premiered", releaseDate);
            }
            append(doc, root, "rating", voteAverage);
            if (genres != null && !genres.isBlank()) {
                for (String genre : genres.split(",")) {
                    append(doc, root, "genre", genre.isBlank() ? null : genre.trim());
                }
            }
            if ("episode".equals(mediaType)) {
                append(doc, root, "season", seasonNo);
                append(doc, root, "episode", episodeNo);
            }
            StringWriter writer = new StringWriter();
            TransformerFactory.newInstance().newTransformer()
                    .transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("NFO 生成失败", e);
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

    private String text(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstText(Element root, String... tags) {
        for (String tag : tags) {
            String value = text(root, tag);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private List<String> texts(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent();
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
        }
        return result;
    }

    private Double doubleValue(String value) {
        try {
            return value == null ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intValue(String value) {
        try {
            return value == null ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
