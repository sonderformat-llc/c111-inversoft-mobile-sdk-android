package io.fusionauth.mobilesdk

import kotlinx.serialization.Serializable
import java.util.logging.Logger

/**
 * AuthenticationConfiguration is a data class that represents the configuration for authentication.
 *
 * @property clientId The client ID used for authentication.
 * @property fusionAuthUrl The URL of the FusionAuth server.
 * @property tenant The tenant ID for the FusionAuth server. (Optional)
 * @property allowUnsecureConnection Flag to allow unsecure connections. Default is false.
 */
@Serializable
data class AuthenticationConfiguration(
    val clientId: String,
    val fusionAuthUrl: String,
    val tenant: String? = null,
    val allowUnsecureConnection: Boolean = false,
) {
    init {
        if (!allowUnsecureConnection) {
            Logger.getLogger("FusionAuth Mobile SDK")
                .warning("Unsecure connections should only be used for testing")
        }
    }
}
