package dev.atsukigames.chatall;

import com.moji4j.MojiConverter;
import com.google.inject.Inject;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

@Plugin(
    id = "chatall",
    name = "ChatAll",
    version = "1.0.0",
    description = "ChatAll with romaji to Japanese conversion & user dictionary",
    authors = {"AtsukiGames"}
)
public class ChatAll {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;
    private final MojiConverter mojiConverter;
    private final Map<String, String> userDict = new HashMap<>();

    // ローマ字のみ判定（英小文字と空白）
    private static final Pattern ROMAJI_ONLY = Pattern.compile("^[a-z ]+$");

    @Inject
    public ChatAll(ProxyServer server, Logger logger, CommandManager commandManager, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
        this.mojiConverter = new MojiConverter();

        loadUserDict();
        registerDictCommand(commandManager);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("ChatAll enabled. /dict add|remove|list available.");
    }

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String rawMsg = event.getMessage();

        Optional<RegisteredServer> currentServerOpt = player.getCurrentServer().map(conn -> conn.getServer());
        if (currentServerOpt.isEmpty()) return;
        String serverName = currentServerOpt.get().getServerInfo().getName();

        // ローマ字のみなら変換候補を生成
        String converted = null;
        boolean isRomaji = ROMAJI_ONLY.matcher(rawMsg.toLowerCase(Locale.ROOT)).matches();
        if (isRomaji) {
            // 先にユーザー辞書の部分一致を適用（漢字などもここで）
            String afterDict = applyUserDict(rawMsg.toLowerCase(Locale.ROOT));
            if (!afterDict.equalsIgnoreCase(rawMsg)) {
                converted = afterDict;
            } else {
                // Moji4Jでひらがな変換（カタカナがよければconvertRomajiToKatakana）
                converted = mojiConverter.convertRomajiToHiragana(rawMsg);
            }
        }

        String displayMsg = rawMsg;
        if (converted != null && !converted.isEmpty() && !converted.equalsIgnoreCase(rawMsg)) {
            displayMsg += " §a(" + converted + ")";
        }

        // 署名付きチャット対応：元イベントはキャンセルせず、追加送信のみで同期表示
        Component formatted = Component.text()
            .append(Component.text("[", NamedTextColor.GREEN))
            .append(Component.text(serverName, NamedTextColor.GREEN))
            .append(Component.text("] ", NamedTextColor.GREEN))
            .append(Component.text(player.getUsername(), NamedTextColor.WHITE))
            .append(Component.text(": ", NamedTextColor.WHITE))
            .append(Component.text(displayMsg, NamedTextColor.WHITE))
            .build();

        server.getAllPlayers().forEach(p -> p.sendMessage(formatted));
        logger.info(String.format("[%s] %s: %s", serverName, player.getUsername(), displayMsg));
    }

    // ========= Command (/dict) =========

    private void registerDictCommand(CommandManager commandManager) {
        CommandMeta meta = commandManager.metaBuilder("dict").build();

        // Velocity 3.4.0+ は SimpleCommand/RawCommand を用い、execute(Invocation) を実装する
        SimpleCommand dictCmd = new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                CommandSource sender = invocation.source();
                String[] args = invocation.arguments();

                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("This command is only for players."));
                    return;
                }
                Player player = (Player) sender;

                if (args.length == 0) {
                    player.sendMessage(Component.text("Usage: /dict add|remove|list [key] [value]"));
                    return;
                }

                String sub = args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "add": {
                        if (args.length < 3) {
                            player.sendMessage(Component.text("Usage: /dict add [romaji] [japanese]"));
                            return;
                        }
                        String key = args[1].toLowerCase(Locale.ROOT);
                        // 値はスペース含みも許可したい場合は結合
                        String value = args[2];
                        if (args.length > 3) {
                            StringBuilder sb = new StringBuilder(value);
                            for (int i = 3; i < args.length; i++) sb.append(' ').append(args[i]);
                            value = sb.toString();
                        }
                        userDict.put(key, value);
                        saveUserDict();
                        player.sendMessage(Component.text("Added: " + key + " -> " + value));
                        break;
                    }
                    case "remove": {
                        if (args.length < 2) {
                            player.sendMessage(Component.text("Usage: /dict remove [romaji]"));
                            return;
                        }
                        String key = args[1].toLowerCase(Locale.ROOT);
                        if (userDict.remove(key) != null) {
                            saveUserDict();
                            player.sendMessage(Component.text("Removed: " + key));
                        } else {
                            player.sendMessage(Component.text("Key not found: " + key));
                        }
                        break;
                    }
                    case "list": {
                        if (userDict.isEmpty()) {
                            player.sendMessage(Component.text("Dictionary is empty."));
                        } else {
                            player.sendMessage(Component.text("Dictionary entries (" + userDict.size() + "):"));
                            userDict.forEach((k, v) -> player.sendMessage(Component.text(k + " -> " + v)));
                        }
                        break;
                    }
                    default:
                        player.sendMessage(Component.text("Unknown subcommand. Use: add, remove, list"));
                }
            }

            @Override
            public boolean hasPermission(Invocation invocation) {
                // 必要に応じて権限チェック（例：return invocation.source().hasPermission("chatall.dict");）
                return true;
            }
        };

        commandManager.register(meta, dictCmd);
    }

    // ========= Dictionary (部分一致で文章内も置換) =========

    private String applyUserDict(String text) {
        String result = text;
        // 置換順序を安定化：長いキーを先に（「kyo」と「kyou」の競合などの軽減）
        List<String> keys = new ArrayList<>(userDict.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String k : keys) {
            String v = userDict.get(k);
            if (v == null || v.isEmpty()) continue;
            result = result.replace(k, v);
        }
        return result;
    }

    private void loadUserDict() {
        try {
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
        } catch (IOException ignored) {}
        Path dictFile = dataDir.resolve("dict.txt");
        if (!Files.exists(dictFile)) return;

        try {
            List<String> lines = Files.readAllLines(dictFile);
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length == 2) {
                    userDict.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                }
            }
            logger.info("Loaded " + userDict.size() + " dictionary entries.");
        } catch (IOException e) {
            logger.warning("Failed to load dictionary: " + e.getMessage());
        }
    }

    private void saveUserDict() {
        Path dictFile = dataDir.resolve("dict.txt");
        List<String> lines = new ArrayList<>();
        userDict.forEach((k, v) -> lines.add(k + " " + v));
        try {
            Files.write(dictFile, lines);
            logger.info("Dictionary saved: " + userDict.size() + " entries.");
        } catch (IOException e) {
            logger.warning("Failed to save dictionary: " + e.getMessage());
        }
    }
}
