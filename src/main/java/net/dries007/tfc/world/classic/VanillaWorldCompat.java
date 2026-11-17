/*
 * Work under Copyright. Licensed under the EUPL.
 * See the project README.md and LICENSE.txt for more information.
 *
 * 改造版新增：让群峦在原版世界类型（比如 WorldPainter 做的地图）的主世界里也生成全部资源。
 * 不替换原版的石头、泥土、草地和水，群峦的内容像其他模组的矿石一样嵌进去。
 */

package net.dries007.tfc.world.classic;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraft.world.gen.NoiseGeneratorPerlin;
import net.minecraft.world.gen.layer.IntCache;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

import net.dries007.tfc.ConfigTFC;
import net.dries007.tfc.TerraFirmaCraft;
import net.dries007.tfc.api.types.RockCategory;
import net.dries007.tfc.util.calendar.Month;
import net.dries007.tfc.util.climate.ClimateHelper;
import net.dries007.tfc.world.classic.biomes.BiomeDecoratorTFC;
import net.dries007.tfc.world.classic.chunkdata.ChunkDataTFC;
import net.dries007.tfc.world.classic.genlayers.GenLayerTFC;
import net.dries007.tfc.world.classic.genlayers.datalayers.drainage.GenDrainageLayer;
import net.dries007.tfc.world.classic.spawner.WorldEntitySpawnerTFC;
import net.dries007.tfc.world.classic.worldgen.*;

import static net.dries007.tfc.world.classic.WorldTypeTFC.SEALEVEL;

public final class VanillaWorldCompat
{
    /* 当前主世界是不是按原版高度在算（海平面不在 144） */
    private static volatile boolean vanillaHeights = false;
    private static volatile int seaLevel = 63;

    private static DataGenerator dataGenerator;
    private static long dataGeneratorSeed;
    private static final Set<Long> PENDING = new LinkedHashSet<>();
    private static Generators generators;

    // ---------------------------------------------------------------- 世界判定

    public static boolean isEnabled()
    {
        return ConfigTFC.General.WORLD.enableInNonTFCWorlds;
    }

    public static boolean isTFCWorldType(World world)
    {
        return world.getWorldType() == TerraFirmaCraft.getWorldType();
    }

    /** 非群峦世界类型的主世界，并且开启了原版世界支持 */
    public static boolean isVanillaOverworld(World world)
    {
        return isEnabled() && world.provider != null && world.provider.getDimension() == 0 && !isTFCWorldType(world);
    }

    /** 群峦的规则（刷怪、动物气候等）在这个世界的主世界生效 */
    public static boolean isTFCOverworld(World world)
    {
        return world.provider != null && world.provider.getDimension() == 0 && (isTFCWorldType(world) || isEnabled());
    }

    /** 这个世界的区块要不要挂群峦区块数据 */
    public static boolean hasChunkData(World world)
    {
        return isTFCWorldType(world) || isVanillaOverworld(world);
    }

    /** 群峦的生成器在这里要不要干活 */
    public static boolean shouldGenerate(World world, IChunkGenerator chunkGenerator)
    {
        return chunkGenerator instanceof ChunkGenTFC || isVanillaOverworld(world);
    }

