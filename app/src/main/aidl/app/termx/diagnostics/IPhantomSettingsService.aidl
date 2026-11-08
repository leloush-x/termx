package app.termx.diagnostics;

interface IPhantomSettingsService {
    String readGlobal(String key);
    String writeGlobal(String key, String value);
    String deleteGlobal(String key);
    void destroy() = 16777114;
}
