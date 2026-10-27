package com.enweilo.appaudiorouter;

interface IRouterService {
    // Shizuku 規定的結束服務代碼
    void destroy() = 16777114;

    String probe() = 1;
    String listDevices() = 2;
    String route(int uid, int deviceId, boolean useShellContext) = 3;
    String clearAll() = 4;
    String activeRoutes() = 5;
}
