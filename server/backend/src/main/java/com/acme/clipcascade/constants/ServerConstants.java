package com.acme.clipcascade.constants;

public class ServerConstants {
    // App version
    public static final String APP_VERSION = "1.0.0";

    // Version URL
    public static final String VERSION_URL = "https://raw.githubusercontent.com/C0R3DMP/SkyClip/main/version.json";

    // Metadata URL
    public static final String METADATA_URL = "https://raw.githubusercontent.com/C0R3DMP/SkyClip/main/metadata.json";

    // Help URL
    public static final String HELP_URL = "https://github.com/C0R3DMP/SkyClip/blob/main/README.md";

    // Captcha Session ID
    public static final String CAPTCHA_SESSION_ID = "captcha_answer";

    // Captcha Case Sensitive
    public static final boolean CAPTCHA_CASE_SENSITIVE = false;

    // BFA Tracker Log Path
    public static final String BFA_TRACKER_LOG_PATH = "logs/bfa_tracker_logs";

    // BFA Tracker Cache Path
    public static final String BFA_TRACKER_CACHE_PATH = "database/bfa_tracker_cache";

    // Heartbeat Interval in milliseconds (P2S mode)
    public static final int HEARTBEAT_SEND_INTERVAL_P2S = 20000; // 20 seconds
    public static final int HEARTBEAT_RECEIVE_INTERVAL_P2S = 0; // 0 seconds (disabled)

    // Heartbeat Interval in milliseconds (P2P mode)
    public static final int HEARTBEAT_SEND_INTERVAL_P2P = 40000; // 40 seconds
    public static final int HEARTBEAT_RECEIVE_INTERVAL_P2P = 120000; // 120 seconds (x3)

    private ServerConstants() {
        // private constructor to prevent instantiation
    }
}
