package rosegold.gumtuneclient.modules.macro;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.BlockColored;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import rosegold.gumtuneclient.GumTuneClient;
import rosegold.gumtuneclient.config.GumTuneClientConfig;
import rosegold.gumtuneclient.config.pages.GemstoneMacroAOTVRoutes;
import rosegold.gumtuneclient.config.pages.GemstoneTypeFilter;
import rosegold.gumtuneclient.events.PlayerMoveEvent;
import rosegold.gumtuneclient.utils.*;
import rosegold.gumtuneclient.utils.objects.Waypoint;
import rosegold.gumtuneclient.utils.objects.WaypointList;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

public class GemstoneMacro {
    private static boolean enabled;
    public static HashSet<WaypointList> allPaths = new HashSet<>();

    private enum GemMacroState {
        AOTV_SETUP,
        AOTV_ROTATE,
        AOTV_TELEPORT,
        SETUP_ROTATE_TO_BLOCK,
        ROTATE_TO_BLOCK,
        MINING,
        SPAWN_ARMADILLO,
        MOUNT_ARMADILLO,
        ROTATE_ARMADILLO,
        DISMOUNT_ARMADILLO
    }

    private static GemMacroState gemMacroState = GemMacroState.AOTV_SETUP;
    private static BlockPos current;
    private static BlockPos lastPos;
    private static boolean mining = false;
    private static long timestamp = System.currentTimeMillis();
    private static long startTimestamp = System.currentTimeMillis();
    private static int currentIndex = -1;
    public static HashSet<BlockPos> blocksInTheWay = new HashSet<>();
    public static HashSet<BlockPos> extraBlocksInTheWay = new HashSet<>();

    @SubscribeEvent
    public void onOverlayRender(RenderGameOverlayEvent.Post event) {
        if (!GumTuneClientConfig.aotvGemstoneMacro) return;
        if (LocationUtils.currentIsland != LocationUtils.Island.CRYSTAL_HOLLOWS) return;
        if (!enabled) return;
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            FontUtils.drawScaledString("Gemstone Macro State: " + gemMacroState, 1, 80, 40, true);
            FontUtils.drawScaledString("Index: " + currentIndex, 1, 80, 50, true);
            FontUtils.drawScaledString("Uptime: " + StringUtils.millisecondFormatTime(System.currentTimeMillis() - startTimestamp), 1, 80, 60, true);
        }
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (!GumTuneClientConfig.aotvGemstoneMacro) return;
        if (GumTuneClient.mc.currentScreen != null) return;
        if (LocationUtils.currentIsland != LocationUtils.Island.CRYSTAL_HOLLOWS) return;
        if (Keyboard.getEventKeyState()) return;
        int eventKey = Keyboard.getEventKey();

