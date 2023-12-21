package net.minelink.ctplus.nms;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minelink.ctplus.CombatTagPlus;
import net.minelink.ctplus.compat.base.NpcIdentity;
import net.minelink.ctplus.compat.base.NpcNameGeneratorFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_18_R2.CraftServer;
import org.bukkit.craftbukkit.v1_18_R2.CraftWorld;
import org.bukkit.craftbukkit.v1_18_R2.entity.CraftPlayer;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;

import java.util.Map;

public class NpcPlayer extends ServerPlayer {

    private NpcIdentity identity;
    public NpcPlayer(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile);
    }

    public NpcIdentity getNpcIdentity() {
        return identity;
    }

    public static NpcPlayer valueOf(Player player) {
        MinecraftServer minecraftServer = MinecraftServer.getServer();
        ServerLevel worldServer = ((CraftWorld) player.getWorld()).getHandle();
        GameProfile gameProfile = new GameProfile(player.getUniqueId(), NpcNameGeneratorFactory.getNameGenerator().generate(player));

        NpcPlayer npcPlayer = new NpcPlayer(minecraftServer, worldServer, gameProfile);
        npcPlayer.identity = new NpcIdentity(player);

        new NpcPlayerConnection(npcPlayer);

        ProfileProperty property = player.getPlayerProfile().getProperties().iterator().next();
        String texture = property.getValue();
        String signature = property.getSignature();

        npcPlayer.getGameProfile().getProperties().put("textures", new Property("textures", texture, signature));

        return npcPlayer;
    }

    @Override
    public void tick(){
        super.tick();
        doTick();
    }

    @Override
    public boolean hurt(DamageSource damageSource, float f) {
        boolean damaged = super.hurt(damageSource, f);
        if (damaged) {
            if (this.hurtMarked) {
                this.hurtMarked = false;
                Bukkit.getScheduler().runTask(CombatTagPlus.getPlugin(CombatTagPlus.class), () -> NpcPlayer.this.hurtMarked = true);
            }
        }
        return damaged;
    }
}

