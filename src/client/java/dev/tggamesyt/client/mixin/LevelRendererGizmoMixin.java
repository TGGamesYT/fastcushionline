package dev.tggamesyt.client.mixin;

import dev.tggamesyt.client.FastCushionLineClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Injects the FastCushionLine route into the world's gizmo collection each
 * frame. {@code finalizeGizmoCollection} runs once per frame just before the
 * collected gizmos are submitted for rendering, so adding our line gizmos here
 * draws them as real, smooth lines (independent of the F3 debug overlay).
 */
@Mixin(LevelRenderer.class)
public class LevelRendererGizmoMixin {

	@Inject(method = "finalizeGizmoCollection", at = @At("HEAD"))
	private void fastcushionline$emitPathGizmos(CallbackInfo ci) {
		if (FastCushionLineClient.manager == null) {
			return;
		}
		List<SimpleGizmoCollector.GizmoInstance> gizmos = FastCushionLineClient.manager.buildPathGizmos();
		if (!gizmos.isEmpty()) {
			((LevelRenderer) (Object) this).addMainThreadGizmos(gizmos);
		}
	}
}
