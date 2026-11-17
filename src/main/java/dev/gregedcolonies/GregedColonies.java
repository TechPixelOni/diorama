package dev.gregedcolonies;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GregedColonies {
	public static final String MOD_ID = "gregedcolonies";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** Extra logging, on with -Dgregedcolonies.debug=true or GREGEDCOLONIES_DEBUG=1. */
	public static final boolean DEBUG = Boolean.getBoolean("gregedcolonies.debug") || "1".equals(System.getenv("GREGEDCOLONIES_DEBUG"));

	private GregedColonies() {
	}

	public static ResourceLocation id(String path) {
		return new net.minecraft.resources.ResourceLocation(MOD_ID, path);
	}
}
