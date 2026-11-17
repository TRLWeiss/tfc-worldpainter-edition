package tfcwptest;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import net.dries007.tfc.api.capability.food.IFoodStatsTFC;
import net.dries007.tfc.objects.items.ItemSeedsTFC;
import net.dries007.tfc.util.agriculture.Crop;
import net.dries007.tfc.util.climate.ClimateTFC;
import net.dries007.tfc.world.classic.chunkdata.ChunkDataTFC;

/** 在集成服务端线程里跑的功能检查，结果写进游戏目录的 tfcwptest-results.txt */
final class FunctionalChecks
{
    static void run(MinecraftServer server, File gameDir, StringBuilder out)
    {
        EntityPlayerMP player = server.getPlayerList().getPlayers().get(0);
        WorldServer world = server.getWorld(0);
        BlockPos base = player.getPosition();
        line(out, "player_pos " + base);

        // ---- 1. 喝原版淡水
        player.setGameType(GameType.SURVIVAL);
        BlockPos water = findWater(world, base, 150);
        if (water == null)
        {
            line(out, "drink SKIP no_vanilla_fresh_water_nearby");
        }
        else
        {
            player.connection.setPlayerLocation(water.getX() + 0.5, water.getY() + 1.2, water.getZ() + 0.5, 0f, 90f);
            player.rotationPitch = 90f;
            player.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            IFoodStatsTFC stats = (IFoodStatsTFC) player.getFoodStats();
            stats.setThirst(20f);
            float before = stats.getThirst();
            MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, EnumHand.MAIN_HAND, water.down(), EnumFacing.UP, new Vec3d(water)));
            float after = stats.getThirst();
            line(out, "drink water_at " + water + " block " + world.getBlockState(water).getBlock().getRegistryName() + " biome " + world.getBiome(water).getRegistryName()
                + " thirst_before " + before + " thirst_after " + after + (after > before ? " PASS" : " FAIL"));
        }

        // ---- 2. 原版耕地上种群峦种子
        BlockPos soil = findSoil(world, base, 32);
        if (soil == null)
        {
            line(out, "seed SKIP no_vanilla_grass_nearby");
        }
        else
        {
            world.setBlockState(soil, Blocks.FARMLAND.getDefaultState());
            player.connection.setPlayerLocation(soil.getX() + 0.5, soil.getY() + 1, soil.getZ() + 2.5, 0f, 45f);
            ItemStack seeds = ItemSeedsTFC.get(Crop.BARLEY, 4);
            player.setHeldItem(EnumHand.MAIN_HAND, seeds);
            EnumActionResult result = seeds.getItem().onItemUse(player, world, soil, EnumHand.MAIN_HAND, EnumFacing.UP, 0.5f, 1f, 0.5f);
            Block farmland = world.getBlockState(soil).getBlock();
            Block crop = world.getBlockState(soil.up()).getBlock();
            boolean pass = result == EnumActionResult.SUCCESS && farmland.getRegistryName().getPath().startsWith("farmland/") && crop.getRegistryName().getPath().startsWith("crop/");
            line(out, "seed at " + soil + " result " + result + " farmland " + farmland.getRegistryName() + " crop " + crop.getRegistryName() + (pass ? " PASS" : " FAIL"));
        }

        // ---- 3. 气温：海平面和高处
        BlockPos sea = new BlockPos(base.getX(), 63, base.getZ());
        line(out, String.format("climate avg_temp %.2f rainfall %.1f actual_temp_y63 %.2f actual_temp_y123 %.2f actual_temp_y183 %.2f",
            ClimateTFC.getAvgTemp(world, sea), ChunkDataTFC.getRainfall(world, sea),
            ClimateTFC.getActualTemp(world, sea), ClimateTFC.getActualTemp(world, sea.up(60)), ClimateTFC.getActualTemp(world, sea.up(120))));

        // ---- 4. 矿石高度分布、露出地表的矿石、群峦动物
        Map<Integer, Integer> oreByY = new TreeMap<>();
        int oresAboveSurface = 0, ores = 0;
        int cx = base.getX() >> 4, cz = base.getZ() >> 4;
        for (int x = cx - 6; x <= cx + 6; x++)
        {
            for (int z = cz - 6; z <= cz + 6; z++)
            {
                Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
                if (chunk == null) continue;
                for (ExtendedBlockStorage section : chunk.getBlockStorageArray())
                {
                    if (section == Chunk.NULL_BLOCK_STORAGE) continue;
                    for (int i = 0; i < 4096; i++)
                    {
                        int bx = i & 15, by = i >> 8 & 15, bz = i >> 4 & 15;
                        IBlockState state = section.get(bx, by, bz);
                        ResourceLocation id = state.getBlock().getRegistryName();
                        if (id == null || !id.getPath().startsWith("ore/")) continue;
                        int y = section.getYLocation() + by;
                        ores++;
                        oreByY.merge(y / 8 * 8, 1, Integer::sum);
                        if (y >= chunk.getHeightValue(bx, bz)) oresAboveSurface++;
                    }
                }
            }
        }
        line(out, "ores_total " + ores + " ores_at_or_above_heightmap " + oresAboveSurface);
        StringBuilder hist = new StringBuilder("ore_y_histogram");
        for (Map.Entry<Integer, Integer> e : oreByY.entrySet()) hist.append(' ').append(e.getKey()).append(':').append(e.getValue());
        line(out, hist.toString());

        Map<String, Integer> animals = new TreeMap<>();
        for (Entity entity : world.loadedEntityList)
        {
            if (entity.getClass().getName().startsWith("net.dries007.tfc.objects.entity.animal"))
            {
                animals.merge(entity.getClass().getSimpleName(), 1, Integer::sum);
            }
        }
        line(out, "tfc_animals " + animals);

        player.setGameType(GameType.SPECTATOR);
        try (PrintWriter writer = new PrintWriter(new File(gameDir, "tfcwptest-results.txt"), StandardCharsets.UTF_8.name()))
        {
            writer.print(out);
        }
        catch (Exception ignored) {}
    }

    private static BlockPos findWater(WorldServer world, BlockPos center, int radius)
    {
        for (int r = 0; r <= radius; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                for (int dz = -r; dz <= r; dz++)
                {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    if (!world.isBlockLoaded(center.add(dx, 0, dz))) continue;
                    BlockPos top = world.getHeight(center.add(dx, 0, dz)).down();
                    IBlockState state = world.getBlockState(top);
                    if (state.getBlock() == Blocks.WATER && state.getValue(BlockLiquid.LEVEL) == 0
                        && !BiomeDictionary.hasType(world.getBiome(top), BiomeDictionary.Type.OCEAN))
                    {
                        return top;
                    }
                }
            }
        }
        return null;
    }

    private static BlockPos findSoil(WorldServer world, BlockPos center, int radius)
    {
        for (int r = 2; r <= radius; r++)
        {
            for (int dx = -r; dx <= r; dx++)
            {
                BlockPos top = world.getTopSolidOrLiquidBlock(center.add(dx, 0, r)).down();
                if (world.getBlockState(top).getBlock() == Blocks.GRASS && world.isAirBlock(top.up())) return top;
            }
        }
        return null;
    }

    private static void line(StringBuilder out, String text)
    {
        out.append(text).append('\n');
        TestHarness.LOG.info("[check] {}", text);
    }

    private FunctionalChecks() {}
}
