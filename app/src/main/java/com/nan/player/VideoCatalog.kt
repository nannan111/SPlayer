package com.nan.player

object VideoCatalog {
    val videos: List<VideoItem> = listOf(
        VideoItem(
            title = "xgplayer demo",
            description = "360p MP4",
            url = "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-360p.mp4",
            duration = "00:20"
        ),
        VideoItem(
            title = "Local oceans",
            description = "Asset MP4",
            url = "file:///android_asset/oceans.mp4",
            duration = "00:20"
        ),
        VideoItem(
            title = "news ",
            description = "HTTP MP4",
            url = "https://hls.cntv.lxdns.com/asp/hls/1200/0303000a/3/default/a8d0c954fc5d4e118611e90db1e42ce9/1200.m3u8",
            duration = "00:20"
        ),
        VideoItem(
            title = "Shandong TV",
            description = "H.264 MP4",
            url = "https://hls.cntv.lxdns.com/asp/hls/450/0303000a/3/default/4410d481e3074b689d0ee61bc5d844f3/450.m3u8",
            duration = "05:32"
        ),
        VideoItem(
            title = "Asset MVI",
            description = "Local asset",
            url = "file:///android_asset/mvi.MVI",
            duration = "--:--"
        ),
        VideoItem(
            title = "Movie 1",
            description = "HLS",
            url = "https://hn.bfvvs.com/play/eZ6OlEEe/index.m3u8",
            duration = "--:--"
        ),
        VideoItem(
            title = "Movie 2",
            description = "HLS",
            url = "https://hn.bfvvs.com/play/YerKQQ2a/index.m3u8",
            duration = "--:--"
        ),
        VideoItem(
            title = "Movie 3",
            description = "HLS",
            url = "https://hn.bfvvs.com/play/aM88j3Ge/index.m3u8",
            duration = "--:--"
        ),
        VideoItem(
            title = "CNTV HLS 1",
            description = "HLS",
            url = "https://hls.cntv.lxdns.com/asp/hls/main/0303000a/3/default/bb2347548ea44c7c826078330dcf191f/main.m3u8?maxbr=2048",
            duration = "--:--"
        ),
        VideoItem(
            title = "CNTV MP4",
            description = "MP4",
            url = "https://vod.cntv.lxdns.com/flash/mp4video63/TMS/2026/03/29/6f91bba5486f4df78b795001482bf862_h2642000000nero_aac16-10.mp4",
            duration = "--:--"
        ),
        VideoItem(
            title = "CNTV HLS 2",
            description = "HLS",
            url = "https://newcntv.qcloudcdn.com/asp/hls/main/0303000a/3/default/6f91bba5486f4df78b795001482bf862/main.m3u8?maxbr=2048",
            duration = "--:--"
        )
    )
}