        ArrayList<Integer> keyBindsToggle = GumTuneClientConfig.gemstoneMacroToggleKeyBind.getKeyBinds();
        if (keyBindsToggle.size() > 0 && keyBindsToggle.get(0) == eventKey) {
            enabled = !enabled;
            ModUtils.sendMessage((enabled ? "Enabled" : "Disabled") + " Gemstone Macro");
            if (!enabled) {
                ModUtils.sendMessage("Final Gemstone Macro Uptime: " + StringUtils.millisecondFormatTime(System.currentTimeMillis() - startTimestamp));

                if (current != null && GumTuneClient.mc.thePlayer != null) {
                    GumTuneClient.mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(
                            C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK,
                            current,
                            EnumFacing.DOWN)
                    );
                }

                RotationUtils.reset();
                current = null;
                KeyBinding.setKeyBindState(GumTuneClient.mc.gameSettings.keyBindSneak.getKeyCode(), false);
            } else {
                if (GumTuneClientConfig.aotvGemstoneMacroResetStateOnToggle) {
                    gemMacroState = GemMacroState.AOTV_SETUP;
                    currentIndex = -1;
                }

                startTimestamp = System.currentTimeMillis();
            }
        }

        ArrayList<Integer> keyBindsAddToPath = GumTuneClientConfig.gemstoneMacroAddToPathKeyBind.getKeyBinds();
        if (keyBindsAddToPath.size() > 0 && keyBindsAddToPath.get(0) == eventKey) {
            if (GumTuneClient.mc.objectMouseOver != null && GumTuneClient.mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                addBlockToPath(GumTuneClient.mc.objectMouseOver.getBlockPos());

                GemstoneMacroAOTVRoutes.redrawRoutes();
                saveConfig();
            } else {
                ModUtils.sendMessage("Invalid block!");
            }
        }


    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!GumTuneClientConfig.aotvGemstoneMacro) return;
        if (LocationUtils.currentIsland != LocationUtils.Island.CRYSTAL_HOLLOWS) return;
        if (current != null) {
            RenderUtils.renderEspBox(current, event.partialTicks, Color.BLUE.getRGB());
        }

        WaypointList activeList = getActiveWaypointList();
        if (activeList == null) return;

        activeList.waypoints.forEach((integer, waypoint) -> {
            RenderUtils.renderEspBox(new BlockPos(waypoint.x, waypoint.y, waypoint.z), event.partialTicks, Color.CYAN.getRGB(), 0.2f);
            RenderUtils.renderWaypointText(integer.toString(), new BlockPos(waypoint.x, waypoint.y, waypoint.z), event.partialTicks);

            int nextIndex = activeList.getNextIndex(integer);

            if (activeList.waypoints.get(nextIndex) != null) {
                RenderUtils.drawLine(
                        new Vec3(waypoint.x + 0.5, waypoint.y + 2.62, waypoint.z + 0.5),
                        new Vec3(activeList.waypoints.get(nextIndex).x + 0.5, activeList.waypoints.get(nextIndex).y + 0.5, activeList.waypoints.get(nextIndex).z + 0.5),
                        1,
                        event.partialTicks
                );
            }
        });

        if (GumTuneClientConfig.aotvGemstoneShowBlocksBlockingPath) {
            blocksInTheWay.forEach(blockPos -> RenderUtils.renderEspBox(blockPos, event.partialTicks, Color.RED.getRGB(), 0.2f));
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (!GumTuneClientConfig.aotvGemstoneMacro) return;
        if (LocationUtils.currentIsland != LocationUtils.Island.CRYSTAL_HOLLOWS) return;

        WaypointList activeList = getActiveWaypointList();
        if (activeList == null) return;

        blocksInTheWay.clear();
        extraBlocksInTheWay.clear();

        if (GumTuneClientConfig.aotvGemstoneShowBlocksBlockingPath || GumTuneClientConfig.nukerShape == 4) {
            activeList.waypoints.forEach((integer, waypoint) -> {
                int nextIndex = activeList.getNextIndex(integer);

                if (activeList.waypoints.get(nextIndex) != null) {
                    MovingObjectPosition movingObjectPosition = BlockUtils.rayTraceBlocks(
                            new Vec3(activeList.waypoints.get(integer).x + 0.5, activeList.waypoints.get(integer).y + 2.62, activeList.waypoints.get(integer).z + 0.5),
                            new Vec3(activeList.waypoints.get(nextIndex).x + 0.5, activeList.waypoints.get(nextIndex).y + 0.5, activeList.waypoints.get(nextIndex).z + 0.5),
                            true,
                            true,
                            false,
                            x -> x == Blocks.chest,
                            false,
                            true
                    );
                    if (movingObjectPosition != null && movingObjectPosition.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && !movingObjectPosition.getBlockPos().equals(new BlockPos(activeList.waypoints.get(nextIndex).x + 0.5, activeList.waypoints.get(nextIndex).y + 0.5, activeList.waypoints.get(nextIndex).z + 0.5))) {
                        blocksInTheWay.add(movingObjectPosition.getBlockPos());
                        extraBlocksInTheWay.add(movingObjectPosition.getBlockPos());
                        extraBlocksInTheWay.add(movingObjectPosition.getBlockPos().add(0, -1, 0));
                        for (EnumFacing enumFacing : EnumFacing.HORIZONTALS) {
                            extraBlocksInTheWay.add(movingObjectPosition.getBlockPos().add(enumFacing.getDirectionVec()));
                            extraBlocksInTheWay.add(movingObjectPosition.getBlockPos().add(enumFacing.getDirectionVec()).add(0, -1, 0));
                        }
                    }
                }
            });
        }

        if (!enabled) return;
        if (event.phase == TickEvent.Phase.START) return;
        if (GumTuneClient.mc.currentScreen != null && !(GumTuneClient.mc.currentScreen instanceof GuiChat)) {
            KeyBinding.setKeyBindState(GumTuneClient.mc.gameSettings.keyBindSneak.getKeyCode(), false);
            return;
        }

        KeyBinding.setKeyBindState(GumTuneClient.mc.gameSettings.keyBindSneak.getKeyCode(), true);

        switch (gemMacroState) {
            case AOTV_SETUP:
                Waypoint nextWaypoint = activeList.waypoints.get(activeList.getNextIndex(currentIndex));
                if (nextWaypoint != null) {
                    BlockPos nextBlockPos = new BlockPos(nextWaypoint.x, nextWaypoint.y, nextWaypoint.z);
                    ArrayList<Vec3> possibleSpots = BlockUtils.getViablePointsOnBlock(nextBlockPos, null, 60, true, true);
                    if (possibleSpots.size() > 0) {
                        RotationUtils.serverSmoothLook(RotationUtils.getRotation(possibleSpots.get(0)), GumTuneClientConfig.aotvGemstoneMacroRotationSpeed);
                        gemMacroState = GemMacroState.AOTV_ROTATE;
                        timestamp = System.currentTimeMillis();
                        lastPos = GumTuneClient.mc.thePlayer.getPosition();
                        break;
                    }
                }
                break;
            case AOTV_ROTATE:
                if (System.currentTimeMillis() - timestamp > GumTuneClientConfig.aotvGemstoneMacroRotationSpeed + 500) {
                    int aotvSlot = InventoryUtils.findItemInHotbarSkyblockId("ASPECT_OF_THE_VOID");
                    if (aotvSlot != -1) {
                        GumTuneClient.mc.thePlayer.inventory.currentItem = aotvSlot;
                        GumTuneClient.mc.playerController.sendUseItem(GumTuneClient.mc.thePlayer, GumTuneClient.mc.theWorld, GumTuneClient.mc.thePlayer.getHeldItem());
                    }
                    gemMacroState = GemMacroState.AOTV_TELEPORT;
                    timestamp = System.currentTimeMillis();
                }
                break;
            case AOTV_TELEPORT:
                if (lastPos.equals(GumTuneClient.mc.thePlayer.getPosition())) {
                    break;
                }

                Waypoint waypoint = activeList.waypoints.get(activeList.getNextIndex(currentIndex));
                if (waypoint != null) {
                    BlockPos blockPos = new BlockPos(waypoint.x, waypoint.y, waypoint.z);
                    if (!GumTuneClient.mc.thePlayer.getPosition().add(-1, -1, -1).equals(blockPos)) {
                        ModUtils.sendMessage("AOTV teleport missed! retrying");
                        timestamp = System.currentTimeMillis();
                        gemMacroState = GemMacroState.AOTV_SETUP;
                        break;
                    }

                    timestamp = System.currentTimeMillis();
                    gemMacroState = GumTuneClientConfig.aotvGemstoneMacroMiningMode == 2 ? GemMacroState.SPAWN_ARMADILLO : GemMacroState.SETUP_ROTATE_TO_BLOCK;
                }
                break;
            case SETUP_ROTATE_TO_BLOCK:
                current = BlockUtils.getEasiestBlock(5, 5, 4, this::canMine);

                if (current != null) {
                    int drillSlot = InventoryUtils.findItemInHotbar("Drill");
                    if (drillSlot != -1) {
                        GumTuneClient.mc.thePlayer.inventory.currentItem = drillSlot;
                    } else {
                        int pickaxeSlot = InventoryUtils.findItemInHotbar("Pickaxe");
                        if (pickaxeSlot != -1) {
                            GumTuneClient.mc.thePlayer.inventory.currentItem = pickaxeSlot;
                        } else {
                            int gauntletSlot = InventoryUtils.findItemInHotbar("Gauntlet");
                            if (gauntletSlot != -1) {
                                GumTuneClient.mc.thePlayer.inventory.currentItem = gauntletSlot;
                            }
                        }
                    }

                    RotationUtils.serverSmoothLook(RotationUtils.getRotation(current), GumTuneClientConfig.aotvGemstoneMacroRotationSpeed);
                    gemMacroState = GumTuneClientConfig.aotvGemstoneMacroMiningMode == 1 ? GemMacroState.MINING : GemMacroState.ROTATE_TO_BLOCK;
                } else {
                    gemMacroState = GemMacroState.AOTV_SETUP;
                    currentIndex = activeList.getNextIndex(currentIndex);
                }

                timestamp = System.currentTimeMillis();
                break;
            case ROTATE_TO_BLOCK:
                if (System.currentTimeMillis() - timestamp > GumTuneClientConfig.aotvGemstoneMacroRotationSpeed) {
                    gemMacroState = GemMacroState.MINING;
                    timestamp = System.currentTimeMillis();
                }
                break;
            case MINING:
                if (current != null) {
                    if (mining && PlayerUtils.pickaxeAbilityReady) {
                        GumTuneClient.mc.playerController.sendUseItem(
                                GumTuneClient.mc.thePlayer,
                                GumTuneClient.mc.theWorld,
                                GumTuneClient.mc.thePlayer.getHeldItem()
                        );
                    }

                    if (!mining) {
                        breakBlock(current);
                        mining = true;
                    }

                    if (System.currentTimeMillis() - timestamp > 3000) {
                        ModUtils.sendMessage("Stuck mining block, it's bad!");
                        current = null;
                        mining = false;
                        gemMacroState = GemMacroState.SETUP_ROTATE_TO_BLOCK;
                        timestamp = System.currentTimeMillis();
                        break;
                    }

                    GumTuneClient.mc.thePlayer.swingItem();

                    if (getBlockState(current).getBlock() != Blocks.stained_glass) {
                        current = null;
                        mining = false;
                        gemMacroState = GemMacroState.SETUP_ROTATE_TO_BLOCK;
                    }
                }
                break;
            case SPAWN_ARMADILLO:
                int rodSlot = InventoryUtils.findItemInHotbar("Rod");
                if (rodSlot != -1) {
                    GumTuneClient.mc.thePlayer.inventory.currentItem = rodSlot;
                }

                Entity armadillo = GumTuneClient.mc.theWorld.loadedEntityList.stream().filter(
                        Entity::hasCustomName
                ).filter(
                        entity -> StringUtils.removeFormatting(entity.getCustomNameTag()).endsWith(GumTuneClient.mc.thePlayer.getName() + " 's Armadillo")
                ).findAny().orElse(null);

                if (armadillo != null) {

                }
                break;
            case MOUNT_ARMADILLO:

                break;
            case ROTATE_ARMADILLO:
                break;
            case DISMOUNT_ARMADILLO:
                break;
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onUpdatePre(PlayerMoveEvent.Pre pre) {
        if (!GumTuneClientConfig.aotvGemstoneMacro) return;
        if (LocationUtils.currentIsland != LocationUtils.Island.CRYSTAL_HOLLOWS) return;
        if (!enabled) return;
        RotationUtils.updateServerLook();
    }

    private void breakBlock(BlockPos blockPos) {
        EnumFacing enumFacing = BlockUtils.calculateEnumfacing(new Vec3(blockPos).add(RandomUtils.randomVec()));
        if (enumFacing != null) {
            GumTuneClient.mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(
                    C07PacketPlayerDigging.Action.START_DESTROY_BLOCK,
                    blockPos,
                    enumFacing
            ));
        } else {
            GumTuneClient.mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(
                    C07PacketPlayerDigging.Action.START_DESTROY_BLOCK,
                    blockPos,
                    GumTuneClient.mc.thePlayer.getHorizontalFacing().getOpposite()
            ));
        }
    }

    public static void loadConfig() {
        try {
            Path path = Paths.get("./config/" + GumTuneClient.MODID + "/aotvGemstoneMacroPath.json");
            if (new File(path.toUri()).exists()) {
                allPaths = new Gson().fromJson(new String(Files.readAllBytes(path)), new TypeToken<HashSet<WaypointList>>() {
                }.getType());
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public static void saveConfig() {
        try {
            Files.write(Paths.get("./config/" + GumTuneClient.MODID + "/aotvGemstoneMacroPath.json"), new Gson().toJson(allPaths).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ioException) {
            ioException.printStackTrace();
            ModUtils.sendMessage("Failed saving Gemstone Macro path!");
        }
    }

    private boolean canMine(BlockPos blockPos) {
        IBlockState blockState = getBlockState(blockPos);

        if (((GumTuneClientConfig.aotvGemstoneMinePanes && blockState.getBlock() == Blocks.stained_glass_pane) || blockState.getBlock() == Blocks.stained_glass) &&
                (GemstoneTypeFilter.amber && getBlockState(blockPos).getValue(BlockColored.COLOR) == EnumDyeColor.ORANGE ||
                        GemstoneTypeFilter.jade && getBlockState(blockPos).getValue(BlockColored.COLOR) == EnumDyeColor.LIME ||
                        GemstoneTypeFilter.ruby && getBlockState(blockPos).getValue(BlockColored.COLOR) == EnumDyeColor.RED ||
                        GemstoneTypeFilter.topaz && getBlockState(blockPos).getValue(BlockColored.COLOR) == EnumDyeColor.YELLOW ||
                        GemstoneTypeFilter.sapphire && getBlockState(blockPos).getValue(BlockColored.COLOR) == EnumDyeColor.LIGHT_BLUE ||
                        GemstoneTypeFilter.amethyst && getBlockState(blockPos).getValue(BlockColored.COLOR) == EnumDyeColor.PURPLE ||
                        GemstoneTypeFilter.jasper && getBlockState(blockPos).getValue(BlockColored.COLOR) == EnumDyeColor.PINK
                )
        ) {
            return GumTuneClient.mc.thePlayer.getPositionEyes(1f).distanceTo(new Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5)) < GumTuneClient.mc.playerController.getBlockReachDistance();
        }

        return false;
    }

    private static IBlockState getBlockState(BlockPos blockPos) {
        return GumTuneClient.mc.theWorld.getBlockState(blockPos);
    }

    public static void addBlockToPath(BlockPos blockPos) {
        WaypointList activeList = getActiveWaypointList();
        if (activeList == null) {
            ModUtils.sendMessage("No active waypoint route selected!");
            return;
        }

        if (activeList.containsValue(blockPos)) {
            activeList.waypoints.remove(Objects.requireNonNull(activeList.getKey(blockPos)));
        } else {
            int emptyIndex = activeList.getEmptyIndex();
            activeList.waypoints.put(emptyIndex, new Waypoint(emptyIndex + "", blockPos));
        }
    }

    private static WaypointList getActiveWaypointList() {
        for (WaypointList waypointList : allPaths) {
            if (waypointList.enabled) return waypointList;
        }

        return null;
    }
}
