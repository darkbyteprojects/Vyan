package com.vyan.xtreamplayer.ui.viewmodels

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vyan.xtreamplayer.network.CatalogScraper
import com.vyan.xtreamplayer.network.CatalogSource
import com.vyan.xtreamplayer.network.IptvVerifier
import com.vyan.xtreamplayer.network.ScrapePage
import com.vyan.xtreamplayer.network.ScrapedPortal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverViewModel(application: Application) : AndroidViewModel(application) {
    val discoveredPortals = mutableStateListOf<ScrapedPortal>()
    val isScraping = mutableStateOf(false)
    val statusText = mutableStateOf("Ready to scan for free portals.")

    private var scrapeJob: Job? = null
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("DiscoverPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val pendingPortals = mutableListOf<ScrapedPortal>()
    private val attemptedKeys = mutableSetOf<String>()
    private val verifiedKeys = mutableSetOf<String>()
    private var scrapeAfter: String? = null
    var scrapeSource = CatalogSource.BEST

    init {
        loadSavedPortals()
        verifiedKeys.addAll(discoveredPortals.map { it.key })
    }

    private fun loadSavedPortals() {
        val savedJson = prefs.getString("saved_portals", null)
        if (savedJson != null) {
            try {
                val type = object : TypeToken<List<ScrapedPortal>>() {}.type
                val savedList: List<ScrapedPortal> = gson.fromJson(savedJson, type)
                discoveredPortals.addAll(savedList)
                if (discoveredPortals.isNotEmpty()) statusText.value = "Loaded ${discoveredPortals.size} saved portals."
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun savePortalsToStorage() {
        prefs.edit().putString("saved_portals", gson.toJson(discoveredPortals)).apply()
    }

    fun setSource(source: CatalogSource) {
        if (source == scrapeSource) return
        scrapeSource = source
        scrapeAfter = null
        pendingPortals.clear()
        statusText.value = ""
    }

    fun scrape() {
        if (isScraping.value) return
        isScraping.value = true
        statusText.value = "Finding portals..."
        scrapeAndVerify()
    }

    fun getMore() {
        if (isScraping.value) return
        isScraping.value = true
        statusText.value = "Searching for more..."
        scrapeAndVerify()
    }

    private fun scrapeAndVerify() {
        val targetAlive = 5
        val maxPagesPerPress = 40

        scrapeJob = viewModelScope.launch(Dispatchers.IO) {
            val newAlive = mutableListOf<ScrapedPortal>()
            var page: ScrapePage? = null
            var pagesTried = 0
            var exhausted = false

            try {
                while (newAlive.size < targetAlive && pagesTried < maxPagesPerPress) {

                    while (pendingPortals.isEmpty() && pagesTried < maxPagesPerPress) {
                        pagesTried++
                        page = CatalogScraper.scrapeCatalogPage(scrapeSource, scrapeAfter)
                        scrapeAfter = page.nextAfter

                        for (p in page.portals) {
                            if (!attemptedKeys.contains(p.key) && discoveredPortals.none { it.key == p.key }) {
                                pendingPortals.add(p)
                            }
                        }

                        if (pendingPortals.isEmpty() && !page.hasMore) {
                            exhausted = true
                            break
                        }
                    }

                    if (pendingPortals.isEmpty()) break

                    val remaining = targetAlive - newAlive.size
                    withContext(Dispatchers.Main) {
                        statusText.value = "Verifying ${pendingPortals.size} queued portals..."
                    }

                    val snapshot = pendingPortals.toList()
                    IptvVerifier.verifyUntil(
                        portals = snapshot,
                        target = remaining,
                        onAttempted = { p ->
                            attemptedKeys.add(p.key)
                            pendingPortals.removeAll { it.key == p.key }
                        },
                        onProgress = { c, t, a ->
                            val total = newAlive.size + a
                            statusText.value = "Verifying $c / $t · alive $total / $targetAlive"
                        },
                        onAlive = { v ->
                            if (verifiedKeys.add(v.key)) {
                                newAlive.add(v)
                                discoveredPortals.add(v)
                            }
                        }
                    )

                    if (newAlive.size < targetAlive && pendingPortals.isEmpty() && (page == null || !page.hasMore)) {
                        exhausted = true
                        break
                    }
                }

                if (newAlive.isNotEmpty()) savePortalsToStorage()

                val canGetMore = pendingPortals.isNotEmpty() || (page?.hasMore == true)

                withContext(Dispatchers.Main) {
                    if (newAlive.isEmpty()) {
                        statusText.value = if (exhausted) "No live portals found in this source."
                        else if (canGetMore) "No new live portals. Try Get More."
                        else "No new live portals."
                    } else {
                        val hit = newAlive.size >= targetAlive
                        var txt = if (hit) "Found ${newAlive.size} live portals."
                        else "Found ${newAlive.size} live portals${if (exhausted) " (source exhausted)." else " (stopped early)."}"

                        if (pendingPortals.isNotEmpty()) {
                            txt += " (${pendingPortals.size} more queued)"
                        }
                        statusText.value = txt
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusText.value = "Scrape failed: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { isScraping.value = false }
            }
        }
    }

    fun reverifyPortals() {
        if (isScraping.value) return
        if (discoveredPortals.isEmpty()) {
            statusText.value = "No portals to reverify."
            return
        }

        isScraping.value = true
        statusText.value = "Reverifying ${discoveredPortals.size} portals..."

        scrapeJob = viewModelScope.launch(Dispatchers.IO) {
            val portalsToVerify = discoveredPortals.toList()
            val alivePortals = mutableListOf<ScrapedPortal>()

            // Clear the screen so they pop back in as they are verified
            withContext(Dispatchers.Main) {
                discoveredPortals.clear()
                verifiedKeys.clear()
            }

            IptvVerifier.verifyUntil(
                portals = portalsToVerify,
                target = portalsToVerify.size, // We want to check ALL of them
                onAttempted = { },
                onProgress = { checked, total, alive ->
                    statusText.value = "Reverifying $checked / $total · $alive alive"
                },
                onAlive = { verifiedPortal ->
                    if (verifiedKeys.add(verifiedPortal.key)) {
                        alivePortals.add(verifiedPortal)
                        discoveredPortals.add(verifiedPortal)
                    }
                }
            )

            savePortalsToStorage()

            withContext(Dispatchers.Main) {
                statusText.value = "Reverification complete. ${alivePortals.size} remain alive."
                isScraping.value = false
            }
        }
    }

    fun stopScraping() {
        scrapeJob?.cancel()
        isScraping.value = false
        statusText.value = "Scanning stopped by user."
    }

    fun clearAll() {
        discoveredPortals.clear()
        attemptedKeys.clear()
        pendingPortals.clear()
        verifiedKeys.clear()
        savePortalsToStorage()
        statusText.value = "All portals cleared."
    }

    fun deleteSelected(selectedUrls: Set<String>) {
        discoveredPortals.removeAll { selectedUrls.contains(it.url + it.username) }
        verifiedKeys.clear()
        verifiedKeys.addAll(discoveredPortals.map { it.key })
        savePortalsToStorage()
    }
}