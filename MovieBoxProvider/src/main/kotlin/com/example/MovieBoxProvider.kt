package com.example

import com.fasterxml.jackson.annotation.JsonProperty
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
        @JsonProperty("data") val data: MbSearchData? = null
    )

    data class MbSearchData(
        @JsonProperty("items") val items: List<MbSearchItem>? = null
    )

    data class MbSearchItem(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("cover") val cover: MbCover? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
        @JsonProperty("subject") val subject: MbSubject? = null
    )

    data class MbCover(
        @JsonProperty("url") val url: String? = null
    )

    data class MbHomeResponse(
        @JsonProperty("data") val data: MbHomeData? = null
    )

    data class MbHomeData(
        @JsonProperty("operatingList") val operatingList: List<MbOperatingList>? = null
    )

    data class MbOperatingList(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("banner") val banner: MbBanner? = null,
        @JsonProperty("subjects") val subjects: List<MbSearchItem>? = null
    )

    data class MbBanner(
        @JsonProperty("items") val items: List<MbSearchItem>? = null
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
        @JsonProperty("data") val data: MbDetailData? = null
    )

    data class MbDetailData(
        @JsonProperty("subject") val subject: MbSubject? = null,
        @JsonProperty("postList") val postList: MbPostList? = null
    )

    data class MbSubject(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("cover") val cover: MbCover? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
        @JsonProperty("stafflist") val stafflist: List<MbStaff>? = null
    )

    data class MbStaff(
        @JsonProperty("name") val name: String? = null
    )

    data class MbPostList(
        @JsonProperty("items") val items: List<MbPostItem>? = null
    )

    data class MbPostItem(
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("title") val title: String? = null
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
        @JsonProperty("data") val data: MbDownloadData? = null
    )

    data class MbDownloadData(
        @JsonProperty("downloads") val downloads: List<MbDownloadItem>? = null,
        @JsonProperty("captions") val captions: List<MbCaptionItem>? = null
    )

    data class MbDownloadItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("resolution") val resolution: Int? = null
    )

    data class MbCaptionItem(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("lanName") val lanName: String? = null,
        @JsonProperty("lan") val lan: String? = null
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
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
                    quality = getQualityFromName("${res}p"),
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