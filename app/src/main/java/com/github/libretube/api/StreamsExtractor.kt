package com.github.libretube.api

import android.content.Context
import com.github.libretube.R
import android.util.Log
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Message
import com.github.libretube.api.obj.MetaInfo
import com.github.libretube.api.obj.PipedStream
import com.github.libretube.api.obj.PreviewFrames
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.api.obj.Streams
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.enums.ContentFilter
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.ui.dialogs.ShareDialog
import com.github.libretube.util.NewPipeDownloaderImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.toKotlinInstant
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import retrofit2.HttpException
import java.io.IOException
import java.lang.Exception
import java.time.OffsetDateTime

fun VideoStream.toPipedStream(): PipedStream = PipedStream(
    url = content,
    codec = codec,
    format = format.toString(),
    height = height,
    width = width,
    quality = getResolution(),
    mimeType = format?.mimeType,
    bitrate = bitrate,
    initStart = initStart,
    initEnd = initEnd,
    indexStart = indexStart,
    indexEnd = indexEnd,
    fps = fps,
    contentLength = itagItem?.contentLength ?: 0L
)

object StreamsExtractor {
//    val npe by lazy {
//        NewPipe.getService(ServiceList.YouTube.serviceId)
//    }

    private const val MAX_CONCURRENT_REQUESTS = 32
    private val SERVICE = NewPipe.getService(ServiceList.YouTube.serviceId)

    /**
     * Defines the cutoff date for sub-feeds.
     *
     * Sub-feeds older than this date are considered expired and will not be shown.
     * Currently set to 3 days before now.
     */
    private val SUB_FEED_CUTOFF_DATE = OffsetDateTime.now().minusDays(3)

    init {
        NewPipe.init(NewPipeDownloaderImpl())
    }

