package ysmar.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ysmar.takeover.Takeover;

/**
 * The renderers of Yes Steve Model ask this vanilla method whether to draw the body; for a body this mod draws
 * through Accelerated Rendering the answer becomes "no". In every other case the method runs unchanged.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "isBodyVisible(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void ysmAr$hideTakenOverBody(LivingEntity entity, CallbackInfoReturnable<Boolean> callback) {
        if (Takeover.hideBody(this, entity)) {
            callback.setReturnValue(false);
        }
    }
}
