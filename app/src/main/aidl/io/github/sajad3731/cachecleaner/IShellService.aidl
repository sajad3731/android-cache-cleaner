package io.github.sajad3731.cachecleaner;

interface IShellService {
    /** Reserved transaction code Shizuku uses to stop the service. */
    void destroy() = 16777114;

    /** Asks Android to free every app's cache. Returns the command's exit code. */
    int trimCaches() = 1;

    /** Clears one app's cache. Works only when the service runs as root. */
    int clearCache(String packageName, int userId) = 2;
}
