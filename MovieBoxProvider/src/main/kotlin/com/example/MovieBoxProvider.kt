package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://h5-api.aoneroom.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override var lang = "en"
    override val hasMainPage = true

    private val apiHeaders = mapOf(
        "X-Client-Info" to "{\"timezone\":\"Africa/Nairobi\"}",
        "Accept" to "application/json",
        "Referer" to "https://videodownloader.site/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    data class MbSearchResponse(
        val data: MbSearchData? = null
    )

    data class MbSearchData(
        val items: List<MbSearchItem>? = null
    )

    data class MbSearchItem(
        val subjectId: String? = null,
        val title: String? = null,
        val subjectType: Int? = null,
        val cover: MbCover? = null,
        val releaseDate: String? = null,
        val detailPath: String? = null,
        val subject: MbSubject? = null
    )

    data class MbCover(
        val url: String? = null
    )

    data class MbHomeResponse(
        val data: MbHomeData? = null
    )

    data class MbHomeData(
        val operatingList: List<MbOperatingList>? = null
    )

    data class MbOperatingList(
        val title: String? = null,
        val banner: MbBanner? = null,
        val subjects: List<MbSearchItem>? = null
    )

    data class MbBanner(
        val items: List<MbSearchItem>? = null
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/wefeed-h5api-bff/home?host=moviebox.ph"
        val response = app.get(url, headers = apiHeaders).parsed<MbHomeResponse>()
        val data = response.data ?: return HomePageResponse(emptyList())

        val homePages = mutableListOf<HomePageList>()

        data.operatingList?.forEach { group ->
            val groupName = group.title ?: return@forEach
            val items = group.banner?.items ?: group.subjects ?: return@forEach

            val searchResponses = items.mapNotNull { item ->
                val id = item.subjectId ?: return@mapNotNull null
                val subject = item.subject ?: item
                val title = subject.title ?: return@mapNotNull null
                val typeId = subject.subjectType ?: item.subjectType
                val type = if (typeId == 2) TvType.TvSeries else TvType.Movie
                // Use the subject's cover if available, fallback to item's cover, fallback to embedded cover.url
                val posterUrl = subject.cover?.url ?: item.cover?.url
                val detailPath = subject.detailPath ?: item.detailPath
                val detailUrl = "$mainUrl/wefeed-h5api-bff/detail?detailPath=$detailPath&id=$id&type=$typeId"

                newTvSeriesSearchResponse(title, detailUrl, type) {
                    this.posterUrl = posterUrl
                    if (!subject.releaseDate.isNullOrEmpty()) {
                        this.year = subject.releaseDate.substringBefore("-").toIntOrNull()
                    }
                }
            }
            if (searchResponses.isNotEmpty()) {
                homePages.add(HomePageList(groupName, searchResponses))
            }
        }
        return HomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val payload = mapOf(
            "keyword" to query,
            "page" to 1,
            "perPage" to 24,
            "subjectType" to 0
        )
        
        val url = "$mainUrl/wefeed-h5api-bff/subject/search"
        val response = app.post(
            url,
            headers = apiHeaders,
            json = payload
        ).parsed<MbSearchResponse>()

        return response.data?.items?.mapNotNull { item ->
            val id = item.subjectId ?: return@mapNotNull null
            val title = item.title ?: return@mapNotNull null
            val type = if (item.subjectType == 2) TvType.TvSeries else TvType.Movie
            val posterUrl = item.cover?.url
            
            // Build the detail URL and embed necessary fields for load()
            val detailUrl = "$mainUrl/wefeed-h5api-bff/detail?detailPath=${item.detailPath}&id=$id&type=${item.subjectType}"

            newTvSeriesSearchResponse(title, detailUrl, type) {
                this.posterUrl = posterUrl
                if (!item.releaseDate.isNullOrEmpty()) {
                    this.year = item.releaseDate.substringBefore("-").toIntOrNull()
                }
            }
        } ?: emptyList()
    }

    data class MbDetailResponse(
        val data: MbDetailData? = null
    )

    data class MbDetailData(
        val subject: MbSubject? = null,
        val postList: MbPostList? = null
    )

    data class MbSubject(
        val subjectId: String? = null,
        val title: String? = null,
        val subjectType: Int? = null,
        val description: String? = null,
        val cover: MbCover? = null,
        val releaseDate: String? = null,
        val detailPath: String? = null,
        val stafflist: List<MbStaff>? = null
    )

    data class MbStaff(
        val name: String? = null
    )

    data class MbPostList(
        val items: List<MbPostItem>? = null
    )

    data class MbPostItem(
        val episode: Int? = null,
        val season: Int? = null,
        val title: String? = null
    )

    override suspend fun load(url: String): LoadResponse? {
        val detailPath = AppUtils.parseJson<Map<String, String>>("{\"" + url.substringAfter("?").replace("&", "\",\"").replace("=", "\":\"") + "\"}")["detailPath"] ?: url.substringAfter("detailPath=").substringBefore("&")
        val subjectId = url.substringAfter("id=").substringBefore("&")
        val typeId = url.substringAfter("type=").substringBefore("&").toIntOrNull() ?: 1

        val reqUrl = "$mainUrl/wefeed-h5api-bff/detail?detailPath=$detailPath"
        val response = app.get(reqUrl, headers = apiHeaders).parsed<MbDetailResponse>()
        val data = response.data ?: return null
        val subject = data.subject ?: return null
        
        val title = subject.title ?: return null
        val poster = subject.cover?.url
        val description = subject.description
        val year = subject.releaseDate?.substringBefore("-")?.toIntOrNull()
        
        // Build episode list
        val isTvSeries = (typeId == 2)
        
        if (isTvSeries) {
            val episodes = data.postList?.items?.map { ep ->
                val epNum = ep.episode ?: 1
                val snNum = ep.season ?: 1
                Episode(
                    data = "$subjectId,$snNum,$epNum,$detailPath",
                    name = ep.title ?: "Episode $epNum",
                    season = snNum,
                    episode = epNum
                )
            } ?: emptyList()
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, "$subjectId,0,0,$detailPath") {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    data class MbDownloadResponse(
        val data: MbDownloadData? = null
    )

    data class MbDownloadData(
        val downloads: List<MbDownloadItem>? = null,
        val captions: List<MbCaptionItem>? = null
    )

    data class MbDownloadItem(
        val url: String? = null,
        val resolution: Int? = null
    )

    data class MbCaptionItem(
        val url: String? = null,
        val lanName: String? = null,
        val lan: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(",")
        if (parts.size < 4) return false
        val subjectId = parts[0]
        val se = parts[1]
        val ep = parts[2]
        val detailPath = parts[3]

        val dlUrl = "$mainUrl/wefeed-h5api-bff/subject/download?subjectId=$subjectId&se=$se&ep=$ep&detailPath=$detailPath"
        
        val response = app.get(dlUrl, headers = apiHeaders).parsed<MbDownloadResponse>()
        val dlData = response.data ?: return false

        dlData.downloads?.forEach { dlItem ->
            val videoUrl = dlItem.url ?: return@forEach
            val res = dlItem.resolution ?: 720
            
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name + " ${res}p",
                    url = videoUrl,
                    referer = apiHeaders["Referer"]!!,
                    quality = Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }

        dlData.captions?.forEach { capItem ->
            val subUrl = capItem.url ?: return@forEach
            val lang = capItem.lanName ?: capItem.lan ?: "Unknown"
            
            subtitleCallback.invoke(
                SubtitleFile(lang, subUrl)
            )
        }

        return true
    }
}