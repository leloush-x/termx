package app.termx.diagnostics;

interface IPhantomSettingsService {
    String readGlobal(String key) = 1;
    String writeGlobal(String key, String value) = 2;
    String deleteGlobal(String key) = 3;
    void destroy() = 16777114;
}
