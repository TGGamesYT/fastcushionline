package dev.tggamesyt.client.mixin;

import dev.tggamesyt.client.FastCushionLineClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slides the camera smoothly from cushion to cushion while FastCushionLine is
 * travelling (toggle with {@code /fcl smoothcamera}). The seat itself still jumps
 * cushion-to-cushion as the server moves the player, but after the camera is
 * positioned each frame we nudge it to the travel manager's eased visual anchor,
 * so the view glides across instead of snapping. The offset decays to zero once
 * the camera catches up, so normal play is untouched.
 */
@Mixin(Camera.class)
public abstract class CameraSmoothMixin {

	@Shadow
	public abstract Vec3 position();

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	public abstract float getCameraEntityPartialTicks(DeltaTracker deltaTracker);

	@Inject(method = "update", at = @At("TAIL"))
	private void fastcushionline$slideCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (FastCushionLineClient.manager == null) {
			return;
		}
		float partial = getCameraEntityPartialTicks(deltaTracker);
		Vec3 offset = FastCushionLineClient.manager.cameraSlideOffset(partial);
		if (offset == Vec3.ZERO) {
			return;
		}
		setPosition(position().add(offset));
	}
}
