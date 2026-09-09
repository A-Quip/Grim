package ac.grim.grimac.manager.init.start;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.commands.GrimVersion;
import ac.grim.grimac.utils.anticheat.LogUtil;

public class UpdateChecker implements StartableInitable {
    @Override
    public void start() {
        if (GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("check-for-updates", true)) {
            try {
                GrimVersion.checkForUpdatesAsync(GrimAPI.INSTANCE.getPlatformServer().getConsoleSender());
            } catch (Throwable t) {
                // The update checker is a best-effort, non-essential feature and must never abort
                // server startup. On some Fabric setups a conflicting Adventure/text pipeline throws
                // NoClassDefFoundError while rendering the console message; log and continue. (#2835)
                // Throwable (not Exception) is intentional: the failure is a NoClassDefFoundError.
                LogUtil.error("Skipping GrimAC update check due to an error while sending the version message.", t);
            }
        }
    }
}
