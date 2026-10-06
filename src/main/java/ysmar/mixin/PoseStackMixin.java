package ysmar.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ysmar.takeover.LateHook;
import ysmar.takeover.Takeover;

/**
 * Tells the take-over when a scale call has returned. Yes Steve Model scales the pose stack by the model scale in
 * its pre-render step, after any code other mods put in front of that step: the last moment before its layers
 * and its draw read the pose and the bone values. All but a few calls end at the one field read.
 */
@Mixin(PoseStack.class)
public abstract class PoseStackMixin {
    // RETURN, not TAIL: every way out of the method counts, also a return another mod puts in front of the vanilla one.
    @Inject(method = "scale(FFF)V", at = @At("RETURN"), require = 0)
    private void ysmAr$afterScale(float x, float y, float z, CallbackInfo callback) {
        if (LateHook.armed) {
            Takeover.onScale(this, x, y, z);
        }
    }
}
