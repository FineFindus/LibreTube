package com.github.libretube.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asDrawable
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.toBitmap
import com.github.libretube.BuildConfig
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.extensions.toAndroidUri
import com.github.libretube.util.DataSaverMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.nio.file.Path

object ImageHelper {
    private lateinit var imageLoader: ImageLoader

    private val Context.coilFile get() = cacheDir.resolve("coil")

    /**
     * Initialize the image loader
     */
    fun initializeImageLoader(context: Context) {
        val maxCacheSize = PreferenceHelper.getString(PreferenceKeys.MAX_IMAGE_CACHE, "128")

        val httpClient = OkHttpClient().newBuilder()

        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            httpClient.addInterceptor(loggingInterceptor)
        }

        imageLoader = ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(httpClient.build())
                )
            }
            .apply {
                if (maxCacheSize.isEmpty()) {
                    diskCachePolicy(CachePolicy.DISABLED)
                } else {
                    diskCachePolicy(CachePolicy.ENABLED)
                    memoryCachePolicy(CachePolicy.ENABLED)

                    val diskCache = generateDiskCache(
                        directory = context.coilFile,
                        size = maxCacheSize.toInt()
                    )
                    diskCache(diskCache)
                }
            }
            .build()
    }

    private fun generateDiskCache(directory: File, size: Int): DiskCache {
        return DiskCache.Builder()
            .directory(directory)
            .maxSizeBytes(size * 1024 * 1024L)
            .build()
    }

    /**
     * load an image from a url into an imageView
     */
    fun loadImage(url: String?, target: ImageView, whiteBackground: Boolean = false) {
        // clear image to avoid loading issues at fast scrolling
        target.setImageBitmap(null)

        // only load the image if the data saver mode is disabled
        if (DataSaverMode.isEnabled(target.context) || url.isNullOrEmpty()) return
        val urlToLoad = ProxyHelper.rewriteUrlUsingProxyPreference(url)

        getImageWithCallback(target.context, urlToLoad, { result ->
            // set the background to white for transparent images
            if (whiteBackground) target.setBackgroundColor(Color.WHITE)

            target.setImageDrawable(result)
        },
            {
                // some videos do not provide a `maxresdefault` thumbnail, fall back to the medium quality
                // we cannot use `hqdefault` here as it has a different aspect ratio, and thus black bars
                if (urlToLoad.endsWith("maxresdefault.jpg"))
                    loadImage(urlToLoad.replace("maxres", "mq"), target, whiteBackground)
            }
        )
    }

    suspend fun downloadImage(context: Context, url: String, path: Path) {
        val bitmap = getImage(context, url) ?: return
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(path.toAndroidUri())?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 25, it)
            }
        }
    }

    suspend fun getImage(context: Context, url: String?): Bitmap? {
        return getImage(context, url?.toUri())
    }

    suspend fun getImage(context: Context, url: Uri?): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .build()

        return imageLoader.execute(request).image?.toBitmap()
    }

    private fun getImageWithCallback(context: Context, url: String?, onSuccess: (Drawable) -> Unit, onError: () -> Unit) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .target(
                onSuccess = { drawable ->
                    onSuccess(drawable.asDrawable(context.resources))
                },
                onError = { onError() }
            )
            .build()

        imageLoader.enqueue(request)
    }

    /**
     * Get a squared bitmap with the same width and height from a bitmap
     * @param bitmap The bitmap to resize
     */
    fun getSquareBitmap(bitmap: Bitmap): Bitmap {
        val newSize = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - newSize) / 2,
            (bitmap.height - newSize) / 2,
            newSize,
            newSize
        )
    }
}
