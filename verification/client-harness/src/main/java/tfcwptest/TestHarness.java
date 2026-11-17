package tfcwptest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 只用于自动化验证的客户端小模组：
 * 启动参数带 -Dtfcwptest.world=存档文件夹名 时，到主菜单后自动进入该存档，
 * 进入后切成旁观模式升到空中，在服务端执行 /tfcwp status 和 /tfcwp scan，截两张图，然后退出游戏。
 */
@Mod(modid = "tfcwptest", name = "TFCWP Client Test Harness", version = "1.0", clientSideOnly = true)
public class TestHarness
{
    static final Logger LOG = LogManager.getLogger("tfcwptest");
    private final String world = System.getProperty("tfcwptest.world");
    private int state;
    private int ticks;
    private int menuTicks;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event)
    {
        if (world != null) MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        // 整合包可能用自定义主菜单，所以不认菜单类型：游戏加载完、停在任意界面 5 秒后进存档
        if (state == 0 && mc.world == null && mc.currentScreen != null && ++menuTicks >= 100)
        {
            state = 1;
            LOG.info("自动进入存档 {}", world);
            mc.launchIntegratedServer(world, world, null);
            return;
        }
        if (state != 1 || mc.player == null || mc.world == null) return;
        ticks++;
        if (ticks == 60)
        {
            run(mc, "gamemode spectator " + mc.player.getName());
            run(mc, "time set 6000");
            run(mc, "weather clear");
        }
        else if (ticks == 360 && Boolean.getBoolean("tfcwptest.checks"))
        {
            // 等补生成队列跑完再检查
            IntegratedServer server = mc.getIntegratedServer();
            server.addScheduledTask(() -> FunctionalChecks.run(server, mc.gameDir, new StringBuilder()));
        }
        else if (ticks == 400)
        {
            run(mc, "tp " + mc.player.getName() + " ~ ~22 ~ 45 28");
        }
        else if (ticks == 600)
        {
            shot(mc, "tfcwp-view-1.png");
        }
        else if (ticks == 620)
        {
            run(mc, "tp " + mc.player.getName() + " ~ ~ ~ 225 28");
        }
        else if (ticks == 820)
        {
            shot(mc, "tfcwp-view-2.png");
        }
        else if (ticks == 860)
        {
            LOG.info("验证结束，退出游戏");
            mc.shutdown();
        }
    }

    private static void run(Minecraft mc, String command)
    {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) return;
        server.addScheduledTask(() -> {
            int result = server.getCommandManager().executeCommand(server, command);
            LOG.info("执行命令 [{}] 结果 {}", command, result);
        });
    }

    private static void shot(Minecraft mc, String name)
    {
        LOG.info("截图 {}: {}", name, ScreenShotHelper.saveScreenshot(mc.gameDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer()).getUnformattedText());
    }
}