    suspend fun extractFeed(subscriptions: List<String>): List<StreamItem> {
        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        val feed = coroutineScope {
            subscriptions.map{ subscription ->
                async {
                    try {
                        semaphore.withPermit {
                            extractSubscription(subscription)
                        }
                    } catch (e: Exception) {
                        Log.e("StreamsExtractor", "extractFeed: Failed to extract subscription $subscription: ${e.message}")
                        emptyList()
                    }
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.uploaded }

        return feed;
    }

    private suspend fun extractSubscription(subscription: String): List<StreamItem> {
        return withContext(Dispatchers.IO) {
            // we fetch the uploads using the FeedInfo first,
            // it does not have all the info we need, but we can use it to check
            // if there even are new videos to be fetched
            val feedInfo = FeedInfo.getInfo(
                "${ShareDialog.YOUTUBE_FRONTEND_URL}/channel/$subscription"
            )
            val hasRecentUploads = feedInfo.relatedItems.any { it.uploadDate?.offsetDateTime()?.isAfter(SUB_FEED_CUTOFF_DATE) == true }
            if (!hasRecentUploads)
                return@withContext emptyList()

            val channelInfo = ChannelInfo.getInfo(
                SERVICE,
                "${ShareDialog.YOUTUBE_FRONTEND_URL}/channel/$subscription"
            )

            channelInfo.tabs.asSequence().filter {
                ChannelTabs.VIDEOS in it.contentFilters && ContentFilter.VIDEOS.isEnabled ||
                        // Shorts are not working in NewPipeExtractor right now
                        // ChannelTabs.SHORTS in it.contentFilters && ContentFilter.SHORTS.isEnabled ||
                 ChannelTabs.LIVESTREAMS in it.contentFilters && ContentFilter.LIVESTREAMS.isEnabled
            }.flatMap {
                ChannelTabInfo.getInfo(SERVICE, it).relatedItems
            }.filterIsInstance<StreamInfoItem>().filter {
                it.uploadDate?.offsetDateTime()?.isAfter(
                    SUB_FEED_CUTOFF_DATE) == true
            }.map {
                StreamItem(
                    url = it.url.replace(ShareDialog.YOUTUBE_FRONTEND_URL, ""),
                    type = StreamItem.TYPE_STREAM,
                    title = it.name,
                    // for some reason the extracted thumbnails do not include the Full-HD thumbnail,
                    // so we manually use the maxres thumbnail URL
                    //thumbnail = it.thumbnails.maxByOrNull { img -> img.height }?.url,
                    thumbnail = "https://img.youtube.com/vi/${it.url.replace("${ShareDialog.YOUTUBE_FRONTEND_URL}/watch?v=", "")}/maxresdefault.jpg",
                    uploaderName = it.uploaderName,
                    uploaderUrl = it.uploaderUrl.replace(ShareDialog.YOUTUBE_FRONTEND_URL, ""),
                    uploaderAvatar = channelInfo.avatars.maxByOrNull { img -> img.height }?.url,
                    uploadedDate = it.uploadDate?.offsetDateTime().toString(),
                    duration = it.duration,
                    views = it.viewCount,
                    uploaderVerified = it.isUploaderVerified,
                    uploaded = it.uploadDate?.offsetDateTime()?.toEpochSecond()?.times(1000) ?: 0L,
                    shortDescription = it.shortDescription,
                    isShort = it.isShortFormContent
                )
            }.toList()
        }
    }

    suspend fun extractStreams(videoId: String): Streams {
        if (!PlayerHelper.disablePipedProxy || !PlayerHelper.localStreamExtraction) {
            return RetrofitInstance.api.getStreams(videoId)
        }

        val resp = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        return Streams(
            title = resp.name,
            description = resp.description.content,
            uploader = resp.uploaderName,
            uploaderAvatar = resp.uploaderAvatars.maxBy { it.height }.url,
            uploaderUrl = resp.uploaderUrl.replace("https://www.youtube.com", ""),
            uploaderVerified = resp.isUploaderVerified,
            uploaderSubscriberCount = resp.uploaderSubscriberCount,
            category = resp.category,
            views = resp.viewCount,
            likes = resp.likeCount,
            dislikes = if (PlayerHelper.localRYD) runCatching {
                RetrofitInstance.externalApi.getVotes(videoId).dislikes
            }.getOrElse { -1 } else -1,
            license = resp.licence,
            hls = resp.hlsUrl,
            dash = resp.dashMpdUrl,
            tags = resp.tags,
            metaInfo = resp.metaInfo.map {
                MetaInfo(
                    it.title,
                    it.content.content,
                    it.urls.map { url -> url.toString() },
                    it.urlTexts
                )
            },
            visibility = resp.privacy.name.lowercase(),
            duration = resp.duration,
            uploadTimestamp = resp.uploadDate.offsetDateTime().toInstant().toKotlinInstant(),
            uploaded = resp.uploadDate.offsetDateTime().toEpochSecond() * 1000,
            thumbnailUrl = resp.thumbnails.maxBy { it.height }.url,
            relatedStreams = resp.relatedItems.filterIsInstance<StreamInfoItem>().map {
                StreamItem(
                    it.url.replace("https://www.youtube.com", ""),
                    StreamItem.TYPE_STREAM,
                    it.name,
                    it.thumbnails.maxBy { image -> image.height }.url,
                    it.uploaderName,
                    it.uploaderUrl.replace("https://www.youtube.com", ""),
                    it.uploaderAvatars.maxBy { image -> image.height }.url,
                    it.textualUploadDate,
                    it.duration,
                    it.viewCount,
                    it.isUploaderVerified,
                    it.uploadDate?.offsetDateTime()?.toEpochSecond()?.times(1000) ?: 0L,
                    it.shortDescription,
                    it.isShortFormContent,
                )
            },
            chapters = resp.streamSegments.map {
                ChapterSegment(
                    title = it.title,
                    image = it.previewUrl.orEmpty(),
                    start = it.startTimeSeconds.toLong()
                )
            },
            audioStreams = resp.audioStreams.map {
                PipedStream(
                    url = it.content,
                    format = it.format?.toString(),
                    quality = "${it.averageBitrate} bits",
                    bitrate = it.bitrate,
                    mimeType = it.format?.mimeType,
                    initStart = it.initStart,
                    initEnd = it.initEnd,
                    indexStart = it.indexStart,
                    indexEnd = it.indexEnd,
                    contentLength = it.itagItem?.contentLength ?: 0L,
                    codec = it.codec,
                    audioTrackId = it.audioTrackId,
                    audioTrackName = it.audioTrackName,
                    audioTrackLocale = it.audioLocale?.toLanguageTag(),
                    audioTrackType = it.audioTrackType?.name,
                    videoOnly = false
                )
            },
            videoStreams = resp.videoOnlyStreams.map {
                it.toPipedStream().copy(videoOnly = true)
            } + resp.videoStreams.map {
                it.toPipedStream().copy(videoOnly = false)
            },
            previewFrames = resp.previewFrames.map {
                PreviewFrames(
                    it.urls,
                    it.frameWidth,
                    it.frameHeight,
                    it.totalCount,
                    it.durationPerFrame.toLong(),
                    it.framesPerPageX,
                    it.framesPerPageY
                )
            },
            subtitles = resp.subtitles.map {
                Subtitle(
                    it.content,
                    it.format?.mimeType,
                    it.displayLanguageName,
                    it.languageTag,
                    it.isAutoGenerated
                )
            }
        )
    }

    fun getExtractorErrorMessageString(context: Context, exception: Exception): String {
        return when (exception) {
            is IOException -> context.getString(R.string.unknown_error)
            is HttpException -> exception.response()?.errorBody()?.string()?.runCatching {
                JsonHelper.json.decodeFromString<Message>(this).message
            }?.getOrNull() ?: context.getString(R.string.server_error)
            else -> exception.localizedMessage.orEmpty()
        }
    }
}
