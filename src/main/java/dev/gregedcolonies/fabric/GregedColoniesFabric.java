package dev.gregedcolonies.fabric;

import dev.gregedcolonies.Claims;
import dev.gregedcolonies.GregedColonies;
import dev.gregedcolonies.GregedColoniesClient;
import dev.gregedcolonies.MapSession;
import dev.gregedcolonies.Maps;
import dev.gregedcolonies.MinimapHud;
import dev.gregedcolonies.ServerRules;
import dev.gregedcolonies.TrackedPlayers;
import dev.gregedcolonies.WorldKey;
import dev.gregedcolonies.XaeroChannels;
import dev.gregedcolonies.drawing.DrawingStore;
import dev.gregedcolonies.bake.BlockLooks;
import dev.gregedcolonies.render.XaeroRadarIcons;
import dev.gregedcolonies.share.ShareClient;
import dev.gregedcolonies.share.ShareProtocol;
import dev.gregedcolonies.waypoint.WaypointHud;
import dev.gregedcolonies.waypoint.WaypointShare;
import dev.gregedcolonies.waypoint.WaypointStore;
import dev.gregedcolonies.waypoint.Waystones;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
//? if >=1.21.6 {
/*import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//?}
import net.minecraft.client.KeyMapping;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.packs.PackType;

/**
 * GregedColonies's entrypoint on Fabric: registers the key bindings and the HUD, and hooks the mod up to
 * Fabric API's events. Only the Fabric jars contain this package; NeoForge has its own entrypoint.
 */
public final class GregedColoniesFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		GregedColoniesClient.init();
		ShareClient.init();
		// The payload type is registered on both sides, in GregedColoniesFabricCommon.
		//? if >=1.21 {
		/*ClientPlayNetworking.registerGlobalReceiver(ShareProtocol.TYPE, (packet, context) -> ShareProtocol.toClient(packet.data()));
		ShareProtocol.toServer(data -> ClientPlayNetworking.send(new ShareProtocol.Packet(data)));
		*///?} else {
		ClientPlayNetworking.registerGlobalReceiver(ShareProtocol.CHANNEL, (client, handler, buf, sender) -> {
			// Read here: the buffer is only good until this returns. Handled on the client thread,
			// unless the player has left by then.
			byte[] data = ShareProtocol.read(buf).data();
			client.execute(() -> {
				if (handler.getConnection().isConnected()) {
					ShareProtocol.toClient(data);
				}
			});
		});
		ShareProtocol.toServer(data -> {
			net.minecraft.network.FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
			buf.writeBytes(data);
			ClientPlayNetworking.send(ShareProtocol.CHANNEL, buf);
		});
		//?}
		for (KeyMapping keyMapping : GregedColoniesClient.KEY_MAPPINGS) {
			KeyBindingHelper.registerKeyBinding(keyMapping);
		}
		// Waypoints in the world are part of the picture of the world, so they go under the whole
		// vanilla HUD and every screen; the minimap goes over the HUD. Before 1.21.6
		// GameRendererMixin draws the waypoints.
		//? if >=1.21.6 {
		/*HudElementRegistry.addFirst(GregedColonies.id("waypoints"), new WaypointHud());
		HudElementRegistry.addLast(GregedColonies.id("minimap"), new MinimapHud());
		*///?} else {
		// Drawn after the whole vanilla HUD, like addLast. Each vanilla HUD layer sits 200 deeper
		// than the one before and the GUI depth-tests, so lift the vanilla fills and text this
		// draws above all of them.
		MinimapHud minimapHud = new MinimapHud();
		HudRenderCallback.EVENT.register((graphics, deltaTracker) -> {
			var pose = graphics.pose();
			pose.pushPose();
			pose.translate(0, 0, 5000);
			minimapHud.render(graphics, deltaTracker);
			pose.popPose();
		});
		//?}
		ClientChunkEvents.CHUNK_LOAD.register(MapSession::chunkLoaded);
		// Joining a server whose mods number block states differently renumbers them here, and leaving puts them back.
		RegistryIdRemapCallback.event(BuiltInRegistries.BLOCK).register(state -> BlockLooks.invalidate());
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> ServerRules.onServerMessage(message));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> WaypointShare.register(dispatcher));
		ClientPlayConnectionEvents.DISCONNECT.register((listener, mc) -> {
			ServerRules.onDisconnect();
			WaypointStore.onLeave();
			Maps.onLeave();
			DrawingStore.onLeave();
			WorldKey.onJoinOrLeave();
			XaeroChannels.onJoinOrLeave();
			ShareClient.onJoinOrLeave();
			TrackedPlayers.clear();
			Waystones.clear();
			Claims.clear();
		});
		// A network moving the player to another of its servers joins again without leaving.
		ClientPlayConnectionEvents.JOIN.register((listener, sender, mc) -> {
			ServerRules.onDisconnect();
			WaypointStore.onLeave();
			Maps.onLeave();
			DrawingStore.onLeave();
			WorldKey.onJoinOrLeave();
			XaeroChannels.onJoinOrLeave();
			ShareClient.onJoinOrLeave();
			TrackedPlayers.clear();
			Waystones.clear();
			Claims.clear();
		});
		// Once every mod has started, so a mod that has Xaero's channels has them by then.
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> XaeroChannels.register());
		// Radar icons from Xaero's Minimap's icon files, read again on every resource reload.
		//? if >=26.1 {
		/*net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(XaeroRadarIcons.ID, XaeroRadarIcons.LISTENER);
		*///?} else if >=1.21.9 {
		/*net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloader(XaeroRadarIcons.ID, XaeroRadarIcons.LISTENER);
		*///?} else {
		net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
			new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
				@Override
				public net.minecraft.resources.ResourceLocation getFabricId() {
					return XaeroRadarIcons.ID;
				}

				@Override
				public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
					XaeroRadarIcons.reload(manager);
				}
			});
		//?}
		// Leaving a world saves the map on the next tick, but closing the window stops ticking first.
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> MapSession.close());
		ClientTickEvents.END_CLIENT_TICK.register(GregedColoniesClient::tick);
	}
}
