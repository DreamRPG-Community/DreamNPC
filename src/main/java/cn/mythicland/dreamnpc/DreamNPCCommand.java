package cn.mythicland.dreamnpc;

import cn.mythicland.dreamnpc.model.CommandExecutionMode;
import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.dreamlib.bootstrap.annotation.CommandCompleter;
import cn.mythicland.dreamlib.bootstrap.annotation.CommandComponent;
import cn.mythicland.dreamlib.bootstrap.annotation.CommandHandler;
import cn.mythicland.dreamlib.command.CommandContext;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The only DreamNPC command entry point.
 */
@CommandComponent(value = "dreamnpc", permission = "dreamnpc.admin")
public final class DreamNPCCommand {

    private final DreamNPCService service;

    public DreamNPCCommand(DreamNPCService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    private static Player player(CommandContext context) {
        if (context.sender() instanceof Player player) return player;
        context.sender().sendMessage("该命令只能由玩家执行。");
        throw context.invalidUsage();
    }

    @CommandHandler(
            value = "sel",
            usage = "/dreamnpc sel",
            order = 90
    )
    private void select(CommandContext context) {
        context.requireArguments(0);
        service.selectByView(player(context));
    }

    @CommandHandler(
            value = "create",
            usage = "/dreamnpc create <名字...>",
            order = 40
    )
    private void create(CommandContext context) {
        context.requireAtLeast(1);
        service.create(player(context), context.arguments());
    }

    @CommandHandler(
            value = "rename",
            usage = "/dreamnpc rename <行号> <内容...>",
            order = 80
    )
    private void rename(CommandContext context) {
        context.requireAtLeast(2);
        int line;
        try {
            line = Integer.parseInt(context.argument(0));
        } catch (NumberFormatException exception) {
            throw context.invalidUsage();
        }
        service.rename(player(context), line, String.join(" ", context.arguments().subList(1, context.arguments().size())));
    }

    @CommandHandler(
            value = "skin",
            usage = "/dreamnpc skin <正版玩家ID>",
            order = 100
    )
    private void skin(CommandContext context) {
        context.requireArguments(1);
        service.skin(player(context), context.argument(0));
    }

    @CommandHandler(
            value = "tph",
            usage = "/dreamnpc tph",
            order = 110
    )
    private void teleport(CommandContext context) {
        context.requireArguments(0);
        service.teleportSelected(player(context));
    }

    @CommandHandler(
            value = "cmd add",
            usage = "/dreamnpc cmd add <执行方式> <命令...>",
            order = 10
    )
    private void addCommand(CommandContext context) {
        context.requireAtLeast(2);
        CommandExecutionMode mode;
        try {
            mode = CommandExecutionMode.valueOf(context.argument(0).toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw context.invalidUsage();
        }
        service.addCommand(
                player(context),
                mode,
                String.join(" ", context.arguments().subList(1, context.arguments().size()))
        );
    }

    @CommandHandler(
            value = "cmd remove",
            usage = "/dreamnpc cmd remove <序号>",
            order = 30
    )
    private void removeCommand(CommandContext context) {
        context.requireArguments(1);
        int index;
        try {
            index = Integer.parseInt(context.argument(0));
        } catch (NumberFormatException exception) {
            throw context.invalidUsage();
        }
        service.removeCommand(player(context), index);
    }

    @CommandHandler(
            value = "cmd list",
            usage = "/dreamnpc cmd list",
            order = 20
    )
    private void listCommands(CommandContext context) {
        context.requireArguments(0);
        Player player = player(context);
        service.listCommands(context.sender(), player);
    }

    @CommandHandler(
            value = "list",
            usage = "/dreamnpc list",
            order = 60
    )
    private void list(CommandContext context) {
        context.requireArguments(0);
        service.list(context.sender());
    }

    @CommandHandler(
            value = "delete",
            usage = "/dreamnpc delete",
            order = 50
    )
    private void delete(CommandContext context) {
        context.requireArguments(0);
        service.delete(player(context));
    }

    @CommandHandler(
            value = "reload",
            usage = "/dreamnpc reload",
            order = 70
    )
    private void reload(CommandContext context) {
        context.requireArguments(0);
        service.reload().whenComplete((ignored, error) -> {
            if (error == null) {
                context.sender().sendMessage("DreamNPC 配置已重载。");
                return;
            }
            context.sender().sendMessage(
                    ChatColor.RED + "DreamNPC 配置重载失败: " + DreamLibApi.rootCauseMessage(error)
            );
        });
    }

    @CommandCompleter("cmd add")
    private List<String> completeAdd(CommandContext context) {
        if (context.arguments().size() <= 1) return List.of("player", "console", "op");
        return List.of();
    }

    @CommandCompleter("cmd remove")
    private List<String> completeRemove(CommandContext context) {
        if (!(context.sender() instanceof Player player)) return List.of();
        int count = service.commandCount(player);
        List<String> values = new ArrayList<>();
        for (int index = 1; index <= count; index++) values.add(String.valueOf(index));
        return values;
    }
}
