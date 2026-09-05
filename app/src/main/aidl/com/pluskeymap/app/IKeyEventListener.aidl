package com.pluskeymap.app;

oneway interface IKeyEventListener {
    void onReady(String device);
    void onKey(boolean down, long eventTimeMs);
    void onError(String message);
}
