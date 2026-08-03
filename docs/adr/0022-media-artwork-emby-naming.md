# 影视本地图片命名：识别对齐 Jellyfin 别名集，写回改用 Emby 首选名

ADR 0020 第 4 条将本地图片命名固定为 Jellyfin 风格的单一文件名（`poster.jpg`/`fanart.jpg`），实际使用中 Emby 及其兼容削刮器产出的 `folder.jpg`/`backdrop.jpg` 完全无法识别。Jellyfin 官方文档对每种图片类型定义的本身就是一族别名而非单名，`folder.jpg`/`backdrop.jpg` 同为合规命名。因此决定（部分取代 ADR 0020 第 4 条的命名约定）：

1. **读取侧对齐 Jellyfin 官方别名集**：海报链 `folder.jpg`→`poster.jpg`→`cover.jpg`→`default.jpg`（电影追加 `movie.jpg`，剧集追加 `show.jpg`），背景链 `backdrop.jpg`→`fanart.jpg`→`background.jpg`→`art.jpg`，按序取第一个存在的文件；季海报维持仅 `seasonXX-poster.jpg`，集剧照维持 `<视频名>-thumb.jpg`。
2. **写回侧改用 Emby 首选名**：TMDB 削刮写回图片统一写 `folder.jpg`（海报）与 `backdrop.jpg`（背景），并将写名置于读取链首，保证写读自洽（再次削刮读到的必是自己写回的图）。
3. **拒绝双写两族命名**：`poster.jpg`+`folder.jpg` 都写会双倍占盘并在文件树中产生重复图片；单一写名下 Jellyfin/Emby 均可识别。
4. **不纳入 logo/landscape/banner**：元数据模型无对应字段，jcloud 界面不展示这几类图，识别了也是空转。

历史已写回的 `poster.jpg`/`fanart.jpg` 仍在读取链中可被识别，无需迁移。NFO 命名（同名 `.nfo`、`tvshow.nfo`）不变，本就两系通用。
