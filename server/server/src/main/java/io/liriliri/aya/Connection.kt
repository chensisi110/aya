package io.liriliri.aya

import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.LocalSocket
import android.util.DisplayMetrics
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class Connection(private val client: LocalSocket) : Thread() {
    private companion object {
        private const val TAG = "Aya.Connection"
        private const val QUERY_PARALLELISM = 4
        private var packageCache = JSONObject()
        private val packageCacheLock = Any()
        private val resourcesCache = ConcurrentHashMap<String, Resources>()
        private val queryExecutor = Executors.newFixedThreadPool(QUERY_PARALLELISM)
        private const val ICON_SIZE = 48

        init {
            val iconCacheDir = File(IconCache.DIR)
            if (!iconCacheDir.exists()) {
                iconCacheDir.mkdirs()
            }
        }
    }

    override fun run() {
        while (!isInterrupted && client.isConnected) {
            try {
                val request = Wire.Request.parseDelimitedFrom(client.inputStream)
                val params = request.params.ifEmpty { "{}" }
                handleRequest(request.id, request.method, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle request", e)
                break
            }
        }

        client.close()
    }

    private fun handleRequest(id: String, method: String, params: String) {
        val result = JSONObject()

        when (method) {
            "getPackageInfos" -> {
                result.put("packageInfos", getPackageInfos(JSONObject(params)))
            }

            "startFileServer" -> {
                val port = HttpFileServerManager.start()
                result.put("port", port)
            }

            "isFileServerRunning" -> {
                result.put("running", HttpFileServerManager.isRunning())
            }

            else -> {
                Log.e(TAG, "Unknown method: $method")
            }
        }

        Wire.Response.newBuilder().setId(id).setResult(result.toString()).build()
            .writeDelimitedTo(client.outputStream)
    }

    private fun getPackageInfos(params: JSONObject): JSONArray {
        val packageNames = Util.jsonArrayToStringArray(params.getJSONArray("packageNames"))
        val result = JSONArray()
        if (packageNames.isEmpty()) {
            return result
        }

        if (packageNames.size == 1) {
            try {
                result.put(getPackageInfo(packageNames[0]))
            } catch (_: Exception) {
            }
            return result
        }

        val futures = ArrayList<Future<Pair<Int, JSONObject?>>>(packageNames.size)
        packageNames.forEachIndexed { index, packageName ->
            futures.add(
                queryExecutor.submit<Pair<Int, JSONObject?>> {
                    try {
                        index to getPackageInfo(packageName)
                    } catch (_: Exception) {
                        index to null
                    }
                }
            )
        }

        futures.map { it.get() }
            .sortedBy { it.first }
            .forEach { (_, info) ->
                if (info != null) {
                    result.put(info)
                }
            }

        return result
    }

    private fun getPackageInfo(packageName: String): JSONObject {
        val packageInfo =
            ServiceManager.packageManager.getPackageInfo(packageName, 0)

        val applicationInfo = packageInfo.applicationInfo
        val apkPath = applicationInfo.sourceDir
        val apkSize = File(apkPath).length()

        val cacheKey = "$packageName.$apkSize"
        var label = packageName
        var icon = ""

        synchronized(packageCacheLock) {
            if (packageCache.has(cacheKey)) {
                val cacheInfo = packageCache.getJSONObject(cacheKey)
                label = cacheInfo.getString("label")
                icon = cacheInfo.getString("icon")
            } else {
                val resources = getResources(applicationInfo)
                if (applicationInfo.labelRes != 0) {
                    try {
                        label = resources.getString(applicationInfo.labelRes)
                    } catch (_: Exception) {
                    }
                }
                val cacheInfo = JSONObject()
                cacheInfo.put("label", label)
                cacheInfo.put("icon", "")
                packageCache.put(cacheKey, cacheInfo)
            }
        }

        icon = ensureIconOnDisk(cacheKey, applicationInfo, icon)
        synchronized(packageCacheLock) {
            if (packageCache.has(cacheKey)) {
                packageCache.getJSONObject(cacheKey).put("icon", icon)
            }
        }

        val info = JSONObject()
        info.put("packageName", packageInfo.packageName)
        info.put("label", label)
        info.put("icon", icon)
        info.put("enabled", applicationInfo.enabled)
        return info
    }

    private fun ensureIconOnDisk(
        cacheKey: String,
        applicationInfo: ApplicationInfo,
        cachedIconPath: String,
    ): String {
        if (cachedIconPath.isNotEmpty() && File(cachedIconPath).exists()) {
            return cachedIconPath
        }

        val resources = getResources(applicationInfo)
        val iconPath = savePackageIcon(cacheKey, applicationInfo, resources)
        return if (iconPath.isNotEmpty() && File(iconPath).exists()) iconPath else ""
    }

    private fun savePackageIcon(
        cacheKey: String,
        applicationInfo: ApplicationInfo,
        resources: Resources,
    ): String {
        if (applicationInfo.icon == 0) {
            return ""
        }

        try {
            val iconPath = "${IconCache.DIR}/$cacheKey.png"
            val file = File(iconPath)
            synchronized(iconPath.intern()) {
                if (!file.exists()) {
                    val resIcon = resources.getDrawable(applicationInfo.icon, null)
                    val bitmapIcon = Util.drawableToBitmap(resIcon, ICON_SIZE)
                    val scaledIcon = Util.scaleBitmap(bitmapIcon, ICON_SIZE)
                    val pngIcon = Util.bitMapToPng(scaledIcon, 100)
                    file.parentFile?.mkdirs()
                    file.writeBytes(pngIcon)
                    if (scaledIcon !== bitmapIcon) {
                        scaledIcon.recycle()
                    }
                    bitmapIcon.recycle()
                }
            }
            return if (file.exists()) iconPath else ""
        } catch (_: Exception) {
            return ""
        }
    }

    private fun resourcesCacheKey(applicationInfo: ApplicationInfo): String {
        val key = StringBuilder(applicationInfo.sourceDir)
        applicationInfo.splitSourceDirs
            ?.sorted()
            ?.forEach { splitPath -> key.append('|').append(splitPath) }
        return key.toString()
    }

    private fun getResources(applicationInfo: ApplicationInfo): Resources {
        val cacheKey = resourcesCacheKey(applicationInfo)
        return resourcesCache.getOrPut(cacheKey) {
            createResources(applicationInfo)
        }
    }

    private fun createResources(applicationInfo: ApplicationInfo): Resources {
        val assetManager = AssetManager::class.java.newInstance() as AssetManager
        val addAssetManagerMethod =
            assetManager.javaClass.getMethod("addAssetPath", String::class.java)
        addAssetManagerMethod.invoke(assetManager, applicationInfo.sourceDir)
        applicationInfo.splitSourceDirs?.forEach { splitPath ->
            addAssetManagerMethod.invoke(assetManager, splitPath)
        }

        val displayMetrics = DisplayMetrics()
        displayMetrics.setToDefaults()
        val configuration = Configuration()
        configuration.setToDefaults()

        return Resources(assetManager, displayMetrics, configuration)
    }
}
