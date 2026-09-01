package com.pluskeymap.app;

public class ActionConfig {

    public static final int ACTION_NONE           = 0;
    public static final int ACTION_FLASHLIGHT     = 1;
    public static final int ACTION_LAUNCH_APP     = 3;
    public static final int ACTION_VOLUME_UP      = 4;
    public static final int ACTION_VOLUME_DOWN    = 5;
    public static final int ACTION_MEDIA_PLAY     = 6;
    public static final int ACTION_MEDIA_NEXT     = 7;
    public static final int ACTION_MEDIA_PREV     = 8;
    public static final int ACTION_DND_TOGGLE     = 9;  // retired
    public static final int ACTION_RINGER_TOGGLE  = 10; // retired
    public static final int ACTION_CUSTOM_INTENT  = 11;
    public static final int ACTION_CAMERA_SHUTTER = 12;

    public static final String[] ACTION_LABELS = {
            "无",
            "开关手电筒",
            null,           // slot 2 retired (was Screenshot) -- kept to avoid prefs remapping
            null,           // slot 3 retired (was Launch App) -- removed, background launch unreliable
            "调高音量",
            "调低音量",
            "播放/暂停媒体",
            "下一曲",
            "上一曲",
            null,           // slot 9 retired (was Toggle Do Not Disturb)
            "切换响铃/振动/勿扰模式",
            "自定义 Intent",
            "相机快门（仅相机应用）"
    };
}
