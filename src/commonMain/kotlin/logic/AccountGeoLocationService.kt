package logic

import com.netonstream.geolite4k.GeoLite4K
import com.netonstream.geolite4k.GeoLiteLookupResult
import com.netonstream.geolite4k.GeoLiteResolver
import neton.core.config.ConfigLoader

/**
 * Account-facing adapter around GeoLite4K. The database is loaded once and all
 * subsequent lookups are local in-memory reads.
 */
class AccountGeoLocationService private constructor(
    private val resolver: GeoLiteResolver,
) {
    companion object {
        private const val NOT_CONFIGURED = "GeoLite2 库未设置"
        private const val INVALID_DATABASE = "GeoLite2 库不可用"

        private val configuredInstance: AccountGeoLocationService by lazy {
            val config = ConfigLoader.loadModuleConfig("geolite")
            val path = ConfigLoader.getString(config, "path")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: GeoLite4K.DEFAULT_DATABASE_PATH
            AccountGeoLocationService(GeoLite4K.open(path))
        }

        fun fromConfig(): AccountGeoLocationService = configuredInstance
    }

    fun resolve(ip: String?): String? {
        if (ip.isNullOrBlank()) return null
        return when (val result = resolver.lookup(ip)) {
            is GeoLiteLookupResult.Found -> result.location.displayName
            is GeoLiteLookupResult.DatabaseUnavailable -> NOT_CONFIGURED
            is GeoLiteLookupResult.DatabaseInvalid -> INVALID_DATABASE
            GeoLiteLookupResult.NotFound -> "未知地区"
            is GeoLiteLookupResult.InvalidIp -> "IP 地址无效"
        }
    }
}
