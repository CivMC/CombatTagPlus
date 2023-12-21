package net.minelink.ctplus.nms;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minelink.ctplus.compat.base.NpcIdentity;
import net.minelink.ctplus.compat.base.NpcPlayerHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_18_R2.CraftWorld;
import org.bukkit.craftbukkit.v1_18_R2.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

public class NpcPlayerHelperImpl implements NpcPlayerHelper {
    @Override
    public Player spawn(Player player) {
        ServerLevel worldServer = ((CraftWorld) player.getWorld()).getHandle();

        NpcPlayer npcPlayer = NpcPlayer.valueOf(player);

        Location location = player.getLocation();

        npcPlayer.setPos(location.getX(), location.getY(), location.getZ());
        npcPlayer.setXRot(location.getYaw());
        npcPlayer.setYRot(location.getPitch());

        npcPlayer.getBukkitEntity().setNoDamageTicks(0);

        worldServer.entityManager.getEntityGetter().get(player.getUniqueId()).remove(Entity.RemovalReason.DISCARDED);
        worldServer.addNewPlayer(npcPlayer);
        showAll(npcPlayer, location);

        return npcPlayer.getBukkitEntity();
    }

    public static void showAll(ServerPlayer entityPlayer, Location location) {
        ClientboundPlayerInfoPacket playerInfoAdd = new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.ADD_PLAYER);
        ClientboundAddPlayerPacket namedEntitySpawn = new ClientboundAddPlayerPacket(entityPlayer);
        ClientboundRotateHeadPacket headRotation = new ClientboundRotateHeadPacket(entityPlayer, (byte) ((location.getYaw() * 256f) / 360f));
        ClientboundPlayerInfoPacket playerInfoRemove = new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER);
        for (Player player : Bukkit.getOnlinePlayers()) {
            ServerGamePacketListenerImpl connection = ((CraftPlayer) player).getHandle().connection;
            connection.send(playerInfoAdd);
            connection.send(namedEntitySpawn);
            connection.send(headRotation);
            connection.send(playerInfoRemove);
        }
        entityPlayer.getEntityData().
                set(net.minecraft.world.entity.player.Player.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0xFF);
    }

    @Override
    public void despawn(Player player) {
        ServerPlayer entity = ((CraftPlayer) player).getHandle();
        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        for (ServerPlayer serverPlayer : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (serverPlayer instanceof NpcPlayer) continue;

            ClientboundPlayerInfoPacket packet = new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER, entity);
            serverPlayer.connection.send(packet);
        }

        ServerLevel worldServer = entity.getLevel();
        worldServer.chunkSource.removeEntity(entity);
        worldServer.getPlayers(serverPlayer -> serverPlayer instanceof NpcPlayer).remove(entity);
        removePlayerList(player);
    }

    @Override
    public boolean isNpc(Player player) {
        return ((CraftPlayer) player).getHandle() instanceof NpcPlayer;
    }

    @Override
    public NpcIdentity getIdentity(Player player) {
        if (!isNpc(player)) {
            throw new IllegalArgumentException();
        }

        return ((NpcPlayer) ((CraftPlayer) player).getHandle()).getNpcIdentity();
    }

    @Override
    public void updateEquipment(Player player) {
        ServerPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack item = entity.getItemBySlot(slot);
            if (item == null) continue;

            // Set the attribute for this equipment to consider armor values and enchantments
            // Actually getAttributeMap().a() is used with the previous item, to clear the Attributes
            entity.getAttributes().removeAttributeModifiers(item.getAttributeModifiers(slot));
            entity.getAttributes().addTransientAttributeModifiers(item.getAttributeModifiers(slot));

            // This is also called by super.tick(), but the flag this.bx is not public
            List<Pair<EquipmentSlot, ItemStack>> list = Lists.newArrayList();
            list.add(Pair.of(slot, item));
            Packet packet = new ClientboundSetEquipmentPacket(entity.getId(), list);
            entity.getLevel().chunkSource.broadcast(entity, packet);
        }
    }

    @Override
    public void syncOffline(Player player) {
        ServerPlayer entity = ((CraftPlayer) player).getHandle();

        if (!(entity instanceof NpcPlayer)) {
            throw new IllegalArgumentException();
        }

        NpcPlayer npcPlayer = (NpcPlayer) entity;
        NpcIdentity identity = npcPlayer.getNpcIdentity();
        Player p = Bukkit.getPlayer(identity.getId());
        if (p != null && p.isOnline()) return;

        PlayerDataStorage worldStorage = ((CraftWorld) Bukkit.getWorlds().get(0)).getHandle().getServer().playerDataStorage;
        CompoundTag playerNbt = worldStorage.getPlayerData(identity.getId().toString());
        if (playerNbt == null) return;

        // foodTickTimer is now private in 1.8.3 -- still private in 1.12
        Field foodTickTimerField;
        int foodTickTimer;

        try {
            //Although we can use Mojang mappings when developing, We need to use the obfuscated field name
            //until we can run a full Mojmapped server. I personally used this site when updating to 1.18:
            //https://nms.screamingsandals.org/1.18.1/net/minecraft/world/food/FoodData.html
            foodTickTimerField = FoodData.class.getDeclaredField("d");
            foodTickTimerField.setAccessible(true);
            foodTickTimer = foodTickTimerField.getInt(entity.getFoodData());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        playerNbt.putShort("Air", (short) entity.getAirSupply());
        // Health is now just a float; fractional is not stored separately. (1.12)
        playerNbt.putFloat("Health", entity.getHealth());
        playerNbt.putFloat("AbsorptionAmount", entity.getAbsorptionAmount());
        playerNbt.putInt("XpTotal", entity.experienceLevel);
        playerNbt.putInt("foodLevel", entity.getFoodData().getFoodLevel());
        playerNbt.putInt("foodTickTimer", foodTickTimer);
        playerNbt.putFloat("foodSaturationLevel", entity.getFoodData().getSaturationLevel());
        playerNbt.putFloat("foodExhaustionLevel", entity.getFoodData().exhaustionLevel);
        playerNbt.putShort("Fire", (short) entity.remainingFireTicks);
        playerNbt.put("Inventory", npcPlayer.getInventory().save(new ListTag()));

        File file1 = new File(worldStorage.getPlayerDir(), identity.getId().toString() + ".dat.tmp");
        File file2 = new File(worldStorage.getPlayerDir(), identity.getId().toString() + ".dat");

        try {
            NbtIo.writeCompressed(playerNbt, new FileOutputStream(file1));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save player data for " + identity.getName(), e);
        }

        if ((!file2.exists() || file2.delete()) && !file1.renameTo(file2)) {
            throw new RuntimeException("Failed to save player data for " + identity.getName());
        }
    }

    @Override
    public void createPlayerList(Player player) {
        ServerPlayer p = ((CraftPlayer) player).getHandle();

        for (ServerPlayer serverPlayer : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            ClientboundPlayerInfoPacket packet = new ClientboundPlayerInfoPacket(
                    ClientboundPlayerInfoPacket.Action.ADD_PLAYER, serverPlayer);
            p.connection.send(packet);
        }
    }

    @Override
    public void removePlayerList(Player player) {
        ServerPlayer p = ((CraftPlayer) player).getHandle();
        for (ServerPlayer serverPlayer : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            ClientboundPlayerInfoPacket packet = new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER, serverPlayer);
            p.connection.send(packet);
        }
    }
}
