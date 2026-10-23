//! 更新配置独立存储；默认不自动请求，不收集输入内容。
package dev.qingstudy.ime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object UpdatePreferences {
    fun store(context: Context) = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    fun wifi(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    fun network(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
