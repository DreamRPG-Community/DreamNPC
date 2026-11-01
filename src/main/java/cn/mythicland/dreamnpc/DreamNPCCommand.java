package cn.mythicland.dreamnpc;

import cn.mythicland.dreamlib.api.DreamLibApi;
import cn.mythicland.dreamnpc.model.CommandExecutionMode;
import cn.mythicland.framework.bukkit.command.CommandBinding;
import cn.mythicland.framework.bukkit.command.CommandCompleter;
import cn.mythicland.framework.bukkit.command.CommandContext;
import cn.mythicland.framework.bukkit.command.CommandRoute;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The only DreamNPC command entry point.
 */
@CommandBinding(
        value = "dreamnpc",
        permission = "dreamnpc.admin"
)
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

    @CommandRoute(
            path = {"dreamnpc", "sel"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc sel",
            order = 90
    )
    private void select(CommandContext context) {
        context.requireArguments(0);
        service.selectByView(player(context));
    }

    @CommandRoute(
            path = {"dreamnpc", "create"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc create <名字...>",
            order = 40
    )
    private void create(CommandContext context) {
        context.requireAtLeast(1);
        service.create(player(context), context.arguments());
    }

    @CommandRoute(
            path = {"dreamnpc", "rename"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc rename <行号> <内容...>",
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
        service.rename(player(context), line,
                String.join(" ", context.arguments().subList(1, context.arguments().size())));
    }

    @CommandRoute(
            path = {"dreamnpc", "skin"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc skin <正版玩家ID>",
            order = 100
    )
    private void skin(CommandContext context) {
        context.requireArguments(1);
        service.skin(player(context), context.argument(0));
    }

    @CommandRoute(
            path = {"dreamnpc", "tph"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc tph",
            order = 110
    )
    private void teleport(CommandContext context) {
        context.requireArguments(0);
        service.teleportSelected(player(context));
    }

    @CommandRoute(
            path = {"dreamnpc", "cmd", "add"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc cmd add <执行方式> <命令...>",
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

    @CommandRoute(
            path = {"dreamnpc", "cmd", "remove"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc cmd remove <序号>",
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

    @CommandRoute(
            path = {"dreamnpc", "cmd", "list"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc cmd list",
            order = 20
    )
    private void listCommands(CommandContext context) {
        context.requireArguments(0);
        Player player = player(context);
        service.listCommands(context.sender(), player);
    }

    @CommandRoute(
            path = {"dreamnpc", "list"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc list",
            order = 60
    )
    private void list(CommandContext context) {
        context.requireArguments(0);
        service.list(context.sender());
    }

    @CommandRoute(
            path = {"dreamnpc", "delete"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc delete",
            order = 50
    )
    private void delete(CommandContext context) {
        context.requireArguments(0);
        service.delete(player(context));
    }

    @CommandRoute(
            path = {"dreamnpc", "reload"},
            maxArguments = Integer.MAX_VALUE,
            usages = "/dreamnpc reload",
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
