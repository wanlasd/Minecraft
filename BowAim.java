package org.winterpro.winterpro.module.impl.combat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.phys.Vec3;
import org.winterpro.winterpro.event.MotionEvent;
import org.winterpro.winterpro.event.Render3DEvent;
import org.winterpro.winterpro.module.BoolValue;
import org.winterpro.winterpro.module.NumberValue;
import org.winterpro.winterpro.module.Module;
import org.winterpro.winterpro.util.RenderUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BowAim extends Module {

    private final NumberValue maxSpeed = new NumberValue("Max Rotation Speed", 5.0, 1.0, 10.0, 1.0);
    private final NumberValue minSpeed = new NumberValue("Min Rotation Speed", 3.0, 1.0, 10.0, 1.0);
    private final NumberValue range = new NumberValue("Range", 30.0, 5.0, 50.0, 1.0);
    private final NumberValue fovValue = new NumberValue("Fov", 180.0, 0.0, 360.0, 1.0);
    private final NumberValue pingComp = new NumberValue("Ping Comp", 2.0, 0.0, 10.0, 1.0);
    private final BoolValue targetMobs = new BoolValue("Target Mobs", false);
    private final BoolValue allMobs = new BoolValue("All Mobs", false);
    public final BoolValue silent = new BoolValue("Silent", false);
    public final BoolValue movementFix = new BoolValue("Movement Fix", false);
    private final BoolValue render = new BoolValue("Render", true);

    private float[] rotation;
    private float[] lastRotation;

    private LivingEntity currentTarget;
    private double predX, predY, predZ;

    public static float targetYaw;
    public static boolean isAiming;
    private int keepAimTicks;

    public BowAim() {
        super("BowAim", Category.COMBAT);
        setDescription("Automatically calculates drop and velocity to aim your bow at targets.");
        addValues(maxSpeed, minSpeed, range, fovValue, pingComp, targetMobs, allMobs, silent, movementFix, render);
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;

        boolean isUsingBow = mc.player.getUseItem().getItem() instanceof BowItem;

        if (!isUsingBow) {
            if (keepAimTicks > 0) {
                keepAimTicks--;
            } else {
                lastRotation = rotation = null;
                currentTarget = null;
                isAiming = false;
            }
            return;
        }

        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity livingEntity) {
                if (filter(livingEntity)) {
                    targets.add(livingEntity);
                }
            }
        }

        targets.sort(Comparator.comparingDouble(this::getAngleDifferenceToCrosshair));
        LivingEntity target = targets.isEmpty() ? null : targets.get(0);
        currentTarget = target;

        if (target == null) {
            if (keepAimTicks > 0) {
                keepAimTicks--;
            } else {
                lastRotation = rotation = null;
                isAiming = false;
            }
        } else {
            float[] newRotation = getIterativePredictedAngles(target);
            if (rotation == null) {
                lastRotation = rotation = newRotation;
            } else {
                lastRotation = rotation;
                rotation = newRotation;
            }
            targetYaw = rotation[0];
            isAiming = true;
            keepAimTicks = 3;
        }
    }

    @Override
    public void onRender2D(GuiGraphics graphics) {
        if (silent.get() || rotation == null || lastRotation == null || mc.player == null || mc.level == null) return;
        if (!(mc.player.getUseItem().getItem() instanceof BowItem) && keepAimTicks <= 0) return;

        final float partialTicks = mc.getFrameTime();
        float yawDiff = Mth.wrapDegrees(rotation[0] - lastRotation[0]);
        float pitchDiff = rotation[1] - lastRotation[1];

        float currentYaw = lastRotation[0] + yawDiff * partialTicks;
        float currentPitch = lastRotation[1] + pitchDiff * partialTicks;

        double min = minSpeed.get() * 30;
        double max = maxSpeed.get() * 30;
        double randomSpeed = min + (max - min) * Math.random();
        final float strength = (float) randomSpeed;

        final double sensitivity = mc.options.sensitivity().get() * 0.6F + 0.2F;
        final double gcd = sensitivity * sensitivity * sensitivity * 8.0F;

        float deltaYawToTurn = Mth.wrapDegrees(currentYaw - mc.player.getYRot());
        float deltaPitchToTurn = currentPitch - mc.player.getXRot();

        float deltaYaw = (float) (deltaYawToTurn * (strength / 100) * gcd);
        float deltaPitch = (float) (deltaPitchToTurn * (strength / 100) * gcd);

        mc.player.turn(deltaYaw, deltaPitch);
    }

    @Override
    public void onRender3D(Render3DEvent event) {
        if (!render.get() || currentTarget == null || mc.player == null || mc.level == null) return;
        if (!(mc.player.getUseItem().getItem() instanceof BowItem) && keepAimTicks <= 0) return;

        PoseStack poseStack = event.getPoseStack();
        float partialTicks = event.getPartialTicks();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();

        float width = currentTarget.getBbWidth() / 2.0f;
        float height = currentTarget.getBbHeight();

        double cx = currentTarget.xOld + (currentTarget.getX() - currentTarget.xOld) * partialTicks - camera.x;
        double cy = currentTarget.yOld + (currentTarget.getY() - currentTarget.yOld) * partialTicks - camera.y;
        double cz = currentTarget.zOld + (currentTarget.getZ() - currentTarget.zOld) * partialTicks - camera.z;

        int targetFill = new Color(255, 0, 0, 100).getRGB();
        int targetOutline = new Color(255, 0, 0, 200).getRGB();

        poseStack.pushPose();
        poseStack.translate(cx, cy, cz);

        RenderUtils.setupRender();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderUtils.drawFilledBoundingBox(poseStack, -width, width, 0, height, -width, width, targetFill, targetFill, 1.0f);
        RenderUtils.drawEntityBox(poseStack, currentTarget, targetOutline);
        RenderUtils.endRender();
        poseStack.popPose();

        double px = predX - camera.x;
        double py = predY - (currentTarget.getBbHeight() / 2.0) - camera.y;
        double pz = predZ - camera.z;

        int predFill = new Color(0, 255, 0, 100).getRGB();
        int predOutline = new Color(0, 255, 0, 200).getRGB();

        poseStack.pushPose();
        poseStack.translate(px, py, pz);

        RenderUtils.setupRender();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderUtils.drawFilledBoundingBox(poseStack, -width, width, 0, height, -width, width, predFill, predFill, 1.0f);
        RenderUtils.drawEntityBox(poseStack, currentTarget, predOutline);
        RenderUtils.endRender();
        poseStack.popPose();
    }

    @Override
    public void onMotion(MotionEvent event) {
        if (!silent.get() || !isAiming || rotation == null || mc.player == null || mc.level == null) return;

        if (event.isPre()) {
            event.setYaw(rotation[0]);
            event.setPitch(rotation[1]);

            mc.player.yHeadRot = rotation[0];
            mc.player.yBodyRot = rotation[0];
        }
    }

    private float[] getIterativePredictedAngles(LivingEntity target) {
        int useTicks = mc.player.getTicksUsingItem();
        double velocity = useTicks / 20.0;
        velocity = (velocity * velocity + velocity * 2.0) / 3.0;
        if (velocity > 1.0) velocity = 1.0;
        velocity *= 3.0;

        if (velocity < 0.1) {
            this.predX = target.getX();
            this.predY = target.getY() + (target.getBbHeight() / 2.0);
            this.predZ = target.getZ();
            return new float[]{mc.player.getYRot(), mc.player.getXRot()};
        }

        double startX = mc.player.getX();
        double startY = mc.player.getY() + mc.player.getEyeHeight();
        double startZ = mc.player.getZ();

        double dx = target.getX() - target.xOld;
        double dy = target.getY() - target.yOld;
        double dz = target.getZ() - target.zOld;

        double predictX = target.getX();
        double predictY = target.getY() + (target.getBbHeight() / 2.0);
        double predictZ = target.getZ();

        int pingOffset = pingComp.get().intValue();
        predictX += dx * pingOffset;
        predictY += dy * pingOffset;
        predictZ += dz * pingOffset;

        for (int i = 0; i < 60; i++) {
            predictX += dx;
            predictY += dy;
            predictZ += dz;

            double diffX = predictX - startX;
            double diffZ = predictZ - startZ;
            double diffY = predictY - startY;

            double distXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
            double g = 0.05;
            double v2 = velocity * velocity;
            double v4 = v2 * v2;
            double root = v4 - g * (g * distXZ * distXZ + 2 * diffY * v2);

            if (root >= 0) {
                double pitch = -Math.toDegrees(Math.atan((v2 - Math.sqrt(root)) / (g * distXZ)));
                double yaw = Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0;

                double dragVelocity = velocity;
                double simXZ = 0;
                int flightTicks = 0;

                for (int t = 0; t < 60; t++) {
                    simXZ += dragVelocity * Math.cos(Math.toRadians(pitch));
                    dragVelocity *= 0.99;
                    flightTicks++;
                    if (simXZ >= distXZ) break;
                }

                if (Math.abs(flightTicks - i) <= 1) {
                    this.predX = predictX;
                    this.predY = predictY;
                    this.predZ = predictZ;
                    return new float[]{(float) yaw, (float) pitch};
                }
            }
        }

        double diffX = target.getX() - startX;
        double diffZ = target.getZ() - startZ;
        double diffY = (target.getY() + target.getBbHeight() / 2.0) - startY;
        double distXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double fallbackPitch = -Math.toDegrees(Math.atan2(diffY, distXZ));
        double fallbackYaw = Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0;

        this.predX = target.getX();
        this.predY = target.getY() + target.getBbHeight() / 2.0;
        this.predZ = target.getZ();

        return new float[]{(float) fallbackYaw, (float) fallbackPitch};
    }

    private float getAngleDifferenceToCrosshair(Entity entity) {
        double diffX = entity.getX() - mc.player.getX();
        double diffZ = entity.getZ() - mc.player.getZ();
        float entityYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0);

        float deltaYaw = Mth.wrapDegrees(entityYaw - mc.player.getYRot());
        return Math.abs(deltaYaw);
    }

    private boolean filter(LivingEntity entity) {
        if (entity == mc.player) return false;
        if (mc.player.distanceTo(entity) > range.get()) return false;

        boolean isPlayer = entity instanceof Player;
        boolean isHostileMob = entity instanceof Monster;

        if (!isPlayer && !allMobs.get() && !(targetMobs.get() && isHostileMob)) {
            return false;
        }

        if (!mc.player.hasLineOfSight(entity)) return false;
        if (!isInFOV(entity)) return false;

        return !entity.isDeadOrDying() && entity.getHealth() > 0;
    }

    private boolean isInFOV(LivingEntity entity) {
        float currentFOV = fovValue.get().floatValue();
        if (currentFOV >= 360) return true;

        double diffX = entity.getX() - mc.player.getX();
        double diffZ = entity.getZ() - mc.player.getZ();
        float entityYaw = (float) (Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0);

        float deltaYaw = Mth.wrapDegrees(entityYaw - mc.player.getYRot());
        return Math.abs(deltaYaw) <= currentFOV / 2;
    }
}
