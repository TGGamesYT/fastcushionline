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
 * cushion-to-cushion as the server moves the player; after the camera position is
 * computed each frame we hand it to the travel manager, which eases it toward the
 * real position so a seat teleport becomes a glide. The smoother only ever moves
 * the camera from where it is toward the real spot, so it never snaps ahead and
 * back, and it settles exactly on the seat when idle, leaving normal play alone.
 */
@Mixin(Camera.class)
public abstract class CameraSmoothMixin {

	@Shadow
	public abstract Vec3 position();

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Inject(method = "update", at = @At("TAIL"))
	private void fastcushionline$slideCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (FastCushionLineClient.manager == null) {
			return;
		}
		Vec3 real = position();
		Vec3 smoothed = FastCushionLineClient.manager.smoothCameraPosition(real);
		if (smoothed != real) {
			setPosition(smoothed);
		}
	}
}
