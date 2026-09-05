package com.pluskeymap.app;
import com.pluskeymap.app.IKeyEventListener;

interface IKeyReader {
    void start(IKeyEventListener listener) = 0;
    void stop() = 1;
    boolean isReading() = 2;
    void setWakeScreenOnDown(boolean enabled) = 3;
    void destroy() = 16777114;
}
