package fr.autotnt;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class AutoTntClient implements ClientModInitializer {

    /** Largeur de la ligne de TNT (blocs). */
    public static final int WIDTH = 5;
    /** Distance devant le joueur (blocs). */
    public static final double DISTANCE = 1.0;
    /** Seuil de vitesse de recul. */
    public static final double BACK_THRESHOLD = 0.05;
    /** Profondeur max de recherche du sol. */
    private static final int MAX_GROUND_SCAN = 64;

    private static KeyMapping toggleKey;
    private static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("autotnt", "main"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autotnt.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("AutoTNT: " + (enabled ? "ON" : "OFF")), true);
            }
        }

        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (!enabled || player == null || level == null || mc.gameMode == null || mc.screen != null) {
            return;
        }
        if (!isMovingBackwards(player)) {
            return;
        }

        int tntSlot = findTntInHotbar(player);
        if (tntSlot < 0) {
            return;
        }

        // Direction horizontale du regard
        Vec3 look = player.getLookAngle();
        double lx = look.x, lz = look.z;
        double len = Math.sqrt(lx * lx + lz * lz);
        if (len < 1.0E-4) {
            return;
        }
        lx /= len;
        lz /= len;
        // Vecteur "droite" (perpendiculaire)
        double rx = -lz, rz = lx;

        // Sol sous les pieds
        BlockPos feet = player.blockPosition();
        Integer groundY = findGroundY(level, feet);
        int targetY = (groundY != null) ? groundY + 1 : feet.getY();

        double cx = player.getX() + lx * DISTANCE;
        double cz = player.getZ() + lz * DISTANCE;

        int originalSlot = player.getInventory().getSelectedSlot();
        boolean switched = false;
        boolean placedAny = false;

        int half = WIDTH / 2;
        int start = -half;
        int end = (WIDTH % 2 == 0) ? half - 1 : half;

        for (int i = start; i <= end; i++) {
            double px = cx + rx * i;
            double pz = cz + rz * i;
            BlockPos target = BlockPos.containing(px, targetY, pz);

            BlockState current = level.getBlockState(target);
            if (!current.canBeReplaced()) {
                continue; // case déjà occupée
            }

            BlockHitResult hit = findHit(level, target);
            if (hit == null) {
                continue;
            }

            if (!switched) {
                if (originalSlot != tntSlot) {
                    player.getInventory().setSelectedSlot(tntSlot);
                }
                switched = true;
            }

            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            placedAny = true;
        }

        if (switched && originalSlot != tntSlot) {
            player.getInventory().setSelectedSlot(originalSlot);
        }
    }

    /** Recul : vitesse horizontale opposée au regard (composante > seuil). */
    private static boolean isMovingBackwards(LocalPlayer player) {
        Vec3 vel = player.getDeltaMovement();
        Vec3 look = player.getLookAngle();
        double len = Math.sqrt(look.x * look.x + look.z * look.z);
        if (len < 1.0E-4) {
            return false;
        }
        double along = (vel.x * look.x + vel.z * look.z) / len;
        return along < -BACK_THRESHOLD;
    }

    private static int findTntInHotbar(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(Items.TNT)) {
                return i;
            }
        }
        return -1;
    }

    /** Y du premier bloc plein sous les pieds du joueur, ou null (vol). */
    private static Integer findGroundY(Level level, BlockPos feet) {
        BlockPos.MutableBlockPos p = feet.mutable();
        for (int d = 0; d < MAX_GROUND_SCAN; d++) {
            p.set(feet.getX(), feet.getY() - 1 - d, feet.getZ());
            if (p.getY() < level.getMinY()) {
                break;
            }
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.canBeReplaced()) {
                // Ne considérer comme "sol" que si proche (évite de viser le fond en vol)
                return (d <= 2) ? p.getY() : null;
            }
        }
        return null;
    }

    /** DOWN en premier (poser sur la face du dessus du bloc sous la cible), puis les autres faces. */
    private static BlockHitResult findHit(Level level, BlockPos target) {
        Direction[] order = {
                Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                Direction.WEST, Direction.EAST, Direction.UP
        };
        for (Direction dir : order) {
            BlockPos support = target.relative(dir);
            BlockState s = level.getBlockState(support);
            if (s.isAir() || s.canBeReplaced()) {
                continue;
            }
            Direction face = dir.getOpposite();
            Vec3 pos = Vec3.atCenterOf(support).add(
                    face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(pos, face, support, false);
        }
        return null;
    }
}
