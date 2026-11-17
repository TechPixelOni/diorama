package dev.gregedcolonies.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.gregedcolonies.gameshim.GameShim;
import net.minecraft.client.Options;

/** Test-friendly options from the start, over whatever options.txt says. */
@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void gregedcolonies$testOptions(CallbackInfo ci) {
		GameShim.applyTestOptions((Options) (Object) this);
	}
}
