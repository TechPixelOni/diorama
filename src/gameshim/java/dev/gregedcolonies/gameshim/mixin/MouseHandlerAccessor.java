package dev.gregedcolonies.gameshim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.MouseHandler;
//? if >=1.21.9 {
/*import net.minecraft.client.input.MouseButtonInfo;
*///?}

/** The window system callbacks the mouse handler registers, for simulated input. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
	//? if >=1.21.9 {
	/*@Invoker("onButton")
	void gregedcolonies$onButton(long window, MouseButtonInfo button, int action);
	*///?} else {
	@Invoker("onPress")
	void gregedcolonies$onPress(long window, int button, int action, int mods);
	//?}

	//? if >=26.3 {
	/*@Invoker("onMove")
	void gregedcolonies$onMove(long window, double x, double y, double deltaX, double deltaY);
	*///?} else {
	@Invoker("onMove")
	void gregedcolonies$onMove(long window, double x, double y);
	//?}
}
