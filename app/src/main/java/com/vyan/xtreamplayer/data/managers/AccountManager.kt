package com.vyan.xtreamplayer.data.managers

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vyan.xtreamplayer.models.UserAccount
import com.vyan.xtreamplayer.models.UserInfo

class AccountManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("xtream_player_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    private var accounts: MutableList<UserAccount> = mutableListOf()
    private var activeAccountIndex: Int = 0

    init {
        loadAccounts()
    }

    @Synchronized
    private fun loadAccounts() {
        try {
            val json = prefs.getString("accounts_list", null)
            if (!json.isNullOrEmpty()) {
                val type = object : TypeToken<List<UserAccount>>() {}.type
                val savedList: List<UserAccount>? = gson.fromJson(json, type)
                if (!savedList.isNullOrEmpty()) {
                    accounts = savedList.toMutableList()
                }
            }
            activeAccountIndex = prefs.getInt("active_account_index", 0)
            if (accounts.isNotEmpty()) {
                activeAccountIndex = activeAccountIndex.coerceIn(0, accounts.size - 1)
            } else {
                activeAccountIndex = 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            activeAccountIndex = 0
        }
    }

    @Synchronized
    private fun saveAccounts() {
        try {
            val snapshot = accounts.toList()
            val json = gson.toJson(snapshot)
            prefs.edit()
                .putString("accounts_list", json)
                .putInt("active_account_index", activeAccountIndex)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getAccounts(): List<UserAccount> = try {
        accounts.toList()
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    fun getActiveAccount(): UserAccount? {
        try {
            if (accounts.isEmpty()) return null
            if (activeAccountIndex !in accounts.indices) {
                activeAccountIndex = 0
            }
            return accounts.getOrNull(activeAccountIndex)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    @Synchronized
    fun addAccount(account: UserAccount) {
        try {
            accounts.removeAll { it.url.trim().lowercase() == account.url.trim().lowercase() && it.username == account.username }
            accounts.add(account)
            activeAccountIndex = (accounts.size - 1).coerceAtLeast(0)
            saveAccounts()
            DataCache.clearAll(null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun updateAccount(updatedAccount: UserAccount) {
        try {
            val idx = accounts.indexOfFirst { it.id == updatedAccount.id }
            if (idx != -1) {
                accounts[idx] = updatedAccount
                saveAccounts()
                DataCache.clearAll(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun switchAccount(index: Int) {
        try {
            if (accounts.isEmpty()) return
            if (index in accounts.indices) {
                activeAccountIndex = index
                saveAccounts()
                DataCache.clearAll(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun switchAccount(account: UserAccount) {
        setActiveAccount(account.id)
    }

    @Synchronized
    fun setActiveAccount(accountId: String) {
        try {
            val idx = accounts.indexOfFirst { it.id == accountId }
            if (idx != -1) {
                activeAccountIndex = idx
                saveAccounts()
                DataCache.clearAll(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun setActiveAccount(account: UserAccount) {
        setActiveAccount(account.id)
    }

    @Synchronized
    fun removeAccount(account: UserAccount) {
        try {
            val idx = accounts.indexOfFirst { it.id == account.id }
            if (idx != -1) {
                accounts.removeAt(idx)
                if (accounts.isEmpty()) {
                    activeAccountIndex = 0
                } else {
                    activeAccountIndex = activeAccountIndex.coerceIn(0, accounts.size - 1)
                }
                saveAccounts()
                DataCache.clearAll(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Cached User Info Persistence
    fun saveCachedUserInfo(userInfo: UserInfo, pingMs: Long?) {
        val activeAcc = getActiveAccount() ?: return
        val json = gson.toJson(userInfo)
        prefs.edit()
            .putString("cached_user_info_${activeAcc.id}", json)
            .putLong("cached_ping_${activeAcc.id}", pingMs ?: -1L)
            .apply()
    }

    fun getCachedUserInfo(accountId: String? = null): UserInfo? {
        val targetId = accountId ?: getActiveAccount()?.id ?: return null
        val json = prefs.getString("cached_user_info_${targetId}", null) ?: return null
        return try {
            gson.fromJson(json, UserInfo::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getCachedPing(): Long? {
        val activeAcc = getActiveAccount() ?: return null
        val ping = prefs.getLong("cached_ping_${activeAcc.id}", -1L)
        return if (ping >= 0) ping else null
    }

    // Favorites Management (Star System)
    fun getFavoriteCategories(): Set<String> {
        val activeAcc = getActiveAccount() ?: return emptySet()
        return prefs.getStringSet("fav_categories_${activeAcc.id}", emptySet()) ?: emptySet()
    }

    fun isCategoryFavorite(categoryId: String): Boolean {
        return getFavoriteCategories().contains(categoryId)
    }

    fun toggleFavoriteCategory(categoryId: String) {
        val activeAcc = getActiveAccount() ?: return
        val current = getFavoriteCategories().toMutableSet()
        if (current.contains(categoryId)) {
            current.remove(categoryId)
        } else {
            current.add(categoryId)
        }
        prefs.edit().putStringSet("fav_categories_${activeAcc.id}", current).apply()
    }

    fun getFavoriteItems(keyPrefix: String): Set<String> {
        val activeAcc = getActiveAccount() ?: return emptySet()
        return prefs.getStringSet("${keyPrefix}_${activeAcc.id}", emptySet()) ?: emptySet()
    }

    fun isItemFavorite(keyPrefix: String, itemId: String): Boolean {
        return getFavoriteItems(keyPrefix).contains(itemId)
    }

    fun toggleFavoriteItem(keyPrefix: String, itemId: String) {
        val activeAcc = getActiveAccount() ?: return
        val current = getFavoriteItems(keyPrefix).toMutableSet()
        if (current.contains(itemId)) {
            current.remove(itemId)
        } else {
            current.add(itemId)
        }
        prefs.edit().putStringSet("${keyPrefix}_${activeAcc.id}", current).apply()
    }

    // Hidden categories manager
    fun getHiddenCategories(): Set<String> {
        val activeAcc = getActiveAccount() ?: return emptySet()
        return prefs.getStringSet("hidden_cats_${activeAcc.id}", emptySet()) ?: emptySet()
    }

    fun hideCategory(categoryId: String) {
        val activeAcc = getActiveAccount() ?: return
        val current = getHiddenCategories().toMutableSet()
        current.add(categoryId)
        prefs.edit().putStringSet("hidden_cats_${activeAcc.id}", current).apply()
    }

    fun unhideCategory(categoryId: String) {
        val activeAcc = getActiveAccount() ?: return
        val current = getHiddenCategories().toMutableSet()
        current.remove(categoryId)
        prefs.edit().putStringSet("hidden_cats_${activeAcc.id}", current).apply()
    }

    fun isMoviesTabHidden(): Boolean {
        return prefs.getBoolean("hide_movies_tab", false)
    }

    fun setMoviesTabHidden(hidden: Boolean) {
        prefs.edit().putBoolean("hide_movies_tab", hidden).apply()
    }

    fun isSeriesTabHidden(): Boolean {
        return prefs.getBoolean("hide_series_tab", false)
    }

    fun setSeriesTabHidden(hidden: Boolean) {
        prefs.edit().putBoolean("hide_series_tab", hidden).apply()
    }
}