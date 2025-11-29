package com.example.controler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class Controler {
    Minecraft mc;
    BlockPos previousBlock = null;


    public Controler(Minecraft mc) {
         this.mc = mc;
    }

    public double[] scanner_ametyst(int RADIUS) {
        double distSq_block = 0;
        double distSq_player = 0;
        double distSq;
        if (mc.player == null || mc.level == null) return new double[]{-1};

        BlockPos playerPos = mc.player.blockPosition();
        BlockPos closestPos = null;
        double closestDistance = Double.MAX_VALUE;

        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int y = -2; y <= 6; y++) {
                for (int z = -RADIUS; z <= RADIUS; z++) {

                    BlockPos checkPos = playerPos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(checkPos);

                    if (state.is(Blocks.OAK_LOG)) {

                        distSq_player = playerPos.distSqr(checkPos);

                        if(this.previousBlock != null) {
                            distSq_block= this.previousBlock.distSqr(checkPos);
                        }

                        if(distSq_block < 3 && distSq_player < 9) {
                            this.previousBlock = checkPos;
                            return lookAt(checkPos.getX(), checkPos.getY(), checkPos.getZ());

                        }else{
                            distSq = distSq_player;
                        }
//                        distSq = distSq_player > distSq_block ? distSq_player : distSq_block;

                        if (distSq < closestDistance) {
                            closestDistance = distSq;
                            closestPos = checkPos;
                        }
                    }
                    }
                }
            }

        if (closestPos != null) {
            this.previousBlock = closestPos;
            return lookAt(closestPos.getX(), closestPos.getY(), closestPos.getZ());
        }
        return new double[]{-1};
    }

    public double[] lookAt(double targetX, double targetY, double targetZ) {

        float pi = (float) Math.PI;
        LocalPlayer player = mc.player;
        Vec3 playerPos = player.getEyePosition();

        double dx = targetX + 0.5 - playerPos.x;
        double dy = targetY+ 0.5 - playerPos.y;
        double dz = targetZ + 0.5 - playerPos.z;



        double distanceXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.atan2(dz, dx) / (2*pi) * 360 - 90;

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distanceXZ));

        player.setYRot(yaw);
        player.setXRot(pitch);


        player.yRotO = yaw;
        player.xRotO = pitch;



        return new double[]{dx, dy, dz};
    }

    public String coWidze() {
        HitResult cel = mc.hitResult;

        if (cel == null || cel.getType() == HitResult.Type.MISS) {
            return null;
        }

        if (cel.getType() == HitResult.Type.BLOCK) {

            BlockHitResult blockHit = (BlockHitResult) cel;
            BlockPos pos = blockHit.getBlockPos();

            BlockState state = mc.level.getBlockState(pos);

            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return id;
        }

        if (cel.getType() == HitResult.Type.ENTITY) {
            return null;
        }

        return null;
    }


    public int znajdzSlotZPrzedmiotem() {
        var menu = mc.player.containerMenu;

        for (int i = 9; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.hasItem() && slot.getItem().getItem() == Items.STONE) {
                return i;
            }
        }
        return -1;
    }

    public void clickSlot(int windowId, int slotId, int button, ClickType type) {
        if (mc.gameMode != null) {
            mc.gameMode.handleInventoryMouseClick(windowId, slotId, button, type, mc.player);
        }
    }

    public void crafting() {

        if (mc.player.containerMenu == null) {
            return;
        }
        int windowId = mc.player.containerMenu.containerId;

        int slotZDiamentem = znajdzSlotZPrzedmiotem();
        clickSlot(windowId, slotZDiamentem, 0, ClickType.PICKUP);
        for(int i = 0; i <= 64; i++) {
            for (int craftingSlot = 1; craftingSlot <= 4; craftingSlot++) {
                clickSlot(windowId, craftingSlot, 1, ClickType.PICKUP);
            }
        }

        clickSlot(windowId, 0, 0,ClickType.QUICK_MOVE);
        return;

    }
}
