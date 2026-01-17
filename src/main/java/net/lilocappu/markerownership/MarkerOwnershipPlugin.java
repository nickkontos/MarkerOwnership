package net.lilocappu.markerownership;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class MarkerOwnershipPlugin extends JavaPlugin implements Listener {
    // NOTE: Compile with Java 21 (class file 65) to match Paper 1.21.x runtime.
    private static final String ADMIN_PERMISSION = "markerownership.admin";
    private static final String OWNER_KEY_PREFIX = "owner";
    private static final String DEFAULT_SET_ID = "markers";
    private static final Pattern TYPE_PREFIX_RE = Pattern.compile("^(marker|area|circle|line):");
    private static final Pattern ARG_RE = Pattern.compile("(?i)\\b([a-z]+):(\"[^\"]*\"|\\S+)");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#x([0-9a-fA-F]+);");

    private File markersFile;
    private File ownershipFile;
    private FileConfiguration ownershipConfig;
    private final Map<String, String> ownerByMarker = new HashMap<>();

    @Override
    public void onEnable() {
        Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
        if (dynmap == null) {
            getLogger().severe("Dynmap plugin not found - disabling.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        markersFile = new File(dynmap.getDataFolder(), "markers.yml");
        if (!markersFile.exists()) {
            getLogger().warning("Dynmap markers.yml not found yet: " + markersFile.getAbsolutePath());
        }
        ownershipFile = new File(getDataFolder(), "ownership.yml");
        ownershipConfig = YamlConfiguration.loadConfiguration(ownershipFile);
        loadOwnership();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("MarkerOwnership enabled. Ownership file: " + ownershipFile.getAbsolutePath());
    }

    @Override
    public void onDisable() {
        saveOwnership();
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message == null) return;
        String trimmed = message.trim();
        if (!trimmed.startsWith("/")) return;
        String[] parts = trimmed.substring(1).split("\\s+");
        if (parts.length < 2) return;
        if (!parts[0].equalsIgnoreCase("dmarker")) return;

        Player player = event.getPlayer();
        String sub = parts[1].toLowerCase(Locale.ROOT);
        ParsedArgs parsedArgs = ParsedArgs.fromMessage(trimmed, parts, 2);

        if (sub.equals("deleteset")) {
            if (!isAdmin(player)) {
                deny(event, player);
            }
            return;
        }

        if (sub.startsWith("delete")) {
            if (isAdmin(player)) return;
            String type = deleteType(sub);
            DeleteTarget target = parseDeleteTarget(parsedArgs);
            if (target == null) return;
            MarkerRecord record = resolveMarker(type, target);
            if (record == null) {
                deny(event, player);
                return;
            }
            if (!isOwner(player, record.key)) {
                deny(event, player);
                return;
            }
            ownerByMarker.remove(record.key);
            saveOwnership();
            return;
        }

        if (sub.equals("add") || sub.equals("addarea") || sub.equals("addcircle") || sub.equals("addline")) {
            String type = addType(sub);
            CreateTarget target = parseCreateTarget(parsedArgs);
            if (target == null) return;
            scheduleOwnershipCapture(player, type, target, 0, false);
            return;
        }

        if (sub.startsWith("update")) {
            String type = updateType(sub);
            CreateTarget target = parseCreateTarget(parsedArgs);
            if (target == null) return;
            if (!isAdmin(player)) {
                MarkerRecord record = resolveMarker(type, target.toDeleteTarget());
                if (record != null && !isOwner(player, record.key)) {
                    deny(event, player);
                    return;
                }
            }
            // Do not claim ownership on update; only enforce ownership if it exists.
            return;
        }
    }

    private boolean isAdmin(Player player) {
        return player.isOp() || player.hasPermission(ADMIN_PERMISSION);
    }

    private void deny(PlayerCommandPreprocessEvent event, Player player) {
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "You can only delete markers you created.");
    }

    private String deleteType(String sub) {
        if (sub.equals("deletearea")) return "area";
        if (sub.equals("deletecircle")) return "circle";
        if (sub.equals("deleteline")) return "line";
        return "marker";
    }

    private String addType(String sub) {
        if (sub.equals("addarea")) return "area";
        if (sub.equals("addcircle")) return "circle";
        if (sub.equals("addline")) return "line";
        return "marker";
    }

    private String updateType(String sub) {
        if (sub.equals("updatearea")) return "area";
        if (sub.equals("updatecircle")) return "circle";
        if (sub.equals("updateline")) return "line";
        return "marker";
    }

    private DeleteTarget parseDeleteTarget(ParsedArgs args) {
        if (args.id == null && args.label == null) return null;
        return new DeleteTarget(args.id, args.label, args.setId);
    }

    private CreateTarget parseCreateTarget(ParsedArgs args) {
        if (args.id == null && args.label == null) return null;
        return new CreateTarget(args.id, args.label, args.setId);
    }

    private MarkerRecord resolveMarker(String type, DeleteTarget target) {
        if (markersFile == null || !markersFile.exists()) return null;
        String setId = target.setId == null || target.setId.isEmpty() ? DEFAULT_SET_ID : target.setId;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(markersFile);
        ConfigurationSection setsSec = cfg.getConfigurationSection("sets");
        if (setsSec == null) return null;
        ConfigurationSection setSec = setsSec.getConfigurationSection(setId);
        if (setSec == null) return null;

        String id = target.id;
        String label = target.label;
        if (id == null && label != null) {
            id = label;
        }

        if (type.equals("area")) {
            ConfigurationSection sec = setSec.getConfigurationSection("areas");
            String found = findByIdOrLabel(sec, id, label);
            return found == null ? null : new MarkerRecord("area", setId, found);
        }
        if (type.equals("circle")) {
            ConfigurationSection sec = setSec.getConfigurationSection("circles");
            String found = findByIdOrLabel(sec, id, label);
            return found == null ? null : new MarkerRecord("circle", setId, found);
        }
        if (type.equals("line")) {
            ConfigurationSection sec = setSec.getConfigurationSection("lines");
            String found = findByIdOrLabel(sec, id, label);
            return found == null ? null : new MarkerRecord("line", setId, found);
        }

        ConfigurationSection sec = setSec.getConfigurationSection("markers");
        String found = findByIdOrLabel(sec, id, label);
        return found == null ? null : new MarkerRecord("marker", setId, found);
    }

    private String findByIdOrLabel(ConfigurationSection sec, String id, String label) {
        if (sec == null) return null;
        if (id != null && sec.isConfigurationSection(id)) {
            return id;
        }
        if (label == null || label.isEmpty()) return null;
        String target = normalizeLabel(label);
        for (String key : sec.getKeys(false)) {
            ConfigurationSection marker = sec.getConfigurationSection(key);
            if (marker == null) continue;
            String markerLabel = normalizeLabel(marker.getString("label"));
            if (markerLabel != null && markerLabel.equalsIgnoreCase(target)) {
                return key;
            }
        }
        return null;
    }

    private String normalizeLabel(String label) {
        if (label == null) return null;
        return unescapeHtml(label).trim();
    }

    private String unescapeHtml(String input) {
        String out = input;
        out = out.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");

        Matcher dec = NUM_ENTITY.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (dec.find()) {
            String code = dec.group(1);
            try {
                int value = Integer.parseInt(code);
                dec.appendReplacement(sb, Matcher.quoteReplacement(Character.toString((char) value)));
            } catch (NumberFormatException e) {
                dec.appendReplacement(sb, Matcher.quoteReplacement(dec.group(0)));
            }
        }
        dec.appendTail(sb);
        out = sb.toString();

        Matcher hex = HEX_ENTITY.matcher(out);
        sb = new StringBuffer();
        while (hex.find()) {
            String code = hex.group(1);
            try {
                int value = Integer.parseInt(code, 16);
                hex.appendReplacement(sb, Matcher.quoteReplacement(Character.toString((char) value)));
            } catch (NumberFormatException e) {
                hex.appendReplacement(sb, Matcher.quoteReplacement(hex.group(0)));
            }
        }
        hex.appendTail(sb);
        return sb.toString();
    }

    private boolean isOwner(Player player, String key) {
        String owner = ownerByMarker.get(key);
        if (owner == null) return false;
        return owner.equals(player.getUniqueId().toString());
    }

    private void loadOwnership() {
        ownerByMarker.clear();
        if (ownershipConfig == null) return;
        ConfigurationSection ownerSection = ownershipConfig.getConfigurationSection(OWNER_KEY_PREFIX);
        int loaded = 0;
        if (ownerSection != null) {
            for (String key : ownerSection.getKeys(true)) {
                Object raw = ownerSection.get(key);
                if (!(raw instanceof String)) continue;
                String value = (String) raw;
                if (!value.isEmpty()) {
                    String normalized = normalizeKey(key);
                    if (normalized != null) {
                        ownerByMarker.put(normalized, value);
                        loaded++;
                    }
                }
            }
        }
        for (String key : ownershipConfig.getKeys(false)) {
            if (!key.startsWith(OWNER_KEY_PREFIX + ".")) continue;
            String markerKey = key.substring((OWNER_KEY_PREFIX + ".").length());
            Object raw = ownershipConfig.get(key);
            if (!(raw instanceof String)) continue;
            String value = (String) raw;
            if (!value.isEmpty()) {
                String normalized = normalizeKey(markerKey);
                if (normalized != null) {
                    ownerByMarker.put(normalized, value);
                    loaded++;
                }
            }
        }
        getLogger().info("Loaded " + loaded + " marker ownership entries.");
    }

    private void saveOwnership() {
        if (ownershipConfig == null || ownershipFile == null) return;
        ownershipConfig.set(OWNER_KEY_PREFIX, null);
        ownershipConfig.getKeys(false).forEach(k -> {
            if (k.startsWith(OWNER_KEY_PREFIX + ".")) ownershipConfig.set(k, null);
        });
        for (Map.Entry<String, String> entry : ownerByMarker.entrySet()) {
            ownershipConfig.set(OWNER_KEY_PREFIX + "." + entry.getKey(), entry.getValue());
        }
        try {
            ownershipConfig.save(ownershipFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save ownership.yml: " + e.getMessage());
        }
    }

    private void scheduleOwnershipCapture(Player player, String type, CreateTarget target, int attempt, boolean onlyIfMissing) {
        long delayTicks = attempt == 0 ? 2L : Math.min(40L, 10L * attempt);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            MarkerRecord record = resolveMarker(type, target.toDeleteTarget());
            if (record == null) {
                if (attempt < 5) {
                    scheduleOwnershipCapture(player, type, target, attempt + 1, onlyIfMissing);
                }
                return;
            }
            if (onlyIfMissing && ownerByMarker.containsKey(record.key)) {
                return;
            }
            ownerByMarker.put(record.key, player.getUniqueId().toString());
            saveOwnership();
        }, delayTicks);
    }

    private static class DeleteTarget {
        final String id;
        final String label;
        final String setId;
        DeleteTarget(String id, String label, String setId) {
            this.id = id;
            this.label = label;
            this.setId = setId;
        }
    }

    private static class CreateTarget {
        final String id;
        final String label;
        final String setId;
        CreateTarget(String id, String label, String setId) {
            this.id = id;
            this.label = label;
            this.setId = setId;
        }
        DeleteTarget toDeleteTarget() {
            return new DeleteTarget(id, label, setId);
        }
    }

    private static class MarkerRecord {
        final String key;
        MarkerRecord(String type, String setId, String markerId) {
            this.key = buildKey(type, setId, markerId);
        }
    }

    private static String buildKey(String type, String setId, String markerId) {
        String safeSetId = (setId == null || setId.isEmpty()) ? DEFAULT_SET_ID : setId;
        return type + ":" + safeSetId + ":" + markerId;
    }

    private static String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) return null;
        String type = "marker";
        Matcher typeMatch = TYPE_PREFIX_RE.matcher(rawKey);
        if (typeMatch.find()) {
            type = typeMatch.group(1);
        }
        String markerId = extractMarkerId(rawKey);
        if (markerId == null || markerId.isEmpty()) return null;
        String setId = DEFAULT_SET_ID;

        if (rawKey.startsWith(type + ":")) {
            String remainder = rawKey.substring(type.length() + 1);
            int idx = remainder.indexOf(':');
            if (idx > 0) {
                String candidate = remainder.substring(0, idx);
                if (isSimpleSetId(candidate)) {
                    setId = candidate;
                }
            }
        }
        return buildKey(type, setId, markerId);
    }

    private static boolean isSimpleSetId(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '_' || c == '-') continue;
            if (Character.isLetterOrDigit(c)) continue;
            return false;
        }
        return true;
    }

    private static String extractMarkerId(String rawKey) {
        int idx = rawKey.lastIndexOf(':');
        if (idx == -1 || idx == rawKey.length() - 1) return null;
        return rawKey.substring(idx + 1);
    }

    private static class ParsedArgs {
        final String id;
        final String label;
        final String setId;

        private ParsedArgs(String id, String label, String setId) {
            this.id = id;
            this.label = label;
            this.setId = setId;
        }

        static ParsedArgs fromMessage(String rawMessage, String[] parts, int startIdx) {
            String id = null;
            String label = null;
            String setId = null;

            Matcher matcher = ARG_RE.matcher(rawMessage);
            while (matcher.find()) {
                String key = matcher.group(1).toLowerCase(Locale.ROOT);
                String value = stripQuotes(matcher.group(2));
                if (key.equals("id")) {
                    id = value;
                } else if (key.equals("label")) {
                    label = value;
                } else if (key.equals("set")) {
                    setId = value;
                }
            }

            if (label == null) {
                for (int i = startIdx; i < parts.length; i++) {
                    String token = parts[i];
                    if (token.contains(":")) continue;
                    label = stripQuotes(token);
                    break;
                }
            }

            return new ParsedArgs(id, label, setId);
        }

        private static String stripQuotes(String value) {
            if (value == null) return null;
            String trimmed = value.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }
    }
}
