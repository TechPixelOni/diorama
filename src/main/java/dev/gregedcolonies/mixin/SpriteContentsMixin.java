package dev.gregedcolonies.mixin;

import dev.gregedcolonies.bake.BlockLooks;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block colours are read from textures' images on whichever thread first meets a block, and a
 * resource reload frees the last atlas's images on the client thread: this keeps the two apart
 * (see {@link BlockLooks#closing}).
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin implements BlockLooks.ClosingTexture {
	@Unique
	private volatile boolean gregedcolonies$closed;

	@Inject(method = "close", at = @At("HEAD"))
	private void gregedcolonies$onClose(CallbackInfo ci) {
		BlockLooks.closing(this);
	}

	@Override
	public void gregedcolonies$markClosed() {
		this.gregedcolonies$closed = true;
	}

	@Override
	public boolean gregedcolonies$closed() {
		return this.gregedcolonies$closed;
	}
}
