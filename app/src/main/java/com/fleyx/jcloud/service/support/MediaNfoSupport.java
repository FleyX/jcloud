package com.fleyx.jcloud.service.support;

import cn.hutool.core.util.StrUtil;
import com.fleyx.jcloud.model.po.MediaMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.xml.sax.InputSource;

/**
 * 媒体 NFO 支撑组件：Jellyfin/Kodi 兼容 NFO 的解析、生成与本地来源元数据落库。
 * <p>
 * 命名约定（ADR 0020）：电影识别优先 {@code movie.nfo}、回退与视频同名的 {@code .nfo}，写回统一
 * {@code movie.nfo}；集为与视频同名的 {@code .nfo}，剧文件夹为 {@code tvshow.nfo}。
 * 本地媒体图片命名（ADR 0022）：海报/背景按识别链取目录中第一个存在的文件——电影海报
 * {@code folder.jpg→poster.jpg→cover.jpg→default.jpg→movie.jpg}、剧集海报
 * {@code folder.jpg→poster.jpg→cover.jpg→default.jpg→show.jpg}、背景
 * {@code backdrop.jpg→fanart.jpg→background.jpg→art.jpg}；写回统一产出 {@code folder.jpg}/{@code backdrop.jpg}。
 * 季海报 {@code seasonXX-poster.jpg}、集剧照 {@code <视频名>-thumb.jpg} 命名不变。
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
     * 电影级 NFO 文件名（识别优先，写回统一）。
     */
    public static final String MOVIE_NFO = "movie.nfo";

    /**
     * 海报识别链（电影）：按序取目录中第一个存在的文件。
     */
    public static final List<String> MOVIE_POSTER_NAMES =
            List.of("folder.jpg", "poster.jpg", "cover.jpg", "default.jpg", "movie.jpg");

    /**
     * 海报识别链（剧集）。
     */
    public static final List<String> TV_POSTER_NAMES =
            List.of("folder.jpg", "poster.jpg", "cover.jpg", "default.jpg", "show.jpg");

    /**
     * 背景识别链（电影/剧集共用）。
     */
    public static final List<String> BACKDROP_NAMES =
            List.of("backdrop.jpg", "fanart.jpg", "background.jpg", "art.jpg");

    /**
     * 海报/背景写回文件名（均置于各自识别链首，保证写读自洽）。
     */
    public static final String POSTER_WRITE_NAME = "folder.jpg";
    public static final String BACKDROP_WRITE_NAME = "backdrop.jpg";

    private static final String NFO_MIME = "application/xml";

    private final MediaNfoMergeSupport mergeSupport;

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
        // 剥离前导 BOM（\uFEFF，Emby 等工具写出的 NFO 常见），否则解析器抛「前言中不允许有内容」
        xml = StrUtil.removePrefix(xml, "\uFEFF");
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
     * 实现下沉到 {@link MediaNfoMergeSupport}（生成与合并共用同一管理字段清单）。
     *
     * @param metadata 元数据行（已绑定 owner）
     * @param seasonNo 季号，仅集有效，可为空
     * @param episodeNo 集号，仅集有效，可为空
     * @return NFO XML 字符串
     */
    public String generate(MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        return mergeSupport.generate(metadata, seasonNo, episodeNo);
    }

    /** 合并写回 NFO（ADR 0033）：管理字段覆盖、genre 整体替换，其余元素保留；空/解析失败/根元素不符回退整体重写。实现下沉到 {@link MediaNfoMergeSupport}。 */
    public String mergeNfo(String existingXml, MediaMetadata metadata, Integer seasonNo, Integer episodeNo) {
        return mergeSupport.mergeNfo(existingXml, metadata, seasonNo, episodeNo);
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
