package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.bukkit.utils.convert.BukkitConversionUtils;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.PistonData;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class PistonEvent implements Listener {

    private final Material SLIME_BLOCK = Material.getMaterial("SLIME_BLOCK");
    private final Material HONEY_BLOCK = Material.getMaterial("HONEY_BLOCK");

    private static final double MAX_HORIZONTAL_DISTANCE = 24.0;
    private static final double MAX_VERTICAL_DISTANCE = 64.0;

    private static final Logger LOGGER = Logger.getLogger("GrimPiston");

    // accuracy isn't that important, it's close enough and performant
    private static boolean isCloseEnough(Vector3i vectorA, Vector3d vectorB) {
        return Math.abs(vectorA.getX() - vectorB.getX()) <= MAX_HORIZONTAL_DISTANCE
                && Math.abs(vectorA.getY() - vectorB.getY()) <= MAX_VERTICAL_DISTANCE
                && Math.abs(vectorA.getZ() - vectorB.getZ()) <= MAX_HORIZONTAL_DISTANCE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonPushEvent(BlockPistonExtendEvent event) {
        boolean hasSlimeBlock = false;
        boolean hasHoneyBlock = false;

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        List<Block> movedBlocks = event.getBlocks();

        for (Block block : movedBlocks) {
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX(),
                            block.getY(),
                            block.getZ()));
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX() + event.getDirection().getModX(),
                            block.getY() + event.getDirection().getModY(),
                            block.getZ() + event.getDirection().getModZ()));

            if (block.getType() == SLIME_BLOCK) hasSlimeBlock = true;
            if (block.getType() == HONEY_BLOCK) hasHoneyBlock = true;
        }

        Block piston = event.getBlock();

        boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                .offset(piston.getX() + event.getDirection().getModX(),
                        piston.getY() + event.getDirection().getModY(),
                        piston.getZ() + event.getDirection().getModZ()));

        final int chunkX = event.getBlock().getX() >> 4;
        final int chunkZ = event.getBlock().getZ() >> 4;
        final BlockFace blockFace = BukkitConversionUtils.fromBukkitFace(event.getDirection());
        final Vector3i sourcePos = new Vector3i(piston.getX(), piston.getY(), piston.getZ());

        // Log the piston extension event
        StringBuilder pistonLog = new StringBuilder();
        pistonLog.append("[PISTON EXTEND] pos=(")
                .append(piston.getX()).append(",")
                .append(piston.getY()).append(",")
                .append(piston.getZ()).append(")")
                .append(" dir=").append(blockFace)
                .append(" movedBlocks=").append(movedBlocks.size())
                .append(" headTarget=(")
                .append(piston.getX() + event.getDirection().getModX()).append(",")
                .append(piston.getY() + event.getDirection().getModY()).append(",")
                .append(piston.getZ() + event.getDirection().getModZ()).append(")")
                .append(" slime=").append(hasSlimeBlock)
                .append(" honey=").append(hasHoneyBlock);
        for (int i = 0; i < movedBlocks.size(); i++) {
            Block b = movedBlocks.get(i);
            pistonLog.append("\n  block[").append(i).append("]: type=").append(b.getType())
                    .append(" from=(").append(b.getX()).append(",").append(b.getY()).append(",").append(b.getZ()).append(")")
                    .append(" to=(")
                    .append(b.getX() + event.getDirection().getModX()).append(",")
                    .append(b.getY() + event.getDirection().getModY()).append(",")
                    .append(b.getZ() + event.getDirection().getModZ()).append(")");
        }
        LOGGER.info(pistonLog.toString());

        for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (isCloseEnough(sourcePos, player.compensatedEntities.self.trackedServerPosition.getPos()) && player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)) {
                final int lastTrans = player.lastTransactionSent.get();
                PistonData data = new PistonData(blockFace, boxes, lastTrans, true, hasSlimeBlock, hasHoneyBlock);

                LOGGER.info("[PISTON EXTEND] Registering for player " + player.user.getName()
                        + " transaction=" + lastTrans
                        + " totalBoxes=" + boxes.size());

                player.latencyUtils.addRealTimeTaskAsync(lastTrans, () -> player.compensatedWorld.activePistons.add(data));
            }
        }
    }


    // For some unknown reason, bukkit handles this stupidly
    // Calls the event once without blocks
    // Calls it again with blocks -
    // This wouldn't be an issue if it didn't flip the direction of the event
    // What a stupid system, again I can stand mojang doing stupid stuff but not other mod makers
    //
    // This gives too much of a lenience when retracting
    // But as this is insanely gitchy due to bukkit I don't care.
    // The lenience is never actually given because of collisions hitting the piston base
    // Blocks outside the piston head give only as much lenience as needed
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetractEvent(BlockPistonRetractEvent event) {
        boolean hasSlimeBlock = false;
        boolean hasHoneyBlock = false;

        List<SimpleCollisionBox> boxes = new ArrayList<>();
        BlockFace face = BukkitConversionUtils.fromBukkitFace(event.getDirection());
        List<Block> movedBlocks = event.getBlocks();

        if (movedBlocks.isEmpty()) {
            Block piston = event.getBlock();
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(piston.getX() + face.getModX(),
                            piston.getY() + face.getModY(),
                            piston.getZ() + face.getModZ()));
        }

        for (Block block : movedBlocks) {
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX(), block.getY(), block.getZ()));
            boxes.add(new SimpleCollisionBox(0, 0, 0, 1, 1, 1, true)
                    .offset(block.getX() + face.getModX(), block.getY() + face.getModY(), block.getZ() + face.getModZ()));

            if (block.getType() == SLIME_BLOCK) hasSlimeBlock = true;
            if (block.getType() == HONEY_BLOCK) hasHoneyBlock = true;
        }

        Block piston = event.getBlock();
        final int chunkX = piston.getX() >> 4;
        final int chunkZ = piston.getZ() >> 4;
        Vector3i sourcePos = new Vector3i(piston.getX(), piston.getY(), piston.getZ());

        // Log the piston retraction event
        StringBuilder pistonLog = new StringBuilder();
        pistonLog.append("[PISTON RETRACT] pos=(")
                .append(piston.getX()).append(",")
                .append(piston.getY()).append(",")
                .append(piston.getZ()).append(")")
                .append(" dir=").append(face)
                .append(" movedBlocks=").append(movedBlocks.size())
                .append(" emptyEvent=").append(movedBlocks.isEmpty())
                .append(" slime=").append(hasSlimeBlock)
                .append(" honey=").append(hasHoneyBlock);
        for (int i = 0; i < movedBlocks.size(); i++) {
            Block b = movedBlocks.get(i);
            pistonLog.append("\n  block[").append(i).append("]: type=").append(b.getType())
                    .append(" from=(").append(b.getX()).append(",").append(b.getY()).append(",").append(b.getZ()).append(")")
                    .append(" to=(")
                    .append(b.getX() + face.getModX()).append(",")
                    .append(b.getY() + face.getModY()).append(",")
                    .append(b.getZ() + face.getModZ()).append(")");
        }
        LOGGER.info(pistonLog.toString());

        for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (isCloseEnough(sourcePos, player.compensatedEntities.self.trackedServerPosition.getPos()) && player.compensatedWorld.isChunkLoaded(chunkX, chunkZ)) {
                int lastTrans = player.lastTransactionSent.get();
                PistonData data = new PistonData(face, boxes, lastTrans, false, hasSlimeBlock, hasHoneyBlock);

                LOGGER.info("[PISTON RETRACT] Registering for player " + player.user.getName()
                        + " transaction=" + lastTrans
                        + " totalBoxes=" + boxes.size());

                player.latencyUtils.addRealTimeTaskAsync(lastTrans, () -> player.compensatedWorld.activePistons.add(data));
            }
        }
    }
}
