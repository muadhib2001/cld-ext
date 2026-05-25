package com.sarem

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import android.util.Log;
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.content.Context
import androidx.appcompat.app.AppCompatActivity


class Turkish : MainAPI() {
    override var mainUrl = "https://ahs.turkish123.com"
    override var name = "Turkish123"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private const val mainServer = "https://tukipasti.com"

        private fun getApplicationContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
                val activityThread = currentActivityThreadMethod.invoke(null)
                val getApplicationMethod = activityThreadClass.getMethod("getApplication")
                getApplicationMethod.invoke(activityThread) as? Application
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get Application context: ${e.message}")
                null
            }
        }

        
    }

    override val mainPage = mainPageOf(
        "$mainUrl/series-list/page/" to "Series List",
        "$mainUrl/episodes-list/page/" to "Episodes List",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.movies-list div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("-episode") -> uri.substringBefore("-episode")
            else -> uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperLink(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst("h2")?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quality = getQualityFromString(this.selectFirst("span.mli-quality")?.text())
        val episode = this.selectFirst("span.mli-eps i")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
            this.quality = quality
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movies-list div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.thumb.mvic-thumb img")?.attr("src"))
        val tags = document.select("div.mvici-left p:contains(Genre:) a").map { it.text() }

        val year = document.selectFirst("div.mvici-right p:contains(Year:) a")?.text()?.trim()
            ?.toIntOrNull()
        val description = document.select("p.f-desc").text().trim()
        val duration = document.selectFirst("div.mvici-right span[itemprop=duration]")?.text()
            ?.filter { it.isDigit() }?.toIntOrNull()
        //val rating = document.select("span.imdb-r").text().trim().toRatingInt()
        //val rating = document.select("span.imdb-r").text().trim().toFloat()
        val actors = document.select("div.mvici-left p:contains(Actors:) a").map { it.text() }

        val recommendations = document.select("div.movies-list div.ml-item").mapNotNull {
            it.toSearchResult()
        }

        /*val episodes = document.select("div.les-content a").map {
            Episode(
                it.attr("href"),
                it.text(),
            )
        }*/

        val episodes = document.select("div.les-content a").map {
            val name = it.text().trim()
            val episode = Regex("(\\d+[.,]?\\d*)").find(name)?.groupValues?.getOrNull(0)
                        ?.toIntOrNull()
            
            val raw = it.attr("href")
            val link = "${if (raw.endsWith("/")) raw else "$raw/"}#tab1"

            
            newEpisode(link) { this.episode = episode }
        }

         

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            //this.score =  Score.from10(rating)
            this.duration = duration
            addActors(actors)
            this.recommendations = recommendations
        }
    }

    private suspend fun invokeLocalSource(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = "$mainUrl/").text
        //Log.i("#sarem invokeLocalSource#",url)
        Regex("var\\surlPlay\\s=\\s[\"|'](\\S+)[\"|'];").find(document)?.groupValues?.get(1)
            ?.let { link ->
                M3u8Helper.generateM3u8(
                    this.name,
                    link,
                    referer = "$mainServer/"
                ).forEach(callback)
            }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.i("#sarem data#",data)


        val context = getApplicationContext() 

  
        val webView = WebView(context).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                visibility = View.GONE  // caché et n'occupe pas d'espace
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
                webViewClient = object : WebViewClient() {

                    // Intercepte chaque navigation
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        // Laisse la WebView charger la page normalement
                        return false
                    }

                    // Appelé quand la page est entièrement chargée
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        // Récupère le HTML complet après rendu JS
                        view?.evaluateJavascript(
                            "(function() { return document.documentElement.outerHTML; })();"
                        ) { html ->
                            // html arrive avec des escapes JSON, on nettoie
                            val cleanHtml = html
                                ?.removeSurrounding("\"")
                                ?.replace("\\u003C", "<")
                                ?.replace("\\\"", "\"")
                                ?.replace("\\n", "\n")
                                ?: return@evaluateJavascript

                            val document = Jsoup.parse(cleanHtml)
                            var found=false

                            val iframe = document.select("div.moivieplay iframe[src]").firstOrNull()

                            if (iframe != null) {
                                val src = iframe.attr("src")
                                 if (src != null) {
                                    found=src.contains(mainServer)
                                 }
                            } 


                            // Selon la page chargée, on fait un traitement différent
                            when {
                                found == true -> {
                                    Log.i("#sarem movieplay#",document.select(".movieplay").first()?.html() ?: "")
                                    document.select(".movieplay").amap { e ->
                                            val html=e.outerHtml()
                                            
                                            //Log.i("#sarem", html);
                                            val slist=Regex("<iframe.*src=[\"|'](https[^\"]*)[\"|']").findAll(html).map { it.groupValues[1] }.toList()
                                            //val slist=Regex("[^=]*src=[\"|']([^=]*)[\"|']").findAll(html).map { it.groupValues[1] }.toList()
                                            //val size = slist.size
                                            //Log.i("#sarem", slist.joinToString());

                                            for (link in slist) {
                                                Log.i("#sarem loadLinks#",link)
                                                if (link.startsWith(mainServer)) {
                                                    invokeLocalSource(link, callback)
                                                } else {
                                                    loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                                                }
                                            }
                                            
                                        }
                                }
                                found == false -> {
                                    view.evaluateJavascript("document.querySelector('.player_nav a[href=\"#tab1\"]').click();",null)
                                }
                            }
                        }
                    }
                }

                // Charge la page initiale
                loadUrl(data)
            }

            
        return true

    }

}
