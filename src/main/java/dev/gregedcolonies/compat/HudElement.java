//? if >=26.1 && neoforge {
/*package dev.gregedcolonies.compat;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

// NeoForge has no HUD element interface of its own: the HUD draws from GUI layers
// (GregedColoniesNeoForge). This stands in for Fabric API's HudElement, which the renames point at.
public interface HudElement {
	void render(GuiGraphics graphics, DeltaTracker deltaTracker);
}
*///?} else if <1.21 {
package dev.gregedcolonies.compat;

import net.minecraft.client.gui.GuiGraphics;

// Before 1.21 Fabric API had no HUD element interface, and the game no DeltaTracker: the HUD draws
// from HudRenderCallback (GregedColoniesFabric), which hands over the frame's partial tick. This stands in
// for Fabric's HudElement, which the renames point at.
public interface HudElement {
	void render(GuiGraphics graphics, float partialTick);
}
//?} else if <1.21.6 || neoforge {
/*package dev.gregedcolonies.compat;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

// Before 1.21.6 Fabric API had no HUD element interface; the HUD draws from HudRenderCallback
// instead (GregedColoniesFabric), or from GUI layers on NeoForge (GregedColoniesNeoForge). This stands in for
// Fabric's HudElement, which the renames point at.
public interface HudElement {
	void render(GuiGraphics graphics, DeltaTracker deltaTracker);
}
*///?}
