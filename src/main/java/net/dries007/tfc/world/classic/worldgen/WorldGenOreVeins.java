/*
 * Work under Copyright. Licensed under the EUPL.
 * See the project README.md and LICENSE.txt for more information.
 */

package net.dries007.tfc.world.classic.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.IWorldGenerator;

import net.dries007.tfc.ConfigTFC;
import net.dries007.tfc.TerraFirmaCraft;
import net.dries007.tfc.api.types.Rock;
import net.dries007.tfc.objects.blocks.stone.BlockRockVariant;
import net.dries007.tfc.world.classic.ChunkGenTFC;
import net.dries007.tfc.world.classic.chunkdata.ChunkDataTFC;
import net.dries007.tfc.world.classic.worldgen.vein.Vein;
import net.dries007.tfc.world.classic.worldgen.vein.VeinRegistry;

public class WorldGenOreVeins implements IWorldGenerator
{
    private static final com.google.common.base.Predicate<IBlockState> STONE_PREDICATE = net.minecraft.block.state.pattern.BlockMatcher.forBlock(net.minecraft.init.Blocks.STONE);

    private static final Random LOCAL_RANDOM = new Random();
    public static int CHUNK_RADIUS = 1;

    // Used to generate chunk
    public static List<Vein> getNearbyVeins(int chunkX, int chunkZ, long worldSeed, int radius)
    {
        List<Vein> veins = new ArrayList<>();
        for (int x = -radius; x <= radius; x++)
        {
            for (int z = -radius; z <= radius; z++)
            {
                getVeinsAtChunk(veins, chunkX + x, chunkZ + z, worldSeed);
            }
        }
        return veins;
    }

    // Gets veins at a single chunk. Deterministic for a specific chunk x/z and world seed
    private static void getVeinsAtChunk(List<Vein> listToAdd, int chunkX, int chunkZ, long worldSeed)
    {
        LOCAL_RANDOM.setSeed(worldSeed + chunkX * 341873128712L + chunkZ * 132897987541L);
        listToAdd.addAll(VeinRegistry.INSTANCE.getVeins().values().stream()
            .filter(veinType -> LOCAL_RANDOM.nextInt(veinType.getRarity()) == 0)
            .map(veinType -> veinType.createVein(LOCAL_RANDOM, chunkX, chunkZ))
            .collect(Collectors.toList()));
    }

    @Override
    public void generate(Random random, int chunkX, int chunkZ, World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider)
    {
        if (!net.dries007.tfc.world.classic.VanillaWorldCompat.shouldGenerate(world, chunkGenerator)) return;
        final BlockPos chunkBlockPos = new BlockPos(chunkX << 4, 0, chunkZ << 4);
        ChunkDataTFC chunkData = ChunkDataTFC.get(world, chunkBlockPos);
        if (!chunkData.isInitialized()) return;
        if (world.provider.getDimension() != 0) return;

        List<Vein> veins = getNearbyVeins(chunkX, chunkZ, world.getSeed(), CHUNK_RADIUS);
        // 改造版：非群峦世界里，矿脉长在原版石头（以及声明可被矿石替换的模组石头）里，
        // 用哪种岩石的矿石、这条矿脉能不能出现，按这个位置的群峦岩层决定；矿脉高度从群峦高度换算过来
        final boolean vanilla = !(chunkGenerator instanceof net.dries007.tfc.world.classic.ChunkGenTFC);

        for (Vein vein : veins)
        {
            boolean generated = false;
            final int lowestY = vanilla ? net.dries007.tfc.world.classic.VanillaWorldCompat.fromTfcY(vein.getLowestY()) : vein.getLowestY();
            final int highestY = vanilla ? net.dries007.tfc.world.classic.VanillaWorldCompat.fromTfcY(vein.getHighestY()) : vein.getHighestY();
            for (int x = chunkBlockPos.getX() + 8; x < chunkBlockPos.getX() + 24; x++)
            {
                for (int z = chunkBlockPos.getZ() + 8; z < chunkBlockPos.getZ() + 24; z++)
                {
                    // Do checks here that are specific to the the horizontal position, not the vertical one
                    if (vein.inRange(x, z, 0))
                    {
                        ChunkDataTFC columnData = null;
                        for (int y = lowestY; y <= highestY; y++)
                        {
                            final BlockPos posAt = new BlockPos(x, y, z);
                            final BlockPos veinPos = vanilla ? new BlockPos(x, net.dries007.tfc.world.classic.VanillaWorldCompat.toTfcY(y), z) : posAt;
                            if (random.nextDouble() >= vein.getChanceToGenerate(veinPos)) continue;
                            final IBlockState stateAt = world.getBlockState(posAt);

                            // Do checks specific to the individual block pos that is getting replaced
                            if (stateAt.getBlock() instanceof BlockRockVariant)
                            {
                                final BlockRockVariant blockAt = (BlockRockVariant) stateAt.getBlock();
                                if (blockAt.getType() == Rock.Type.RAW && vein.canSpawnIn(blockAt.getRock()))
                                {
                                    world.setBlockState(posAt, vein.getOreState(blockAt.getRock()), 2);
                                    generated = true;
                                }
                            }
                            else if (vanilla && stateAt.getBlock().isReplaceableOreGen(stateAt, world, posAt, STONE_PREDICATE))
                            {
                                if (columnData == null) columnData = ChunkDataTFC.get(world, posAt);
                                if (!columnData.isInitialized()) break;
                                final Rock rock = columnData.getRockLayerHeight(x & 15, y, z & 15);
                                if (rock != null && vein.canSpawnIn(rock))
                                {
                                    world.setBlockState(posAt, vein.getOreState(rock), 2);
                                    generated = true;
                                }
                            }
                        }
                    }
                }
            }
            // Chunk post-processing, if a vein generated
            if (vein.getType() != null)
            {
                if (generated)
                {
                    chunkData.markVeinGenerated(vein);
                }
                else if (ConfigTFC.General.DEBUG.enable)
                {
                    // Failed to generate, debug info
                    // This can be by a number of factors, mainly because at each expected replacing position we didn't find a matching raw rock.
                    // Some possible causes: Width / Height / Shape / Density / Y / Rock Layer
                    TerraFirmaCraft.getLog().debug("Failed to generate vein '{}' in chunk ({}, {}). Vein center pos ({}x, {}y, {}z)", vein.getType().getRegistryName(), chunkX, chunkZ, vein.getPos().getX(), vein.getPos().getY(), vein.getPos().getZ());
                }
            }
        }
    }
}