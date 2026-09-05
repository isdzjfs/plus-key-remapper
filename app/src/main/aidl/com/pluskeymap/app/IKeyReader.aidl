package com.pluskeymap.app;
import com.pluskeymap.app.IKeyEventListener;

interface IKeyReader {
    void start(IKeyEventListener listener) = 0;
    void stop() = 1;
    boolean isReading() = 2;
    void destroy() = 16777114;
}
