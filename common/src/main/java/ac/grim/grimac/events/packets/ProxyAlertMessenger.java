package ac.grim.grimac.events.packets;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.Map;

// TODO (Cross-Platform) ensure this is correct, and modify to only check appropriate files for each platform
public class ProxyAlertMessenger extends PacketListenerAbstract {
    @Getter private static boolean usingProxy;

    public ProxyAlertMessenger() {
        usingProxy = ProxyAlertMessenger.getBooleanFromFile("spigot.yml", "settings.bungeecord")
                || ProxyAlertMessenger.getBooleanFromFile("paper.yml", "settings.velocity-support.enabled")
                || (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_19) && ProxyAlertMessenger.getBooleanFromFile("config/paper-global.yml", "proxies.velocity.enabled"));

        if (usingProxy) {
            LogUtil.info("Registering an outgoing plugin channel...");
            GrimAPI.INSTANCE.getPlatformServer().registerOutgoingPluginChannel("BungeeCord");
        }
    }

    public static void sendPluginMessage(String message) {
        if (!canSendAlerts())
            return;

        ByteArrayOutputStream messageBytes = new ByteArrayOutputStream();
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");
        out.writeUTF("ONLINE");
        out.writeUTF("GRIMAC");

        try {
            new DataOutputStream(messageBytes).writeUTF(message);
        } catch (IOException exception) {
            LogUtil.error("Something went wrong whilst forwarding an alert to other servers!", exception);
            return;
        }

        out.writeShort(messageBytes.toByteArray().length);
        out.write(messageBytes.toByteArray());

        Iterables.getFirst(GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers(), null).sendPluginMessage("BungeeCord", out.toByteArray());
    }

    public static boolean canSendAlerts() {
        return usingProxy && GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("alerts.proxy.send", false) && !GrimAPI.INSTANCE.getPlatformPlayerFactory().getOnlinePlayers().isEmpty();
    }

    public static boolean canReceiveAlerts() {
        return usingProxy && GrimAPI.INSTANCE.getConfigManager().getConfig().getBooleanElse("alerts.proxy.receive", false) && GrimAPI.INSTANCE.getAlertManager().hasAlertListeners();
    }

    // TODO (Cross-Platform) check if new getBooleanFromFile impl is correct
    private static boolean getBooleanFromFile(String pathToFile, String pathToValue) {
        File file = new File(pathToFile);
        if (!file.exists()) return false;

        try (InputStream in = new FileInputStream(file)) {
            Object current = new Yaml().load(in);

            for (String part : pathToValue.split("\\.")) {
                if (!(current instanceof Map map)) return false;
                current = map.get(part);
            }

            return Boolean.TRUE.equals(current);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onPacketReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE || !ProxyAlertMessenger.canReceiveAlerts())
            return;

        WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);

        if (!wrapper.getChannelName().equals("BungeeCord") && !wrapper.getChannelName().equals("bungeecord:main"))
            return;

        // NOTE (#2868): a client connected directly to the backend can send arbitrary bytes on the
        // BungeeCord channel. This whole block therefore parses fully untrusted input and must never
        // throw out of the PacketEvents listener. readUTF()/readShort()/readFully() can throw
        // (IllegalStateException) on truncated data and a negative length would throw
        // NegativeArraySizeException — none of which were previously guarded.
        final Component message;
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(wrapper.getData());

            if (!in.readUTF().equals("GRIMAC")) return;

            int length = in.readShort();
            if (length < 0) return; // malformed / spoofed payload
            byte[] messageBytes = new byte[length];
            in.readFully(messageBytes);

            String alert = new DataInputStream(new ByteArrayInputStream(messageBytes)).readUTF();
            message = MessageUtil.miniMessage(alert);
        } catch (Exception exception) {
            // Malformed payload from a direct/hostile connection — drop it silently.
            return;
        }

        // WARNING (#2868): Grim cannot currently verify that this message actually originated from the
        // proxy rather than a directly-connected client, so a well-formed message here may be spoofed.
        // See the fix writeup for the recommended shared-secret mitigation and deployment guidance.
        GrimAPI.INSTANCE.getAlertManager().sendAlert(message, null);
    }
}