    public static boolean isSaltyBiome(Biome biome)
    {
        return BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN) || BiomeDictionary.hasType(biome, BiomeDictionary.Type.BEACH);
    }

    public static boolean isVanillaWaterSource(IBlockState state)
    {
        return (state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.FLOWING_WATER) && state.getValue(BlockLiquid.LEVEL) == 0;
    }

    // ---------------------------------------------------------------- 高度换算

    /**
     * 原版世界的 Y 换算成群峦世界里的等价 Y。群峦海平面在 144，原版在 63 左右：
     * 海平面以下按比例拉伸（深处的矿还在深处、浅层矿还在浅层），海平面以上一格对一格。
     * 群峦世界里原样返回。
     */
    public static int toTfcY(int y)
    {
        if (!vanillaHeights) return y;
        final int s = seaLevel;
        return y <= s ? Math.round(y * (float) SEALEVEL / s) : y - s + SEALEVEL;
    }

    /** toTfcY 的反过程 */
    public static int fromTfcY(int tfcY)
    {
        if (!vanillaHeights) return tfcY;
        final int s = seaLevel;
        int y = tfcY <= SEALEVEL ? Math.round(tfcY * (float) s / SEALEVEL) : tfcY - SEALEVEL + s;
        return MathHelper.clamp(y, 0, 255);
    }

    public static boolean usesVanillaHeights()
    {
        return vanillaHeights;
    }

    public static WorldGenSettings defaultSettings()
    {
        return WorldGenSettings.fromString("").build();
    }

    // ---------------------------------------------------------------- 事件

    public static void init()
    {
        GameRegistry.registerWorldGenerator(new OverworldGenerator(), 100);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(VanillaWorldCompat.class);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWorldLoad(WorldEvent.Load event)
    {
        World world = event.getWorld();
        if (world.provider.getDimension() != 0) return;
        vanillaHeights = isVanillaOverworld(world);
        seaLevel = ConfigTFC.General.WORLD.nonTFCSeaLevel;
        if (!world.isRemote)
        {
            synchronized (PENDING)
            {
                PENDING.clear();
            }
            if (vanillaHeights)
            {
                TerraFirmaCraft.getLog().info("Non-TFC overworld detected ({}). TFC content will be generated into it; sea level treated as Y={}.", world.getWorldType().getName(), seaLevel);
            }
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event)
    {
        if (!event.getWorld().isRemote && event.getWorld().provider.getDimension() == 0)
        {
            synchronized (PENDING)
            {
                PENDING.clear();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onChunkLoad(ChunkEvent.Load event)
    {
        World world = event.getWorld();
        if (world.isRemote || !isVanillaOverworld(world)) return;
        Chunk chunk = event.getChunk();
        ChunkDataTFC data = ChunkDataTFC.get(chunk);
        if (!data.isInitialized())
        {
            if (!fillChunkData(world, chunk)) return;
        }
        // 已经装饰过（比如 WorldPainter 导出时标成了已装饰）但群峦还没来过的区块，排队补生成
        if (chunk.isTerrainPopulated() && !data.isDecorated() && ConfigTFC.General.WORLD.retrogenPopulatedChunks)
        {
            synchronized (PENDING)
            {
                PENDING.add(ChunkPos.asLong(chunk.x, chunk.z));
            }
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event)
    {
        if (!event.getWorld().isRemote && event.getWorld().provider.getDimension() == 0)
        {
            synchronized (PENDING)
            {
                PENDING.remove(ChunkPos.asLong(event.getChunk().x, event.getChunk().z));
            }
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote || event.world.provider.getDimension() != 0) return;
        if (!isVanillaOverworld(event.world)) return;

        WorldServer world = (WorldServer) event.world;
        ChunkProviderServer provider = world.getChunkProvider();
        List<long[]> ready = new ArrayList<>();
        synchronized (PENDING)
        {
            if (PENDING.isEmpty()) return;
            Iterator<Long> it = PENDING.iterator();
            while (it.hasNext() && ready.size() < ConfigTFC.General.WORLD.retrogenChunksPerTick)
            {
                long key = it.next();
                int x = (int) (key & 0xFFFFFFFFL);
                int z = (int) (key >>> 32);
                if (provider.getLoadedChunk(x, z) == null)
                {
                    it.remove();
                    continue;
                }
                // 和原版装饰一样，需要东、南、东南三个邻居都已加载
                if (provider.getLoadedChunk(x + 1, z) == null || provider.getLoadedChunk(x, z + 1) == null || provider.getLoadedChunk(x + 1, z + 1) == null)
                {
                    continue;
                }
                it.remove();
                ready.add(new long[] {x, z});
            }
        }
        for (long[] pos : ready)
        {
            retrogen(world, provider, (int) pos[0], (int) pos[1]);
        }
    }

    private static void retrogen(WorldServer world, ChunkProviderServer provider, int chunkX, int chunkZ)
    {
        Chunk chunk = provider.getLoadedChunk(chunkX, chunkZ);
        if (chunk == null) return;
        ChunkDataTFC data = ChunkDataTFC.get(chunk);
        if (!data.isInitialized() || data.isDecorated()) return;

        boolean fallInstantly = BlockFalling.fallInstantly;
        BlockFalling.fallInstantly = true;
        try
        {
            if (ConfigTFC.General.WORLD.retrogenOtherMods)
            {
                // 所有模组用标准接口注册的生成器（包括下面的群峦生成器）都补跑一遍
                GameRegistry.generateWorld(chunkX, chunkZ, world, provider.chunkGenerator, provider);
            }
            else
            {
                generateTFC(chunkRandom(world, chunkX, chunkZ), chunkX, chunkZ, world, provider.chunkGenerator, provider);
            }
        }
        catch (Exception e)
        {
            TerraFirmaCraft.getLog().error("Failed to generate TFC content in pre-decorated chunk ({}, {})", chunkX, chunkZ, e);
        }
        finally
        {
            BlockFalling.fallInstantly = fallInstantly;
        }
    }

    private static Random chunkRandom(World world, int chunkX, int chunkZ)
    {
        Random random = new Random(world.getSeed());
        long xSeed = random.nextLong() >> 2 + 1L;
        long zSeed = random.nextLong() >> 2 + 1L;
        random.setSeed((xSeed * chunkX + zSeed * chunkZ) ^ world.getSeed());
        return random;
    }

    // ---------------------------------------------------------------- 群峦内容生成

    public static final class OverworldGenerator implements IWorldGenerator
    {
        @Override
        public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
        {
            if (!isVanillaOverworld(world) || chunkGenerator instanceof ChunkGenTFC) return;
            generateTFC(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        }
    }

    static void generateTFC(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
    {
        Chunk chunk = world.getChunk(chunkX, chunkZ);
        ChunkDataTFC data = ChunkDataTFC.get(chunk);
        if (!data.isInitialized() && !fillChunkData(world, chunk)) return;
        if (data.isDecorated()) return;
        data.setDecorated(true);
        chunk.markDirty();

        // 生成器会读到东、南邻居的区块数据
        ensureData(world, chunkProvider, chunkX + 1, chunkZ);
        ensureData(world, chunkProvider, chunkX, chunkZ + 1);
        ensureData(world, chunkProvider, chunkX + 1, chunkZ + 1);

        Generators g = generators();
        final BlockPos blockPos = new BlockPos(chunkX << 4, 0, chunkZ << 4);
        final Biome biome = world.getBiome(blockPos.add(16, 0, 16));

        g.soilPits.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        g.oreVeins.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        g.largeRocks.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        g.trees.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        g.berryBushes.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        g.fruitTrees.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        g.plantsFor(biome).generatePlants(world, random, blockPos);
        g.looseRocks.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        if (ConfigTFC.General.WORLD.nonTFCAnimals)
        {
            WorldEntitySpawnerTFC.performWorldGenSpawning(world, biome, (chunkX << 4) + 8, (chunkZ << 4) + 8, 16, 16, random);
        }
        g.wildCrops.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
    }

    private static void ensureData(World world, IChunkProvider provider, int chunkX, int chunkZ)
    {
        Chunk chunk = provider.getLoadedChunk(chunkX, chunkZ);
        if (chunk != null && !ChunkDataTFC.get(chunk).isInitialized())
        {
            fillChunkData(world, chunk);
        }
    }

    private static synchronized Generators generators()
    {
        if (generators == null) generators = new Generators();
        return generators;
    }

    private static final class Generators
    {
        final IWorldGenerator soilPits = new WorldGenSoilPits();
        final IWorldGenerator oreVeins = new WorldGenOreVeins();
        final IWorldGenerator largeRocks = new RarityBasedWorldGen(x -> x.largeRockRarity, new WorldGenLargeRocks());
        final IWorldGenerator trees = new WorldGenTrees();
        final IWorldGenerator berryBushes = new WorldGenBerryBushes();
        final IWorldGenerator fruitTrees = new WorldGenFruitTrees();
        final IWorldGenerator wildCrops = new WorldGenWildCrops();
        final IWorldGenerator looseRocks = new WorldGenLooseRocks(true);
        // 和群峦自己的群系用同样的水生植物密度：普通、湖泊河流、沼泽
        final BiomeDecoratorTFC plants = new BiomeDecoratorTFC(0, 0);
        final BiomeDecoratorTFC wetPlants = new BiomeDecoratorTFC(4, 5);
        final BiomeDecoratorTFC swampPlants = new BiomeDecoratorTFC(16, 45);

        BiomeDecoratorTFC plantsFor(Biome biome)
        {
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.SWAMP)) return swampPlants;
            if (BiomeDictionary.hasType(biome, BiomeDictionary.Type.RIVER) || BiomeDictionary.hasType(biome, BiomeDictionary.Type.WET)) return wetPlants;
            return plants;
        }
    }

    // ---------------------------------------------------------------- 区块数据

    /**
     * 按种子算出这个区块的群峦数据（三层岩石、稳定度、排水、降雨、气温、植被），算法和群峦自己的区块生成器一致。
     * 岩层分界跟着这一列的实际地表高度走。
     */
    public static boolean fillChunkData(World world, Chunk chunk)
    {
        ChunkDataTFC data = ChunkDataTFC.get(chunk);
        if (!data.isInitialized() && chunk.getCapability(net.dries007.tfc.world.classic.chunkdata.ChunkDataProvider.CHUNK_DATA_CAPABILITY, null) == null)
        {
            return false;
        }
        DataGenerator generator = dataGenerator(world.getSeed());
        generator.fill(chunk, data);
        chunk.markDirty();
        return true;
    }

    private static synchronized DataGenerator dataGenerator(long seed)
    {
        if (dataGenerator == null || dataGeneratorSeed != seed)
        {
            dataGenerator = new DataGenerator(seed);
            dataGeneratorSeed = seed;
        }
        return dataGenerator;
    }

    private static final class DataGenerator
    {
        private final GenLayerTFC rocks1, rocks2, rocks3, stability, drainage;
        private final NoiseGeneratorPerlin rainNoise, floraDensityNoise, floraDiversityNoise, temperatureNoise;
        private final float rainfallSpread, floraDensitySpread, floraDiversitySpread;

        DataGenerator(long seed)
        {
            WorldGenSettings s = defaultSettings();
            rocks1 = GenLayerTFC.initializeRock(seed + 1, RockCategory.Layer.TOP, s.rockLayerSize);
            rocks2 = GenLayerTFC.initializeRock(seed + 2, RockCategory.Layer.MIDDLE, s.rockLayerSize);
            rocks3 = GenLayerTFC.initializeRock(seed + 3, RockCategory.Layer.BOTTOM, s.rockLayerSize);
            rainNoise = new NoiseGeneratorPerlin(new Random(seed + 4), 4);
            floraDensityNoise = new NoiseGeneratorPerlin(new Random(seed + 5), 4);
            floraDiversityNoise = new NoiseGeneratorPerlin(new Random(seed + 6), 4);
            temperatureNoise = new NoiseGeneratorPerlin(new Random(seed + 7), 4);
            stability = GenLayerTFC.initializeStability(seed + 9);
            drainage = GenDrainageLayer.initialize(seed + 11);
            rainfallSpread = (float) ConfigTFC.General.WORLD.rainfallSpreadFactor;
            floraDiversitySpread = (float) ConfigTFC.General.WORLD.floraDiversitySpreadFactor;
            floraDensitySpread = (float) ConfigTFC.General.WORLD.floraDensitySpreadFactor;
        }

        synchronized void fill(Chunk chunk, ChunkDataTFC data)
        {
            final int chunkX = chunk.x;
            final int chunkZ = chunk.z;

            IntCache.resetIntCache();
            int[] rock1 = rocks1.getInts(chunkX * 16, chunkZ * 16, 16, 16).clone();
            int[] rock2 = rocks2.getInts(chunkX * 16, chunkZ * 16, 16, 16).clone();
            int[] rock3 = rocks3.getInts(chunkX * 16, chunkZ * 16, 16, 16).clone();
            DataLayer[] stabilityLayer = layer(stability, chunkX, chunkZ);
            DataLayer[] drainageLayer = layer(drainage, chunkX, chunkZ);

            float rainfall = MathHelper.clamp(250f + 250f * rainfallSpread * (float) rainNoise.getValue(chunkX * 0.005, chunkZ * 0.005), 0, 500);
            float floraDiversity = MathHelper.clamp(0.5f + 0.5f * floraDiversitySpread * (float) floraDiversityNoise.getValue(chunkX * 0.005, chunkZ * 0.005), 0, 1);
            float floraDensity = MathHelper.clamp((0.3f + 0.2f * rainfall / 500f) + 0.4f * floraDensitySpread * (float) floraDensityNoise.getValue(chunkX * 0.05, chunkZ * 0.05), 0, 1);
            float regionalFactor = 5f * 0.09f * (float) temperatureNoise.getValue(chunkX * 0.05, chunkZ * 0.05);
            float averageTemp = ClimateHelper.monthFactor(regionalFactor, Month.AVERAGE_TEMPERATURE_MODIFIER, chunkZ << 4);

            int[] seaLevelOffset = new int[256];
            for (int x = 0; x < 16; x++)
            {
                for (int z = 0; z < 16; z++)
                {
                    int surface = findSurface(chunk, x, z);
                    seaLevelOffset[z << 4 | x] = surface < 0 ? 0 : Math.max(0, toTfcY(surface) - SEALEVEL);
                }
            }
            data.setGenerationData(rock1, rock2, rock3, stabilityLayer, drainageLayer, seaLevelOffset, rainfall, regionalFactor, averageTemp, floraDensity, floraDiversity);
        }

        private static DataLayer[] layer(GenLayerTFC gen, int chunkX, int chunkZ)
        {
            IntCache.resetIntCache();
            int[] ints = gen.getInts(chunkX * 16, chunkZ * 16, 16, 16);
            DataLayer[] layers = new DataLayer[256];
            for (int i = 0; i < 256; i++)
            {
                layers[i] = DataLayer.get(ints[i]);
            }
            return layers;
        }

        private static int findSurface(Chunk chunk, int x, int z)
        {
            int top = Math.min(255, chunk.getTopFilledSegment() + 15);
            for (int y = top; y > 0; y--)
            {
                Material m = chunk.getBlockState(x, y, z).getMaterial();
                if (m == Material.ROCK || m == Material.GROUND || m == Material.GRASS || m == Material.SAND || m == Material.CLAY)
                {
                    return y;
                }
            }
            return -1;
        }
    }

    private VanillaWorldCompat() {}
}
