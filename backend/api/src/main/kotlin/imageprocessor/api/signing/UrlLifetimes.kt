package imageprocessor.api.signing

import java.time.Duration

// The upload URL must expire before its task does; retention_days is at least 1.
internal val UPLOAD_URL_LIFETIME: Duration = Duration.ofMinutes(10)
internal val LINK_LIFETIME: Duration = Duration.ofMinutes(60)
